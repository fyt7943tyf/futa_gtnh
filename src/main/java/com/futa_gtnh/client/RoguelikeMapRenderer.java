package com.futa_gtnh.client;

import java.util.Collection;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/** 地牢地图的二维绘制工具，供全屏地图和悬浮小地图共用。 */
public final class RoguelikeMapRenderer {

    private RoguelikeMapRenderer() {}

    public static View draw(RoguelikeMapState.Floor floor, int playerX, int playerZ, int left, int top, int width,
        int height, boolean fitAll, boolean drawGrid) {

        Gui.drawRect(left, top, left + width, top + height, 0xD0101010);
        if (floor == null) return null;

        if (floor.isEmpty()) return null;

        RoguelikeMapState.Bounds bounds = fitAll ? floor.getBounds() : null;
        if (fitAll && bounds == null) return null;

        int minX;
        int minZ;
        int maxX;
        int maxZ;
        if (fitAll) {
            minX = bounds.minX - 2;
            minZ = bounds.minZ - 2;
            maxX = bounds.maxX + 2;
            maxZ = bounds.maxZ + 2;
        } else {
            int radius = Math.max(24, Math.min(52, Math.min(width, height) / 3));
            minX = playerX - radius;
            minZ = playerZ - radius;
            maxX = playerX + radius;
            maxZ = playerZ + radius;
        }

        int mapWidth = maxX - minX + 1;
        int mapHeight = maxZ - minZ + 1;
        double scale = Math.min((double) (width - 4) / mapWidth, (double) (height - 4) / mapHeight);
        // 全屏地图必须允许缩小，否则探索范围变大后地图会超出绘制区域，看起来像空白。
        // 悬浮小地图仍保持至少一个像素对应一个方块，避免近距离视图过度缩小。
        if (!fitAll && scale < 1.0D) scale = 1.0D;
        int renderedWidth = (int) Math.ceil(mapWidth * scale);
        int renderedHeight = (int) Math.ceil(mapHeight * scale);
        int mapLeft = left + Math.max(2, (width - renderedWidth) / 2);
        int mapTop = top + Math.max(2, (height - renderedHeight) / 2);

        drawMapBatch(
            floor,
            playerX,
            playerZ,
            minX,
            minZ,
            maxX,
            maxZ,
            mapLeft,
            mapTop,
            scale,
            renderedWidth,
            renderedHeight,
            drawGrid);

        return new View(minX, minZ, maxX, maxZ, mapLeft, mapTop, scale, renderedWidth, renderedHeight);
    }

    /** 使用一个顶点批次绘制地图，避免每个格子都触发一次 GUI 绘制提交。 */
    private static void drawMapBatch(RoguelikeMapState.Floor floor, int playerX, int playerZ, int minX, int minZ,
        int maxX, int maxZ, int mapLeft, int mapTop, double scale, int renderedWidth, int renderedHeight,
        boolean drawGrid) {

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        try {
            if (drawGrid && scale >= 2.0D) {
                for (int x = minX; x <= maxX; x += 16) {
                    int screenX = mapLeft + (int) ((x - minX) * scale);
                    addQuad(tessellator, screenX, mapTop, screenX + 1, mapTop + renderedHeight, 0x30202020);
                }
                for (int z = minZ; z <= maxZ; z += 16) {
                    int screenZ = mapTop + (int) ((z - minZ) * scale);
                    addQuad(tessellator, mapLeft, screenZ, mapLeft + renderedWidth, screenZ + 1, 0x30202020);
                }
            }

            for (RoguelikeMapState.Cell cell : floor.getCells()) {
                if (cell.x < minX || cell.x > maxX || cell.z < minZ || cell.z > maxZ) continue;
                int x0 = mapLeft + (int) ((cell.x - minX) * scale);
                int z0 = mapTop + (int) ((cell.z - minZ) * scale);
                int x1 = Math.max(x0 + 1, mapLeft + (int) ((cell.x - minX + 1) * scale));
                int z1 = Math.max(z0 + 1, mapTop + (int) ((cell.z - minZ + 1) * scale));
                addQuad(tessellator, x0, z0, x1, z1, cellColor(cell));
            }

            for (RoguelikeMapState.Marker marker : floor.getMarkers()) {
                if (marker.x < minX || marker.x > maxX || marker.z < minZ || marker.z > maxZ) continue;
                int x0 = mapLeft + (int) ((marker.x - minX) * scale);
                int z0 = mapTop + (int) ((marker.z - minZ) * scale);
                int size = Math.max(3, (int) Math.ceil(scale * 0.8D));
                int color = marker.kind.contains("刷怪") ? 0xFFFF4444 : 0xFFFFC928;
                addQuad(tessellator, x0, z0, x0 + size, z0 + size, color);
                addQuad(tessellator, x0 + 1, z0 + 1, x0 + size - 1, z0 + size - 1, 0xFF202020);
            }

            int playerMarkerX = mapLeft + (int) ((playerX - minX) * scale);
            int playerMarkerZ = mapTop + (int) ((playerZ - minZ) * scale);
            addQuad(
                tessellator,
                playerMarkerX - 2,
                playerMarkerZ - 2,
                playerMarkerX + 3,
                playerMarkerZ + 3,
                0xFFFFFFFF);
            addQuad(
                tessellator,
                playerMarkerX - 1,
                playerMarkerZ - 1,
                playerMarkerX + 2,
                playerMarkerZ + 2,
                0xFF28C8FF);
        } finally {
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void addQuad(Tessellator tessellator, int x0, int y0, int x1, int y1, int color) {
        int alpha = (color >>> 24) & 255;
        int red = (color >>> 16) & 255;
        int green = (color >>> 8) & 255;
        int blue = color & 255;
        tessellator.setColorRGBA_F(red / 255.0F, green / 255.0F, blue / 255.0F, alpha / 255.0F);
        tessellator.addVertex(x0, y1, 0.0D);
        tessellator.addVertex(x1, y1, 0.0D);
        tessellator.addVertex(x1, y0, 0.0D);
        tessellator.addVertex(x0, y0, 0.0D);
    }

    private static int cellColor(RoguelikeMapState.Cell cell) {
        boolean visited = cell.has(RoguelikeMapState.CELL_VISITED);
        if (cell.has(RoguelikeMapState.CELL_STAIR)) return visited ? 0xFFE7B94A : 0xFF80652E;
        if (cell.has(RoguelikeMapState.CELL_PILLAR)) return visited ? 0xFFB58B5E : 0xFF5A4733;
        if (cell.has(RoguelikeMapState.CELL_WALL)) return visited ? 0xFFBFC6CE : 0xFF555C66;
        if (cell.has(RoguelikeMapState.CELL_FLOOR)) return visited ? 0xFF8CB6A0 : 0xFF3D5B4D;
        return visited ? 0xFF8674A8 : 0xFF403653;
    }

    public static void drawLegend(FontRenderer font, int x, int y) {
        font.drawStringWithShadow("■ 墙/边界", x, y, 0xFFBFC6CE);
        font.drawStringWithShadow("■ 地面", x + 72, y, 0xFF8CB6A0);
        font.drawStringWithShadow("■ 楼梯", x + 120, y, 0xFFE7B94A);
        font.drawStringWithShadow("■ 箱子", x + 172, y, 0xFFFFC928);
        font.drawStringWithShadow("■ 刷怪箱", x + 224, y, 0xFFFF4444);
    }

    public static final class View {

        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final int left;
        private final int top;
        private final double scale;
        private final int width;
        private final int height;

        private View(int minX, int minZ, int maxX, int maxZ, int left, int top, double scale, int width, int height) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.left = left;
            this.top = top;
            this.scale = scale;
            this.width = width;
            this.height = height;
        }

        public boolean contains(int screenX, int screenZ) {
            return screenX >= left && screenX <= left + width && screenZ >= top && screenZ <= top + height;
        }

        public int worldX(int screenX) {
            return minX + (int) ((screenX - left) / scale);
        }

        public int worldZ(int screenZ) {
            return minZ + (int) ((screenZ - top) / scale);
        }

        public Collection<RoguelikeMapState.Marker> markersAt(RoguelikeMapState.Floor floor, int screenX, int screenZ) {
            if (floor == null || !contains(screenX, screenZ)) return null;
            int worldX = worldX(screenX);
            int worldZ = worldZ(screenZ);
            ListBuilder builder = new ListBuilder();
            for (RoguelikeMapState.Marker marker : floor.getMarkers()) {
                if (marker.x == worldX && marker.z == worldZ) builder.add(marker);
            }
            return builder.result();
        }
    }

    /** 避免为普通悬浮小地图的每一帧创建临时列表。 */
    private static final class ListBuilder {

        private java.util.List<RoguelikeMapState.Marker> list;

        private void add(RoguelikeMapState.Marker marker) {
            if (list == null) list = new java.util.ArrayList<RoguelikeMapState.Marker>();
            list.add(marker);
        }

        private Collection<RoguelikeMapState.Marker> result() {
            return list == null ? java.util.Collections.<RoguelikeMapState.Marker>emptyList() : list;
        }
    }
}
