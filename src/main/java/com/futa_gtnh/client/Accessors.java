package com.futa_gtnh.client;

import java.lang.reflect.Field;

import net.minecraft.client.gui.inventory.GuiContainer;

/**
 * 读 {@code GuiContainer} 那几个 protected 字段（界面的位置与大小）用的小工具。
 *
 * <p>
 * 为什么要反射：本模组的界面坐标必须知道「底下的界面画在哪」，而
 * {@code guiLeft / guiTop / xSize / ySize} 在 {@code GuiContainer} 里都是 protected，
 * 跨包读不到（原版也没有 getter）。
 *
 * <p>
 * <b>开发环境和正式 jar 的字段名不一样</b>：开发期是 MCP 名（{@code guiLeft}），
 * 打包时 reobf 成 SRG 名（{@code field_147003_i}）。所以两个都试，结果按
 * 「类 + 字段」缓存；都读不到时返回 0，调用方退回默认值。
 */
final class Accessors {

    private static final java.util.Map<String, Field> CACHE = new java.util.HashMap<>();

    private Accessors() {}

    static int intField(Object target, String mcpName, String srgName) {
        if (target == null) return 0;
        String cacheKey = target.getClass()
            .getName() + '#'
            + mcpName;
        Field field = CACHE.get(cacheKey);
        if (field == null) {
            field = find(target.getClass(), mcpName);
            if (field == null) field = find(target.getClass(), srgName);
            if (field == null) {
                CACHE.put(cacheKey, MISS);
                return 0;
            }
            try {
                field.setAccessible(true);
            } catch (Throwable t) {
                CACHE.put(cacheKey, MISS);
                return 0;
            }
            CACHE.put(cacheKey, field);
        }
        if (field == MISS) return 0;
        try {
            return field.getInt(target);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != GuiContainer.class.getSuperclass(); c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往父类找
            }
        }
        return null;
    }

    private static final Field MISS;

    static {
        Field miss = null;
        try {
            miss = Accessors.class.getDeclaredField("MISS");
        } catch (NoSuchFieldException ignored) {
            // 不会发生
        }
        MISS = miss;
    }
}
