package com.futa_gtnh.me.logistics;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.storagebus.StorageBusConfig;

import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.GridAccessException;
import appeng.me.storage.MEInventoryHandler;
import appeng.parts.misc.PartStorageBus;

/** Enumeration policy only: old multiple links to one tile expose the maximum per-key quantity once. */
public final class NativeBusDedup {

    private static final Map<PartStorageBus, Entry> ENTRIES = new IdentityHashMap<>();
    private static final Map<TileEntity, Set<Entry>> TARGETS = new IdentityHashMap<>();

    private NativeBusDedup() {}

    private static final class Entry {

        final PartStorageBus part;
        final StorageBusConfig config;
        TileEntity target;

        Entry(PartStorageBus part, StorageBusConfig config) {
            this.part = part;
            this.config = config;
        }
    }

    static void update(PartStorageBus part, StorageBusConfig config) {
        Entry entry = ENTRIES.computeIfAbsent(part, ignored -> new Entry(part, config));
        TileEntity host = part.getHost()
            .getTile();
        ForgeDirection side = part.getSide();
        int x = host.xCoord + side.offsetX, z = host.zCoord + side.offsetZ;
        TileEntity target = host.getWorldObj()
            .getChunkProvider()
            .chunkExists(x >> 4, z >> 4)
                ? host.getWorldObj()
                    .getTileEntity(x, host.yCoord + side.offsetY, z)
                : null;
        if (entry.target == target) return;
        detach(entry);
        entry.target = target;
        if (target != null)
            TARGETS.computeIfAbsent(target, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(entry);
    }

    static void remove(PartStorageBus part) {
        Entry entry = ENTRIES.remove(part);
        if (entry != null) detach(entry);
    }

    private static void detach(Entry entry) {
        Set<Entry> group = TARGETS.get(entry.target);
        if (group != null) {
            group.remove(entry);
            if (group.isEmpty()) TARGETS.remove(entry.target);
        }
        entry.target = null;
    }

    public static void clear() {
        ENTRIES.clear();
        TARGETS.clear();
    }

    public static boolean multiple(PartStorageBus part) {
        Entry entry = ENTRIES.get(part);
        if (entry == null) return false;
        Set<Entry> group = TARGETS.get(entry.target);
        if (group == null) return false;
        for (Entry other : group) if (other != entry && other.part.getStackType() == part.getStackType()) return true;
        return false;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static boolean visible(PartStorageBus part, IAEStack<?> local, int iteration) {
        if (!multiple(part) || local == null) return true;
        Entry entry = ENTRIES.get(part);
        for (Entry other : TARGETS.get(entry.target)) {
            if (other == entry || other.part.getStackType() != part.getStackType()
                || !other.config.getAccess()
                    .canRead()
                || !other.part.getProxy()
                    .isActive())
                continue;
            boolean allowed = local instanceof IAEItemStack item ? other.config.allowsItem(ItemKey.fromNative(item))
                : other.config.allowsFluid(FluidKey.fromNative((IAEFluidStack) local));
            if (!allowed) continue;
            MEInventoryHandler handler = other.part.getInternalHandler();
            if (handler == null) continue;
            IAEStack available = handler.getInternal()
                .getAvailableItem(local, iteration);
            if (available != null && (available.getStackSize() > local.getStackSize()
                || available.getStackSize() == local.getStackSize() && order(other.part, part) < 0)) return false;
        }
        return true;
    }

    private static int order(PartStorageBus a, PartStorageBus b) {
        TileEntity first = a.getHost()
            .getTile(),
            second = b.getHost()
                .getTile();
        int value = Integer.compare(first.xCoord, second.xCoord);
        if (value != 0) return value;
        value = Integer.compare(first.yCoord, second.yCoord);
        if (value != 0) return value;
        value = Integer.compare(first.zCoord, second.zCoord);
        if (value != 0) return value;
        return Integer.compare(
            a.getSide()
                .ordinal(),
            b.getSide()
                .ordinal());
    }

    /** Duplicate native monitor callbacks cannot be summed: invalidate and let native enumeration rebuild once. */
    static boolean rescanInsteadOfDelta(PartStorageBus part) {
        if (!multiple(part)) return false;
        try {
            part.getProxy()
                .getGrid()
                .postEvent(new MENetworkCellArrayUpdate());
        } catch (GridAccessException detached) {}
        return true;
    }
}
