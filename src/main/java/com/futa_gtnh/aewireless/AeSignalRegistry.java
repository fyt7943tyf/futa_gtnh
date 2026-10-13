package com.futa_gtnh.aewireless;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 服务端全局的发信端注册表：给「接收端选发信端」的列表用。
 *
 * <p>
 * 键是（维度, x, y, z）—— 方块实体的天然身份，改名不影响、也不需要额外 UUID。
 * 值是弱引用：方块实体被卸载后条目自动失效，条目本身在每次遍历时清理。
 *
 * <p>
 * 解析单个发信端不用查这张表（直接按坐标 {@link TileAeSignalTransmitter#resolve}），
 * 表只服务一件事：GUI 的候选列表。
 */
public final class AeSignalRegistry {

    private AeSignalRegistry() {}

    /** 一个发信端的候选列表条目（GUI 用）。 */
    public static final class Entry {

        public final int dim, x, y, z;
        public final String name;
        public final TileAeSignalTransmitter.State state;

        Entry(int dim, int x, int y, int z, String name, TileAeSignalTransmitter.State state) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.state = state;
        }
    }

    private static final class Key {

        final int dim, x, y, z;

        Key(int dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Key other)) return false;
            return dim == other.dim && x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            return dim * 31 + x * 961 + y * 31_013 + z;
        }
    }

    private static final Map<Key, WeakReference<TileAeSignalTransmitter>> TRANSMITTERS = new HashMap<>();

    static void register(TileAeSignalTransmitter tile) {
        if (tile.getWorldObj() == null) return;
        TRANSMITTERS.put(keyOf(tile), new WeakReference<>(tile));
    }

    static void unregister(TileAeSignalTransmitter tile) {
        if (tile.getWorldObj() == null) return;
        TRANSMITTERS.remove(keyOf(tile));
    }

    private static Key keyOf(TileAeSignalTransmitter tile) {
        return new Key(tile.getWorldObj().provider.dimensionId, tile.xCoord, tile.yCoord, tile.zCoord);
    }

    /**
     * 列出对 {@code viewer} 可见的全部发信端（服务端）。
     *
     * <p>
     * 可见 = 方块实体还活着 且 与查看者同队伍（见 {@link AeSignalTeam}）。
     * 名字为空的条目显示占位名，也照样可选 —— 靠坐标就能区分。
     */
    public static List<Entry> listVisible(@Nullable UUID viewer) {
        List<Entry> result = new ArrayList<>();
        TRANSMITTERS.entrySet()
            .removeIf(
                entry -> entry.getValue()
                    .get() == null);
        for (Map.Entry<Key, WeakReference<TileAeSignalTransmitter>> entry : TRANSMITTERS.entrySet()) {
            TileAeSignalTransmitter tile = entry.getValue()
                .get();
            if (tile == null || tile.isInvalid() || tile.getWorldObj() == null || tile.getWorldObj().isRemote) continue;
            if (!AeSignalTeam.sameTeam(viewer, tile.getOwner())) continue;
            Key key = entry.getKey();
            result.add(new Entry(key.dim, key.x, key.y, key.z, displayName(tile), tile.getState()));
        }
        result.sort((a, b) -> {
            int byName = a.name.compareToIgnoreCase(b.name);
            if (byName != 0) return byName;
            if (a.dim != b.dim) return Integer.compare(a.dim, b.dim);
            if (a.x != b.x) return Integer.compare(a.x, b.x);
            if (a.y != b.y) return Integer.compare(a.y, b.y);
            return Integer.compare(a.z, b.z);
        });
        return result;
    }

    private static String displayName(TileAeSignalTransmitter tile) {
        String name = tile.getSignalName();
        return name == null || name.isEmpty() ? "<unnamed>" : name;
    }
}
