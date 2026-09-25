package com.futa_gtnh.client;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 客户端当前地牢的结构证据、访问记录和特殊点位。 */
public final class RoguelikeMapState {

    public static final byte CELL_WALL = 1;
    public static final byte CELL_FLOOR = 1 << 1;
    public static final byte CELL_STAIR = 1 << 2;
    public static final byte CELL_PILLAR = 1 << 3;
    public static final byte CELL_DECORATION = 1 << 4;
    public static final byte CELL_VISITED = 1 << 5;

    private final Floor[] floors = new Floor[RoguelikeMapSource.LEVEL_COUNT];
    private int dimension;
    private int currentLevel;
    private int centerX;
    private int centerZ;
    private boolean initialized;
    private long generation;

    public void reset(int dimension, int centerX, int centerZ) {
        generation++;
        this.dimension = dimension;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.currentLevel = 0;
        this.initialized = true;
        for (int i = 0; i < floors.length; i++) floors[i] = new Floor(i);
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** 返回地图数据的代数，用于让全屏界面识别世界或扫描重置。 */
    public long getGeneration() {
        return generation;
    }

    public int getDimension() {
        return dimension;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public void setCurrentLevel(int currentLevel) {
        this.currentLevel = Math.max(0, Math.min(RoguelikeMapSource.LEVEL_COUNT - 1, currentLevel));
    }

    public int getCenterX() {
        return centerX;
    }

    public int getCenterZ() {
        return centerZ;
    }

    public Floor getFloor(int level) {
        if (level < 0 || level >= floors.length) return null;
        if (floors[level] == null) floors[level] = new Floor(level);
        return floors[level];
    }

    public int getEvidenceCount() {
        int total = 0;
        for (Floor floor : floors) {
            if (floor != null) total += floor.cells.size();
        }
        return total;
    }

    public void merge(int level, int x, int z, byte flags, int score) {
        Floor floor = getFloor(level);
        if (floor != null) floor.merge(x, z, flags, score);
    }

    public void addMarker(int level, Marker marker) {
        Floor floor = getFloor(level);
        if (floor != null) floor.addMarker(marker);
    }

    public void markVisited(int level, int playerX, int playerZ) {
        Floor floor = getFloor(level);
        if (floor == null) return;

        for (int x = playerX - 3; x <= playerX + 3; x++) {
            for (int z = playerZ - 3; z <= playerZ + 3; z++) {
                if ((x - playerX) * (x - playerX) + (z - playerZ) * (z - playerZ) > 12) {
                    continue;
                }
                Cell cell = floor.cells.get(pack(x, z));
                if (cell != null) cell.flags |= CELL_VISITED;
            }
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public static final class Floor {

        private final int level;
        private final Map<Long, Cell> cells = new HashMap<Long, Cell>();
        private final Map<String, Marker> markers = new HashMap<String, Marker>();
        private final Collection<Cell> cellView = Collections.unmodifiableCollection(cells.values());
        private final Collection<Marker> markerView = Collections.unmodifiableCollection(markers.values());
        private Bounds bounds;
        private boolean boundsDirty = true;

        private Floor(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public boolean isEmpty() {
            return cells.isEmpty() && markers.isEmpty();
        }

        public Collection<Cell> getCells() {
            return cellView;
        }

        public Collection<Marker> getMarkers() {
            return markerView;
        }

        public Cell getCell(int x, int z) {
            return cells.get(pack(x, z));
        }

        private void merge(int x, int z, byte flags, int score) {
            long key = pack(x, z);
            Cell cell = cells.get(key);
            if (cell == null) {
                cell = new Cell(x, z);
                cells.put(key, cell);
                boundsDirty = true;
            }
            cell.flags |= flags;
            cell.score = Math.max(cell.score, score);
        }

        private void addMarker(Marker marker) {
            String key = marker.x + ":" + marker.y + ":" + marker.z + ":" + marker.kind + ":" + marker.detail;
            if (!markers.containsKey(key)) {
                markers.put(key, marker);
                boundsDirty = true;
            }
        }

        public boolean hasEvidenceNear(int x, int z, int radius) {
            int radiusSquared = radius * radius;
            for (Cell cell : cells.values()) {
                int dx = cell.x - x;
                int dz = cell.z - z;
                if (dx * dx + dz * dz <= radiusSquared) return true;
            }
            return false;
        }

        public Bounds getBounds() {
            if (!boundsDirty) return bounds;
            if (isEmpty()) {
                bounds = null;
                boundsDirty = false;
                return null;
            }

            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (Cell cell : cells.values()) {
                minX = Math.min(minX, cell.x);
                minZ = Math.min(minZ, cell.z);
                maxX = Math.max(maxX, cell.x);
                maxZ = Math.max(maxZ, cell.z);
            }
            for (Marker marker : markers.values()) {
                minX = Math.min(minX, marker.x);
                minZ = Math.min(minZ, marker.z);
                maxX = Math.max(maxX, marker.x);
                maxZ = Math.max(maxZ, marker.z);
            }
            bounds = new Bounds(minX, minZ, maxX, maxZ);
            boundsDirty = false;
            return bounds;
        }
    }

    public static final class Cell {

        public final int x;
        public final int z;
        private byte flags;
        private int score;

        private Cell(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public byte getFlags() {
            return flags;
        }

        public int getScore() {
            return score;
        }

        public boolean has(byte flag) {
            return (flags & flag) != 0;
        }
    }

    public static final class Marker {

        public final int x;
        public final int y;
        public final int z;
        public final String kind;
        public final String detail;

        public Marker(int x, int y, int z, String kind, String detail) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.detail = detail;
        }
    }

    public static final class Bounds {

        public final int minX;
        public final int minZ;
        public final int maxX;
        public final int maxZ;

        private Bounds(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }
    }
}
