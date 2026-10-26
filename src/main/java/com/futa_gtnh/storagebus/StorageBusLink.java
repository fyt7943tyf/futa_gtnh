package com.futa_gtnh.storagebus;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 一条已放置的存储总线的运行时状态：目标容器、内容镜像、按需读写。
 *
 * <p>
 * 镜像（{@link #itemCache} / {@link #fluidCache}）只用来<b>展示和聚合</b>，
 * 真正抽取时永远现场去问容器；所以镜像短暂过期最多让界面数字慢半拍，
 * 不会让共享存储凭空多出东西。反过来，写入时也只按容器实际接受的量记账。
 */
public final class StorageBusLink {

    private final TileEntityStorageBus bus;
    private final ForgeDirection side;

    private LinkedHashMap<ItemKey, Long> itemCache = new LinkedHashMap<>();
    private LinkedHashMap<FluidKey, Long> fluidCache = new LinkedHashMap<>();
    private int mirrorRevision;

    StorageBusLink(TileEntityStorageBus bus, ForgeDirection side) {
        this.bus = bus;
        this.side = side;
    }

    public TileEntityStorageBus getBus() {
        return bus;
    }

    public StorageBusConfig getConfig() {
        return bus.getConfig(side);
    }

    public boolean isReadable() {
        return getConfig().getAccess()
            .canRead();
    }

    public boolean isWritable() {
        return getConfig().getAccess()
            .canWrite();
    }

    public ForgeDirection getTargetSide() {
        return side;
    }

    /** @return 目标方块实体；目标面无效或区块未加载时为 null */
    public TileEntity targetTile() {
        if (bus == null || bus.getWorldObj() == null || bus.isInvalid()) return null;
        if (side == null || side == ForgeDirection.UNKNOWN) return null;
        try {
            int x = bus.xCoord + side.offsetX;
            int z = bus.zCoord + side.offsetZ;
            // 只在目标区块已经加载时查方块实体：bus 在区块边界上时，
            // getTileEntity 会触发一次同步区块加载，把相邻区块硬拉进来。
            if (!bus.getWorldObj()
                .blockExists(x, bus.yCoord + side.offsetY, z)) return null;
            return bus.getWorldObj()
                .getTileEntity(x, bus.yCoord + side.offsetY, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return 目标朝向目标容器的那一面（Forge 的接口要的是「从哪一面来的」） */
    public ForgeDirection targetFace() {
        ForgeDirection side = getTargetSide();
        return side == null || side == ForgeDirection.UNKNOWN ? ForgeDirection.UNKNOWN : side.getOpposite();
    }

    public boolean hasItemTarget() {
        TileEntity tile = targetTile();
        return tile != null && ExternalInventoryOps.isItemTarget(tile, targetFace());
    }

    public boolean hasFluidTarget() {
        TileEntity tile = targetTile();
        return tile != null && ExternalInventoryOps.isFluidTarget(tile, targetFace());
    }

    /** @return 目标方块注册名；没有目标时返回空串（界面显示用） */
    public String getTargetName() {
        if (bus == null || bus.getWorldObj() == null) return "";
        ForgeDirection side = getTargetSide();
        if (side == null || side == ForgeDirection.UNKNOWN) return "";
        try {
            int x = bus.xCoord + side.offsetX;
            int y = bus.yCoord + side.offsetY;
            int z = bus.zCoord + side.offsetZ;
            if (!bus.getWorldObj()
                .blockExists(x, y, z)) return "";
            Block block = bus.getWorldObj()
                .getBlock(x, y, z);
            if (block == null || block == net.minecraft.init.Blocks.air) return "";
            String name = Block.blockRegistry.getNameForObject(block);
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================================================================
    // 镜像
    // ==================================================================

    /** 现场扫描一次目标容器，替换内容镜像。 */
    public void refresh() {
        LinkedHashMap<ItemKey, Long> items = scanItems();
        LinkedHashMap<FluidKey, Long> fluids = scanFluids();
        synchronized (this) {
            if (!itemCache.equals(items) || !fluidCache.equals(fluids)) mirrorRevision++;
            itemCache = items;
            fluidCache = fluids;
        }
    }

    private LinkedHashMap<ItemKey, Long> scanItems() {
        LinkedHashMap<ItemKey, Long> result = new LinkedHashMap<>();
        TileEntity tile = targetTile();
        if (!(tile instanceof IInventory) || !ExternalInventoryOps.isItemTarget(tile, targetFace())) return result;

        IInventory inventory = (IInventory) tile;
        LinkedHashMap<ItemKey, Long> drawers = DrawerStorageCompat.snapshot(inventory);
        if (drawers != null) {
            for (Map.Entry<ItemKey, Long> entry : drawers.entrySet()) {
                if (getConfig().allowsItem(entry.getKey())) result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }
        int size;
        try {
            size = inventory.getSizeInventory();
        } catch (Throwable t) {
            return result;
        }
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack;
            try {
                stack = inventory.getStackInSlot(slot);
            } catch (Throwable t) {
                continue;
            }
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) continue;
            ItemKey key = ItemKey.of(stack);
            if (key == null || !getConfig().allowsItem(key)) continue;
            merge(result, key, stack.stackSize);
        }
        return result;
    }

    private LinkedHashMap<FluidKey, Long> scanFluids() {
        LinkedHashMap<FluidKey, Long> result = new LinkedHashMap<>();
        TileEntity tile = targetTile();
        if (!(tile instanceof IFluidHandler) || !ExternalInventoryOps.isFluidTarget(tile, targetFace())) return result;

        FluidTankInfo[] tanks;
        try {
            tanks = ((IFluidHandler) tile).getTankInfo(targetFace());
        } catch (Throwable t) {
            return result;
        }
        if (tanks == null) return result;
        for (FluidTankInfo tank : tanks) {
            if (tank == null || tank.fluid == null || tank.fluid.getFluid() == null || tank.fluid.amount <= 0) continue;
            FluidKey key = FluidKey.of(tank.fluid);
            if (key == null || !getConfig().allowsFluid(key)) continue;
            merge(result, key, tank.fluid.amount);
        }
        return result;
    }

    /** @return 物品镜像的只读副本（调用方可以安全遍历） */
    public synchronized List<Map.Entry<ItemKey, Long>> itemSnapshot() {
        List<Map.Entry<ItemKey, Long>> result = new ArrayList<>(itemCache.size());
        for (Map.Entry<ItemKey, Long> entry : itemCache.entrySet()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        return result;
    }

    /** @return 流体镜像的只读副本（调用方可以安全遍历） */
    public synchronized List<Map.Entry<FluidKey, Long>> fluidSnapshot() {
        List<Map.Entry<FluidKey, Long>> result = new ArrayList<>(fluidCache.size());
        for (Map.Entry<FluidKey, Long> entry : fluidCache.entrySet()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        return result;
    }

    public synchronized long cachedItemAmount(ItemKey key) {
        Long amount = key == null ? null : itemCache.get(key);
        return amount == null ? 0L : amount.longValue();
    }

    public synchronized long cachedFluidAmount(FluidKey key) {
        Long amount = key == null ? null : fluidCache.get(key);
        return amount == null ? 0L : amount.longValue();
    }

    public synchronized void clearCache() {
        if (!itemCache.isEmpty() || !fluidCache.isEmpty()) mirrorRevision++;
        itemCache = new LinkedHashMap<>();
        fluidCache = new LinkedHashMap<>();
    }

    public synchronized int getMirrorRevision() {
        return mirrorRevision;
    }

    // ==================================================================
    // 按需读写
    // ==================================================================

    public long simulateInsertItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L || !isWritable() || !getConfig().allowsItem(key)) return 0L;
        TileEntity tile = targetTile();
        return tile instanceof IInventory && ExternalInventoryOps.isItemTarget(tile, targetFace())
            ? ExternalInventoryOps.simulateInsertItems((IInventory) tile, targetFace(), key, amount)
            : 0L;
    }

    public long simulateExtractItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L || !isReadable() || !getConfig().allowsItem(key)) return 0L;
        TileEntity tile = targetTile();
        return tile instanceof IInventory && ExternalInventoryOps.isItemTarget(tile, targetFace())
            ? ExternalInventoryOps.simulateExtractItems((IInventory) tile, targetFace(), key, amount)
            : 0L;
    }

    public long simulateInsertFluid(FluidKey key, long amount) {
        if (key == null || amount <= 0L || !isWritable() || !getConfig().allowsFluid(key)) return 0L;
        TileEntity tile = targetTile();
        return tile instanceof IFluidHandler && ExternalInventoryOps.isFluidTarget(tile, targetFace())
            ? ExternalInventoryOps.simulateInsertFluid((IFluidHandler) tile, targetFace(), key, amount)
            : 0L;
    }

    public long simulateExtractFluid(FluidKey key, long amount) {
        if (key == null || amount <= 0L || !isReadable() || !getConfig().allowsFluid(key)) return 0L;
        TileEntity tile = targetTile();
        return tile instanceof IFluidHandler && ExternalInventoryOps.isFluidTarget(tile, targetFace())
            ? ExternalInventoryOps.simulateExtractFluid((IFluidHandler) tile, targetFace(), key, amount)
            : 0L;
    }

    /** @return 容器实际接受的物品数量 */
    public long insertItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L || !isWritable() || !getConfig().allowsItem(key)) return 0L;
        TileEntity tile = targetTile();
        if (!(tile instanceof IInventory) || !ExternalInventoryOps.isItemTarget(tile, targetFace())) return 0L;
        long accepted = ExternalInventoryOps.insertItems((IInventory) tile, targetFace(), key, amount);
        if (accepted > 0L) adjustItemCache(key, accepted);
        return accepted;
    }

    /** @return 容器实际被取走的物品数量 */
    public long extractItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L || !isReadable() || !getConfig().allowsItem(key)) return 0L;
        TileEntity tile = targetTile();
        if (!(tile instanceof IInventory) || !ExternalInventoryOps.isItemTarget(tile, targetFace())) return 0L;
        long taken = ExternalInventoryOps.extractItems((IInventory) tile, targetFace(), key, amount);
        if (taken > 0L) adjustItemCache(key, -taken);
        return taken;
    }

    /** @return 容器实际接受的流体 mB */
    public long insertFluid(FluidKey key, long amount) {
        if (key == null || amount <= 0L || !isWritable() || !getConfig().allowsFluid(key)) return 0L;
        TileEntity tile = targetTile();
        if (!(tile instanceof IFluidHandler) || !ExternalInventoryOps.isFluidTarget(tile, targetFace())) return 0L;
        long remaining = amount;
        long accepted = 0L;
        while (remaining > 0L) {
            int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
            long moved = ExternalInventoryOps.insertFluid((IFluidHandler) tile, targetFace(), key, chunk);
            if (moved <= 0L) break;
            accepted += moved;
            remaining -= moved;
            if (moved < chunk) break;
        }
        if (accepted > 0L) adjustFluidCache(key, accepted);
        return accepted;
    }

    /** @return 容器实际被抽走的流体 mB */
    public long extractFluid(FluidKey key, long amount) {
        if (key == null || amount <= 0L || !isReadable() || !getConfig().allowsFluid(key)) return 0L;
        TileEntity tile = targetTile();
        if (!(tile instanceof IFluidHandler) || !ExternalInventoryOps.isFluidTarget(tile, targetFace())) return 0L;
        long remaining = amount;
        long taken = 0L;
        while (remaining > 0L) {
            int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
            long moved = ExternalInventoryOps.extractFluid((IFluidHandler) tile, targetFace(), key, chunk);
            if (moved <= 0L) break;
            taken += moved;
            remaining -= moved;
            if (moved < chunk) break;
        }
        if (taken > 0L) adjustFluidCache(key, -taken);
        return taken;
    }

    private synchronized void adjustItemCache(ItemKey key, long delta) {
        if (delta == 0L) return;
        mirrorRevision++;
        long current = cachedItemAmount(key);
        long next = delta > 0L ? saturatingAdd(current, delta) : Math.max(0L, current + delta);
        if (next <= 0L) {
            itemCache.remove(key);
        } else {
            itemCache.put(key, Long.valueOf(next));
        }
    }

    private synchronized void adjustFluidCache(FluidKey key, long delta) {
        if (delta == 0L) return;
        mirrorRevision++;
        long current = cachedFluidAmount(key);
        long next = delta > 0L ? saturatingAdd(current, delta) : Math.max(0L, current + delta);
        if (next <= 0L) {
            fluidCache.remove(key);
        } else {
            fluidCache.put(key, Long.valueOf(next));
        }
    }

    private static <K> void merge(Map<K, Long> map, K key, long amount) {
        Long existing = map.get(key);
        map.put(key, Long.valueOf(saturatingAdd(existing == null ? 0L : existing.longValue(), amount)));
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0L ? Long.MAX_VALUE : sum;
    }

    /** 调试 / GUI 用：目标方块是否已经不再是一个可读写容器。 */
    public boolean isTargetMissing() {
        TileEntity tile = targetTile();
        return tile == null || (!hasItemTarget() && !hasFluidTarget());
    }
}
