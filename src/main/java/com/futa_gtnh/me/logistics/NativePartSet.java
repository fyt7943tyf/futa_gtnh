package com.futa_gtnh.me.logistics;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.me.PublicMeNetwork;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.AEApi;
import appeng.api.exceptions.FailedConnection;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.parts.AEBasePart;

/** Native nodes, NBT and remote connections. No inventory scans or transfer implementation lives here. */
final class NativePartSet {

    private final TileEntity tile;
    private final Map<String, AEBasePart> parts = new LinkedHashMap<>();
    private final Map<String, IGridConnection> connections = new LinkedHashMap<>();
    private NBTTagCompound saved = new NBTTagCompound();

    NativePartSet(TileEntity tile) {
        this.tile = tile;
    }

    <T extends AEBasePart> T add(String id, ForgeDirection side, T part) {
        if (parts.containsKey(id)) throw new IllegalStateException("Duplicate native part: " + id);
        if (!SharedStorageManager.isLoaded()) throw new IllegalStateException("Native storage is not loaded");
        // Persist the downgrade guard before any native worker is connected or allowed to operate.
        SharedStorageManager.requireNativeLogisticsGuard();
        new NativePartHost(tile, side, part);
        // Legacy wireless IO has never consumed one of the public cable ports' channels.
        // Ordinary player-placed AE parts retain their original flags and channel requirements.
        part.getProxy()
            .setFlags();
        part.getProxy()
            .setValidSides(EnumSet.noneOf(ForgeDirection.class));
        if (saved.hasKey(id, 10)) part.readFromNBT(saved.getCompoundTag(id));
        parts.put(id, part);
        part.addToWorld();
        return part;
    }

    boolean contains(String id) {
        return parts.containsKey(id);
    }

    void remove(String id) {
        IGridConnection connection = connections.remove(id);
        if (connection != null) connection.destroy();
        AEBasePart part = parts.remove(id);
        if (part != null) {
            NBTTagCompound tag = new NBTTagCompound();
            part.writeToNBT(tag);
            saved.setTag(id, tag);
            part.removeFromWorld();
        }
    }

    void refresh() {
        IGridNode remote = PublicMeNetwork.core() == null ? null
            : PublicMeNetwork.core()
                .getActionableNode();
        for (Map.Entry<String, AEBasePart> entry : parts.entrySet()) {
            String id = entry.getKey();
            IGridNode local = entry.getValue()
                .getActionableNode();
            IGridConnection previous = connections.get(id);
            if (previous != null && (remote == null || local == null
                || !((previous.a() == local && previous.b() == remote)
                    || (previous.b() == local && previous.a() == remote)))) {
                previous.destroy();
                connections.remove(id);
                previous = null;
            }
            if (previous == null && local != null && remote != null && PublicMeNetwork.isAttachable()) {
                try {
                    connections.put(
                        id,
                        AEApi.instance()
                            .createGridConnection(local, remote));
                } catch (FailedConnection failure) { /* Retry after the public grid is ready. */ }
            }
        }
    }

    void close() {
        for (String id : parts.keySet()
            .toArray(new String[0])) remove(id);
    }

    NBTTagCompound write() {
        NBTTagCompound tag = (NBTTagCompound) saved.copy();
        for (Map.Entry<String, AEBasePart> entry : parts.entrySet()) {
            NBTTagCompound state = new NBTTagCompound();
            entry.getValue()
                .writeToNBT(state);
            tag.setTag(entry.getKey(), state);
        }
        return tag;
    }

    void read(NBTTagCompound tag) {
        close();
        saved = (NBTTagCompound) tag.copy();
    }
}
