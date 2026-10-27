package com.futa_gtnh.me.logistics;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.me.PublicMeNetwork;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.storagebus.StorageBusConfig;
import com.futa_gtnh.storagebus.TileEntityStorageBus;
import com.glodblock.github.common.parts.PartFluidStorageBus;
import com.glodblock.github.loader.ItemAndBlockHolder;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.IncludeExclude;
import appeng.api.config.Settings;
import appeng.api.config.StorageFilter;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.MEInventoryHandler;
import appeng.parts.misc.PartStorageBus;
import appeng.util.IterationCounter;
import appeng.util.prioitylist.IPartitionList;

/** Real native storage buses keep external-adaptor selection, monitoring, caching and grid routing. */
public final class NativeStorageRuntime {

    private final TileEntityStorageBus tile;
    private final NativePartSet parts;
    private final PartStorageBus[][] buses = new PartStorageBus[6][2];
    private NBTTagCompound original;
    private NBTTagCompound frozen;
    private String failure = "";

    public NativeStorageRuntime(TileEntityStorageBus tile) {
        this.tile = tile;
        parts = new NativePartSet(tile);
    }

    public void update() {
        if (!failure.isEmpty()) {
            close();
            return;
        }
        if (!failure.isEmpty() || tile.getWorldObj() == null
            || tile.getWorldObj().isRemote
            || !PublicMeNetwork.isAttachable()) return;
        try {
            if (original == null) {
                original = new NBTTagCompound();
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                    StorageBusConfig config = tile.getConfig(side);
                    if (config != null) original.setTag(Integer.toString(side.ordinal()), config.writeToNbt());
                }
                tile.markDirty();
            }
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                StorageBusConfig config = tile.getConfig(side);
                boolean targetAllowed = true;
                if (loaded(side)) targetAllowed = mayExpose(
                    tile.getWorldObj()
                        .getTileEntity(
                            tile.xCoord + side.offsetX,
                            tile.yCoord + side.offsetY,
                            tile.zCoord + side.offsetZ));
                for (int kind = 0; kind < 2; kind++) {
                    String id = side.ordinal() + ":" + kind;
                    if ((config == null || !targetAllowed) && buses[side.ordinal()][kind] != null) {
                        NativeBusDedup.remove(buses[side.ordinal()][kind]);
                        parts.remove(id);
                        buses[side.ordinal()][kind] = null;
                    } else if (config != null && targetAllowed && buses[side.ordinal()][kind] == null) {
                        PartStorageBus part = kind == 0 ? new Items(side) : new Fluids(side);
                        buses[side.ordinal()][kind] = parts.add(id, side, part);
                        configure(part, config);
                    }
                    if (buses[side.ordinal()][kind] != null) NativeBusDedup.update(buses[side.ordinal()][kind], config);
                }
            }
            parts.refresh();
        } catch (RuntimeException | LinkageError error) {
            pause(error);
        }
    }

    public void changed(ForgeDirection side) {
        if (side == null || side == ForgeDirection.UNKNOWN) return;
        StorageBusConfig config = tile.getConfig(side);
        for (PartStorageBus part : buses[side.ordinal()]) if (part != null && config != null) configure(part, config);
    }

    public void neighborsChanged() {
        for (PartStorageBus[] face : buses) for (PartStorageBus part : face) if (part != null) part.onNeighborChanged();
    }

    private void configure(PartStorageBus part, StorageBusConfig config) {
        AccessRestriction access = config.getAccess()
            .canRead()
                ? config.getAccess()
                    .canWrite() ? AccessRestriction.READ_WRITE : AccessRestriction.READ
                : AccessRestriction.WRITE;
        part.getConfigManager()
            .putSetting(Settings.ACCESS, access);
        part.getConfigManager()
            .putSetting(
                Settings.STORAGE_FILTER,
                config.getAccess()
                    .canRead() ? StorageFilter.NONE : StorageFilter.EXTRACTABLE_ONLY);
        part.setPriority(config.getPriority()); // Native cache invalidation + grid cell-array event.
    }

    static boolean mayExpose(net.minecraft.tileentity.TileEntity target) {
        return !(target instanceof com.futa_gtnh.block.TileEntitySharedTerminal
            || target instanceof com.futa_gtnh.block.TileEntityIoNode
            || target instanceof TileEntityStorageBus);
    }

    private boolean loaded(ForgeDirection side) {
        int x = tile.xCoord + side.offsetX, z = tile.zCoord + side.offsetZ;
        return tile.getWorldObj() != null && tile.getWorldObj()
            .getChunkProvider()
            .chunkExists(x >> 4, z >> 4);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private MEInventoryHandler policy(MEInventoryHandler handler, ForgeDirection side, boolean fluid) {
        if (handler == null) return null;
        if (!com.futa_gtnh.ae2.AeSupplyTracking.isTrackingHandler(handler)) {
            fault(
                new IllegalStateException(
                    "Native storage-bus tracking hook unavailable; refusing an unguarded provider"));
            return null;
        }
        StorageBusConfig config = tile.getConfig(side);
        if (config == null) return null;
        if (handler.getPartitionList() instanceof LegacyPartition) return handler;
        IPartitionList partition = new LegacyPartition(handler, config, fluid);
        handler.setWhitelist(IncludeExclude.WHITELIST);
        handler.setPartitionList(partition);
        handler.setExtractPartitionList(partition);
        handler.setIsExtractFilterActive(true);
        handler.setPriority(config.getPriority());
        return handler;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private final class LegacyPartition implements IPartitionList {

        private final MEInventoryHandler handler;
        private final StorageBusConfig config;
        private final boolean fluid;

        LegacyPartition(MEInventoryHandler handler, StorageBusConfig config, boolean fluid) {
            this.handler = handler;
            this.config = config;
            this.fluid = fluid;
        }

        @Override
        public boolean isListed(IAEStack stack) {
            return fluid ? config.allowsFluid(FluidKey.fromNative((IAEFluidStack) stack))
                : config.allowsItem(ItemKey.fromNative((IAEItemStack) stack));
        }

        @Override
        public boolean isEmpty() {
            return fluid ? !config.hasFluidFilters() : !config.hasItemFilters();
        }

        @Override
        public Iterable getItems() {
            // This is an AE filter hint, never a mirrored inventory. Expand legacy fuzzy/ore filters with native
            // keys.
            List<IAEStack> matching = new ArrayList<>();
            for (Object candidate : handler.getInternal()
                .getAvailableItems(
                    handler.getStackType()
                        .createList(),
                    IterationCounter.fetchNewId())) {
                IAEStack stack = (IAEStack) candidate;
                if (isListed(stack)) matching.add(stack);
            }
            return matching;
        }
    }

    public void fault(Throwable error) {
        failure = error.toString();
        tile.markDirty();
        FutaGtnhMod.LOG.error("原生 AE 存储总线回调结果不明，已隔离并保留托管：{} {} {}", tile.xCoord, tile.yCoord, tile.zCoord, error);
    }

    private void pause(Throwable error) {
        failure = error.toString();
        tile.markDirty();
        FutaGtnhMod.LOG.error("原生 AE 兼容存储总线已暂停：{} {} {}", tile.xCoord, tile.yCoord, tile.zCoord, error);
        close();
    }

    public String failure() {
        return failure;
    }

    public PartStorageBus part(ForgeDirection side, boolean fluid) {
        return buses[side.ordinal()][fluid ? 1 : 0];
    }

    public void close() {
        for (PartStorageBus[] face : buses)
            for (PartStorageBus part : face) if (part != null) NativeBusDedup.remove(part);
        parts.close();
        for (PartStorageBus[] face : buses) {
            face[0] = null;
            face[1] = null;
        }
    }

    public void write(NBTTagCompound tileTag) {
        if (frozen != null) {
            tileTag.setTag("nativeStorageBus", frozen.copy());
            return;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("parts", parts.write());
        if (original != null) tag.setTag("legacyConfig", original.copy());
        if (!failure.isEmpty()) tag.setString("paused", failure);
        tileTag.setTag("nativeStorageBus", NativeLogisticsNbt.seal(tag));
    }

    public void read(NBTTagCompound tileTag) {
        close();
        NBTTagCompound tag = tileTag.getCompoundTag("nativeStorageBus");
        try {
            NativeLogisticsNbt.validate(tag);
        } catch (IllegalStateException error) {
            frozen = (NBTTagCompound) tag.copy();
            failure = error.toString();
            return;
        }
        frozen = null;
        parts.read(tag.getCompoundTag("parts"));
        original = tag.hasKey("legacyConfig", 10) ? (NBTTagCompound) tag.getCompoundTag("legacyConfig")
            .copy() : null;
        failure = tag.getString("paused");
    }

    private final class Items extends PartStorageBus {

        Items(ForgeDirection side) {
            super(
                AEApi.instance()
                    .definitions()
                    .parts()
                    .storageBus()
                    .maybeStack(1)
                    .get());
        }

        @Override
        public MEInventoryHandler getInternalHandler() {
            return failure.isEmpty() && loaded(getSide()) ? policy(super.getInternalHandler(), getSide(), false) : null;
        }

        @Override
        public void postChange(IBaseMonitor<IAEStack<?>> monitor, Iterable<IAEStack<?>> change,
            BaseActionSource source) {
            if (!NativeBusDedup.rescanInsteadOfDelta(this)) super.postChange(monitor, change, source);
        }

        @Override
        public void securityBreak() {
            pause(new IllegalStateException("Native storage bus security break"));
        }
    }

    private final class Fluids extends PartFluidStorageBus {

        Fluids(ForgeDirection side) {
            super(new ItemStack(ItemAndBlockHolder.FLUID_STORAGE_BUS));
        }

        @Override
        public MEInventoryHandler getInternalHandler() {
            return failure.isEmpty() && loaded(getSide()) ? policy(super.getInternalHandler(), getSide(), true) : null;
        }

        @Override
        public void postChange(IBaseMonitor<IAEStack<?>> monitor, Iterable<IAEStack<?>> change,
            BaseActionSource source) {
            if (!NativeBusDedup.rescanInsteadOfDelta(this)) super.postChange(monitor, change, source);
        }

        @Override
        public void securityBreak() {
            pause(new IllegalStateException("Native storage bus security break"));
        }
    }
}
