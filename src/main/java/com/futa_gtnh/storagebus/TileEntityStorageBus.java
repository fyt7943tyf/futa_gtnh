package com.futa_gtnh.storagebus;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

/**
 * 共享存储总线方块实体。
 *
 * <p>
 * 它自己不存任何东西，只做三件事：
 * <ol>
 * <li>把配置（目标面 / 优先级 / 访问模式 / 筛选）持久化；</li>
 * <li>每隔 {@link Config#storageBusRefreshTicks} 扫描一次目标容器，更新镜像；</li>
 * <li>区块加载时向 {@link StorageBusRegistry} 登记，卸载时注销。</li>
 * </ol>
 *
 * <p>
 * 登记<b>不在 {@code validate()} 里做</b>：validate 发生在区块实体加载过程中，
 * 这时访问相邻区块会触发同步区块加载（和 IO 节点踩过的 StackOverflow 同一个坑）。
 * 这里只做记号，等第一个 {@code updateEntity()} 再登记和扫描。
 */
public class TileEntityStorageBus extends TileEntity {

    private final StorageBusConfig config = new StorageBusConfig();
    private final StorageBusLink link = new StorageBusLink(this);

    private boolean registered;
    private boolean pendingRegister = true;
    private boolean refreshRequested;
    private int refreshTimer;

    public StorageBusConfig getConfig() {
        return config;
    }

    public StorageBusLink getLink() {
        return link;
    }

    public boolean isRegistered() {
        return registered;
    }

    /** 界面打开 / 配置改动时调用；已经登记过就立刻重扫目标。 */
    public void ensureRegistered() {
        if (worldObj == null || worldObj.isRemote || registered) return;
        try {
            StorageBusRegistry.register(link);
            registered = true;
            refreshRequested = false;
            refreshTimer = 0;
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("存储总线：登记失败（{} {} {}），下个周期重试", xCoord, yCoord, zCoord, t);
        }
    }

    /** 目标面 / 筛选 / 访问模式 / 优先级变化后调用。 */
    public void onConfigChanged() {
        if (worldObj == null || worldObj.isRemote) return;
        syncMetadata();
        markDirty();
        if (!registered) {
            ensureRegistered();
        } else {
            try {
                StorageBusRegistry.refresh(link);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("存储总线：刷新目标失败（{} {} {}）", xCoord, yCoord, zCoord, t);
            }
            refreshRequested = false;
            refreshTimer = 0;
        }
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** 邻居变了：下个 tick 立刻重扫，别等完整刷新周期。 */
    public void requestRefresh() {
        if (worldObj == null || worldObj.isRemote) return;
        refreshRequested = true;
    }

    @Override
    public void validate() {
        super.validate();
        // 只做记号，不在区块加载过程中碰相邻区块（见类注释）
        if (worldObj != null && !worldObj.isRemote) pendingRegister = true;
    }

    @Override
    public void invalidate() {
        if (registered) {
            registered = false;
            try {
                StorageBusRegistry.unregister(link);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("存储总线：注销时出错（{} {} {}）", xCoord, yCoord, zCoord, t);
            }
        }
        super.invalidate();
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;

        if (pendingRegister) {
            pendingRegister = false;
            // 老存档 / 别的模组放置时可能只有 metadata，没有 bus 配置：用 metadata 兜底
            if (config.getTargetSide() == ForgeDirection.UNKNOWN) {
                int meta = 0;
                try {
                    meta = worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
                } catch (Throwable ignored) {
                    // 保持 UNKNOWN
                }
                if (meta >= 0 && meta < 6) config.setTargetSide(ForgeDirection.getOrientation(meta));
            }
            syncMetadata();
            ensureRegistered();
            return;
        }

        int interval = Math.max(1, Config.storageBusRefreshTicks);
        if (refreshRequested || ++refreshTimer >= interval) {
            refreshRequested = false;
            refreshTimer = 0;
            if (!registered) {
                ensureRegistered();
            } else {
                try {
                    StorageBusRegistry.refresh(link);
                } catch (Throwable t) {
                    FutaGtnhMod.LOG.warn("存储总线：周期刷新失败（{} {} {}），这一轮跳过", xCoord, yCoord, zCoord, t);
                }
            }
        }
    }

    /** 把目标面写进 metadata，客户端据此决定哪一面画「接口」纹理。 */
    private void syncMetadata() {
        if (worldObj == null || worldObj.isRemote) return;
        int meta = config.getTargetSide() == ForgeDirection.UNKNOWN ? 0
            : config.getTargetSide()
                .ordinal();
        try {
            if (worldObj.getBlockMetadata(xCoord, yCoord, zCoord) != meta) {
                worldObj.setBlockMetadataWithNotify(xCoord, yCoord, zCoord, meta, 2);
            }
        } catch (Throwable ignored) {
            // 方块正在被移除时可能失败，不影响存档里的配置
        }
    }

    // ==================================================================
    // 存档
    // ==================================================================

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setTag("bus", config.writeToNbt());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        config.readFromNbt(tag.getCompoundTag("bus"));
        pendingRegister = true;
    }

    // ==================================================================
    // 放置辅助
    // ==================================================================

    /**
     * 选目标面：优先玩家点击的那一面（也就是「贴着放」的方向），
     * 那里不是容器时退而求其次找第一个相邻容器。
     */
    public static ForgeDirection pickTargetSide(World world, int x, int y, int z, ForgeDirection preferred) {
        ForgeDirection fallback = preferred == null || preferred == ForgeDirection.UNKNOWN ? ForgeDirection.DOWN
            : preferred;
        if (isTarget(world, x, y, z, fallback)) return fallback;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (side == fallback) continue;
            if (isTarget(world, x, y, z, side)) return side;
        }
        return fallback;
    }

    private static boolean isTarget(World world, int x, int y, int z, ForgeDirection side) {
        if (world == null || side == null || side == ForgeDirection.UNKNOWN) return false;
        TileEntity tile;
        try {
            if (!world.blockExists(x + side.offsetX, y + side.offsetY, z + side.offsetZ)) return false;
            tile = world.getTileEntity(x + side.offsetX, y + side.offsetY, z + side.offsetZ);
        } catch (Throwable t) {
            return false;
        }
        if (tile == null) return false;
        ForgeDirection face = side.getOpposite();
        return ExternalInventoryOps.isItemTarget(tile, face) || ExternalInventoryOps.isFluidTarget(tile, face);
    }
}
