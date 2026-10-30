package com.futa_gtnh.aewireless;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.FluidStack;

import com.mojang.authlib.GameProfile;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.core.worlddata.WorldData;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.tile.TileEvent;
import appeng.tile.events.TileEventType;
import appeng.tile.grid.AENetworkTile;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/**
 * 「AE 无线发信端」的方块实体：本模组进入 ME 网络的端点。
 *
 * <p>
 * 只做三件事：加入网络（需要通道）、暴露「查询网络里某种物品/流体存量」的服务、
 * 维护一个可读的名字。所有无线逻辑都在接收端覆盖板那边 —— 它直接拿到本 TE
 * 的引用调 {@link #queryItemAmount} / {@link #queryFluidAmount}，不走网络包。
 *
 * <p>
 * <b>每个网络只能有一个生效的发信端</b>：同网络出现多个时，按（维度, x, y, z）
 * 字典序最小的那个生效，其余进入「冲突」状态 —— 不响应查询、GUI 显示错误。
 * 用户拆掉多余的那个即可恢复。
 *
 * <p>
 * 权限（哪些接收端可以连它）由放置者归属的 GTNHLib 队伍决定，见
 * {@link AeSignalTeam#sameTeam}。查询方是覆盖板所在机器的所有者。
 */
public class TileAeSignalTransmitter extends AENetworkTile {

    /** 空闲功耗。比存储元件略低 —— 它只提供查询，不搬东西。 */
    private static final double IDLE_POWER = 0.5;

    /** 名字上限。 */
    public static final int MAX_NAME_LENGTH = 32;

    private String signalName = "";
    private UUID owner;

    public TileAeSignalTransmitter() {
        getProxy().setFlags(GridFlags.REQUIRE_CHANNEL);
        getProxy().setIdlePowerUsage(IDLE_POWER);
    }

    @Override
    protected AENetworkProxy createProxy() {
        return new AENetworkProxy(this, "proxy", getItemFromTile(this), true) {

            @Override
            public void readFromNBT(NBTTagCompound data) {
                super.readFromNBT(data);
                // getNode calls this before updateState joins neighboring grids. Repair legacy
                // unowned nodes here, even when the saved owner is offline; keep valid AE IDs.
                IGridNode node = super.getNode();
                if (node != null && node.getPlayerID() < 0 && owner != null) {
                    // AE requires a complete profile but indexes it by UUID only; no online lookup.
                    node.setPlayerID(
                        WorldData.instance()
                            .playerData()
                            .getPlayerID(new GameProfile(owner, owner.toString())));
                }
            }
        };
    }

    // ==================================================================
    // 名字 / 所有者
    // ==================================================================

    public String getSignalName() {
        return signalName;
    }

    /**
     * 设置名字（服务端，GUI 应用按钮走这里）。空名字会被 {@link AeSignalRegistry} 视为「未命名」。
     */
    public void setSignalName(String name) {
        String trimmed = net.minecraft.util.ChatAllowedCharacters.filerAllowedCharacters(name == null ? "" : name)
            .trim();
        trimmed = com.futa_gtnh.network.Utf8Text.limitLength(trimmed, MAX_NAME_LENGTH);
        this.signalName = trimmed;
        markDirty();
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        markDirty();
    }

    /** 玩家是否可以改这台发信端的名字（所有者本人或同队）。 */
    public boolean canManage(net.minecraft.entity.player.EntityPlayer player) {
        if (player == null) return false;
        if (owner == null) return true;
        return AeSignalTeam.sameTeam(owner, player.getUniqueID());
    }

    // ==================================================================
    // 状态
    // ==================================================================

    /** 发信端的三态。 */
    public enum State {

        /** 没进网络 / 没电 / 没通道。 */
        OFFLINE,

        /** 本网络里有多个发信端，这个不是字典序最小的那个。 */
        CONFLICT,

        /** 在线且是本网络唯一生效的发信端。 */
        ONLINE;

        public static final State[] VALUES = values();
    }

    public State getState() {
        IGridNode node = getProxy().getNode();
        if (node == null || !getProxy().isActive()) return State.OFFLINE;
        return isPrimaryTransmitter() ? State.ONLINE : State.CONFLICT;
    }

    /**
     * 是否本网络里字典序最小的发信端（服务端查询；网格机器集合按类索引，代价很小）。
     */
    private boolean isPrimaryTransmitter() {
        try {
            IGrid grid = getProxy().getGrid();
            IMachineSet peers = grid.getMachines(TileAeSignalTransmitter.class);
            for (IGridNode node : peers) {
                if (node.getMachine() instanceof TileAeSignalTransmitter other && other != this
                    && comparePosition(other, this) < 0) return false;
            }
            return true;
        } catch (GridAccessException ignored) {
            return false;
        }
    }

    private static int comparePosition(TileAeSignalTransmitter a, TileAeSignalTransmitter b) {
        int dim = Integer.compare(a.worldObj.provider.dimensionId, b.worldObj.provider.dimensionId);
        if (dim != 0) return dim;
        int x = Integer.compare(a.xCoord, b.xCoord);
        if (x != 0) return x;
        int y = Integer.compare(a.yCoord, b.yCoord);
        if (y != 0) return y;
        return Integer.compare(a.zCoord, b.zCoord);
    }

    /**
     * 查询服务入口（服务端，被接收端覆盖板直接调用）。
     *
     * @return ME 网络里该物品的存量；离线 / 冲突 / 异常一律 -1（和「存量 0」区分开）
     */
    public long queryItemAmount(ItemStack stack) {
        if (stack == null || getState() != State.ONLINE) return -1;
        try {
            IStorageGrid storage = getProxy().getStorage();
            IAEItemStack key = AEItemStack.create(stack);
            if (key == null) return -1;
            IMEMonitor<IAEItemStack> monitor = itemMonitor(storage);
            if (monitor == null) return -1;
            IAEItemStack found = monitor.getStorageList()
                .findPrecise(key);
            return found == null ? 0 : found.getStackSize();
        } catch (GridAccessException | IllegalStateException ignored) {
            return -1;
        }
    }

    /** 同 {@link #queryItemAmount}，流体版。只看流体种类，不管请求里的数量。 */
    public long queryFluidAmount(FluidStack stack) {
        if (stack == null || getState() != State.ONLINE) return -1;
        try {
            IStorageGrid storage = getProxy().getStorage();
            IAEFluidStack key = AEFluidStack.create(stack);
            if (key == null) return -1;
            IMEMonitor<IAEFluidStack> monitor = fluidMonitor(storage);
            if (monitor == null) return -1;
            IAEFluidStack found = monitor.getStorageList()
                .findPrecise(key);
            return found == null ? 0 : found.getStackSize();
        } catch (GridAccessException | IllegalStateException ignored) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static IMEMonitor<IAEItemStack> itemMonitor(IStorageGrid storage) {
        IMEMonitor<?> monitor = storage.getMEMonitor(AEItemStackType.ITEM_STACK_TYPE);
        return monitor instanceof IMEMonitor ? (IMEMonitor<IAEItemStack>) monitor : null;
    }

    @SuppressWarnings("unchecked")
    private static IMEMonitor<IAEFluidStack> fluidMonitor(IStorageGrid storage) {
        IMEMonitor<?> monitor = storage.getMEMonitor(AEFluidStackType.FLUID_STACK_TYPE);
        return monitor instanceof IMEMonitor ? (IMEMonitor<IAEFluidStack>) monitor : null;
    }

    // ==================================================================
    // 生命周期 / 注册表
    // ==================================================================

    @Override
    public void onReady() {
        super.onReady();
        if (!worldObj.isRemote) AeSignalRegistry.register(this);
    }

    @Override
    public void invalidate() {
        if (!worldObj.isRemote) AeSignalRegistry.unregister(this);
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (!worldObj.isRemote) AeSignalRegistry.unregister(this);
        super.onChunkUnload();
    }

    /** 方块被敲掉时（breakBlock）补充出册 —— 走不到 invalidate 的路径兜底。 */
    public void onRemoved() {
        if (!worldObj.isRemote) AeSignalRegistry.unregister(this);
    }

    // ==================================================================
    // NBT
    // ==================================================================

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readFromNBT_Signal(NBTTagCompound data) {
        signalName = data.getString("futaSignalName");
        if (data.hasKey("futaSignalOwnerM") && data.hasKey("futaSignalOwnerL")) {
            owner = new UUID(data.getLong("futaSignalOwnerM"), data.getLong("futaSignalOwnerL"));
        } else {
            owner = null;
        }
    }

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeToNBT_Signal(NBTTagCompound data) {
        data.setString("futaSignalName", signalName);
        if (owner != null) {
            data.setLong("futaSignalOwnerM", owner.getMostSignificantBits());
            data.setLong("futaSignalOwnerL", owner.getLeastSignificantBits());
        }
    }

    /** 按注册表键解析发信端（服务端）。区块未加载时返回 null，视同离线。 */
    public static TileAeSignalTransmitter resolve(int dim, int x, int y, int z) {
        World world = MinecraftServer.getServer() == null ? null
            : MinecraftServer.getServer()
                .worldServerForDimension(dim);
        if (!(world instanceof WorldServer)) return null;
        // worldServerForDimension 可能返回已卸载维度的空壳，再校验一次区块
        if (!world.blockExists(x, y, z)) return null;
        return world.getTileEntity(x, y, z) instanceof TileAeSignalTransmitter t ? t : null;
    }
}
