package com.futa_gtnh.block;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 「IO 节点」方块实体：共享存储的自动化输入输出口，不带查看库存的界面。
 *
 * <p>
 * 和共享终端（{@link TileEntitySharedTerminal}）共用同一套搬运引擎（{@link TerminalIoEngine}）
 * 与配置（{@link TerminalIoConfig}），区别只在「形态」：
 * <ul>
 * <li>方块本身<b>不占满整格</b>：中心一个小节点，只向「那一面真的挨着能搬东西的方块」伸出连接臂
 * （几何见 {@link IoNodeGeometry}，连接判定见 {@link #refreshConnections()}）；</li>
 * <li>不实现 {@code ISidedInventory} / {@code IFluidHandler} —— 没有被动路径，
 * 管道不能直接从这里抽东西，一切搬运都由面配置驱动（默认全部关闭，放下来只是个带连接的模型）；</li>
 * <li>右键某根连接臂打开那一面的配置界面（模式 / 筛选 / 节奏）。</li>
 * </ul>
 *
 * <p>
 * <b>连接的判定比「邻居是个容器」更严</b>（EnderIO 导管的做法）：邻居那一面必须真的开出了
 * 可访问的槽位 / 罐子才伸臂 —— 贴着石头、贴着被覆盖板封死的机器面，都只是没有臂，
 * 免得「看着连上了、实际搬不动」。
 */
public class TileEntityIoNode extends TileEntity {

    /** 连接重算的低频兜底：邻居的方块实体可能不变但能力变了（换覆盖板之类），隔一阵子核对一次。 */
    private static final int RECHECK_INTERVAL = 100;

    /** 六个面的主动搬运方向与输出白名单。 */
    private final TerminalIoConfig io = new TerminalIoConfig();
    /** 主动搬运的节流计时，单位 tick。 */
    private int ioTimer;
    /** 连接重算的兜底计时。 */
    private int recheckTimer;

    /**
     * 哪几个面真的接着能搬物品的方块（位掩码，位 {@code i} = {@code ForgeDirection.getOrientation(i)}）。
     *
     * <p>
     * 服务端计算，渲染靠它决定画哪几根臂；两边靠描述包同步
     * （{@link #getDescriptionPacket()}，掩码一变就 {@code markBlockForUpdate}）。
     */
    private int itemMask;
    /** 同 {@link #itemMask}，流体侧。 */
    private int fluidMask;
    /**
     * 连接还没算过（放置 / 区块加载时置位）。
     *
     * <p>
     * <b>为什么不在 {@link #validate()} 里直接算</b>：validate 是在<b>区块实体加载过程中</b>
     * 被调用的，这时去 {@code getTileEntity} 邻居位置，邻居在还没加载完的相邻区块里的话会
     * 触发一次<b>同步区块加载</b> —— 而那又会加载实体、又调 validate…… 递归加载直接把
     * 服务端栈打爆（实测 StackOverflowError）。所以这里只做个记号，等第一个 tick
     * （区块加载早已结束）再算。
     */
    private boolean connectionsPending = true;

    public TerminalIoConfig getIo() {
        return io;
    }

    /** @return 这一面当前有没有物品连接（客户端读的是同步过来的掩码） */
    public boolean hasItemConnection(ForgeDirection face) {
        return face != null && (itemMask & (1 << face.ordinal())) != 0;
    }

    /** @return 这一面当前有没有流体连接 */
    public boolean hasFluidConnection(ForgeDirection face) {
        return face != null && (fluidMask & (1 << face.ordinal())) != 0;
    }

    /** @return 物品连接掩码（位 i = ForgeDirection.getOrientation(i)） */
    public int getItemMask() {
        return itemMask;
    }

    /** @return 流体连接掩码 */
    public int getFluidMask() {
        return fluidMask;
    }

    /** 配置改了之后调一次：存档重新落盘，渲染状态（端帽颜色）也刷一遍。 */
    public void onIoChanged() {
        markDirty();
        if (worldObj != null && !worldObj.isRemote) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** 邻居变了（放置 / 破坏 / 换方块）：立刻重算连接。 */
    public void neighbourChanged() {
        if (worldObj == null || worldObj.isRemote) return;
        refreshConnections();
    }

    @Override
    public void validate() {
        super.validate();
        // 只做记号，不在区块加载过程中去碰相邻区块（见 connectionsPending 的注释）
        if (worldObj != null && !worldObj.isRemote) connectionsPending = true;
    }

    /**
     * 重算六面的连接掩码；变了就要求客户端刷新渲染。
     *
     * <p>
     * 物品：邻居是 {@link IInventory}，而且（若是有面概念的 {@link ISidedInventory}）
     * 在它对着我们的那一面上真的开出了可访问槽位。流体：邻居是 {@link IFluidHandler}
     * 且那一面报得出罐子信息。共享终端和别的 IO 节点永远不算 —— 从共享存储往共享存储搬没有意义。
     */
    public void refreshConnections() {
        if (worldObj == null || worldObj.isRemote) return;

        int newItemMask = 0;
        int newFluidMask = 0;
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighbour = worldObj
                .getTileEntity(xCoord + face.offsetX, yCoord + face.offsetY, zCoord + face.offsetZ);
            if (neighbour == null || neighbour instanceof TileEntitySharedTerminal
                || neighbour instanceof TileEntityIoNode) continue;

            ForgeDirection neighbourSide = face.getOpposite();
            if (isItemTarget(neighbour, neighbourSide)) newItemMask |= 1 << face.ordinal();
            if (isFluidTarget(neighbour, neighbourSide)) newFluidMask |= 1 << face.ordinal();
        }

        if (newItemMask != itemMask || newFluidMask != fluidMask) {
            itemMask = newItemMask;
            fluidMask = newFluidMask;
            // 掩码只影响渲染，不进存档；markBlockForUpdate 会让客户端重收描述包
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    private static boolean isItemTarget(TileEntity tile, ForgeDirection side) {
        if (!(tile instanceof IInventory)) return false;

        // ★ GT 的机器 / 输入总线 / 输入仓改用「有没有槽位」来判，而不是 getAccessibleSlotsFromSide。
        //
        // GT 那边是这么写的：
        //
        // if (canAccessData() && (cover.letsItemsOut(-1) || cover.letsItemsIn(-1)))
        // return mMetaTileEntity.getAccessibleSlotsFromSide(side);
        // return GTValues.emptyIntArray; // ← 空数组
        //
        // 而 GT 的机器普遍不覆盖 getAccessibleSlotsFromSide（用基类），面访问实际是由
        // canInsertItem / canExtractItem 控制的 —— 于是这个方法对绝大多数 GT 机器常年返回
        // 空数组，我们的严格判定就恒为假：连接臂和端帽永远不画。
        // 玩家看到的就是「旁边明明摆着 GT 机器或输入总线，节点却是光的」。
        //
        // getSizeInventory 是 GT 认真实现过的（canAccessData 为假时返回 0，机器没槽位也是 0），
        // 拿它当「这一面有没有东西可搬」的判据既准确又能自愈 —— 机器还没初始化完的那一瞬间
        // 可能读到 0，但 updateEntity 每 100 tick 会重算一次（见 RECHECK_INTERVAL）。
        //
        // 放宽是安全的：这个掩码只影响渲染。真正的每一次搬运都由 TerminalIoEngine 现场校验
        // canInsertItem / canExtractItem，不会被这里放行。
        if (tile instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) {
            try {
                return ((IInventory) tile).getSizeInventory() > 0;
            } catch (Throwable t) {
                return false;
            }
        }

        if (tile instanceof ISidedInventory) {
            // 有面概念的容器：那一面必须真的开出了槽位（被覆盖板封死的面这里会返回空数组）
            try {
                return ((ISidedInventory) tile).getAccessibleSlotsFromSide(side.ordinal()).length > 0;
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFluidTarget(TileEntity tile, ForgeDirection side) {
        if (!(tile instanceof IFluidHandler)) return false;
        try {
            FluidTankInfo[] tanks = ((IFluidHandler) tile).getTankInfo(side);
            return tanks != null && tanks.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 心跳：和共享终端同一个套路 —— 没配任何面时第一件事就返回，
     * 配了就按档位节流，把实际搬运包在 try/catch 里（邻居是别的模组写的）。
     */
    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;

        // 放置 / 区块加载欠下的那一次连接计算：现在区块加载已经结束，查邻居是安全的
        if (connectionsPending) {
            connectionsPending = false;
            refreshConnections();
        }

        // 低频兜底：邻居方块没换、但那一面的能力变了（换覆盖板、机器切换朝向）的情况
        if (++recheckTimer >= RECHECK_INTERVAL) {
            recheckTimer = 0;
            refreshConnections();
        }

        if (!io.hasAnyMode()) return;

        int interval = Math.max(1, io.getIntervalTicks());
        if (++ioTimer < interval) return;
        ioTimer = 0;

        try {
            TerminalIoEngine.tick(worldObj, xCoord, yCoord, zCoord, io);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("IO 节点：主动搬运时出错（{} {} {}），这一轮跳过", xCoord, yCoord, zCoord, t);
        }
    }

    // ==================================================================
    // 存档 / 客户端同步
    // ==================================================================

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (io.hasAnyMode() || io.hasAnyFilter() || io.hasCustomRates()) {
            tag.setTag("io", io.writeToNbt());
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("io")) {
            io.readFromNbt(tag.getCompoundTag("io"));
        }
    }

    /**
     * 客户端渲染状态：连接掩码 + 每面的模式（端帽颜色用）。
     * 比整份配置小得多 —— 筛选条件那一大坨客户端不需要。
     */
    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("im", itemMask);
        tag.setInteger("fm", fluidMask);

        byte[] itemModes = new byte[TerminalIoConfig.FACES];
        byte[] fluidModes = new byte[TerminalIoConfig.FACES];
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            itemModes[face.ordinal()] = (byte) io.getMode(face, false)
                .ordinal();
            fluidModes[face.ordinal()] = (byte) io.getMode(face, true)
                .ordinal();
        }
        tag.setByteArray("imode", itemModes);
        tag.setByteArray("fmode", fluidModes);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound tag = pkt.func_148857_g();
        itemMask = tag.getInteger("im");
        fluidMask = tag.getInteger("fm");

        byte[] itemModes = tag.getByteArray("imode");
        byte[] fluidModes = tag.getByteArray("fmode");
        TerminalIoConfig.Mode[] values = TerminalIoConfig.Mode.values();
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            int i = face.ordinal();
            if (itemModes.length > i && itemModes[i] >= 0 && itemModes[i] < values.length) {
                io.setMode(face, false, values[itemModes[i]]);
            }
            if (fluidModes.length > i && fluidModes[i] >= 0 && fluidModes[i] < values.length) {
                io.setMode(face, true, values[fluidModes[i]]);
            }
        }
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }
}
