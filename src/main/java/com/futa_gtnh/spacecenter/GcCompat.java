package com.futa_gtnh.spacecenter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.Loader;
import micdoodle8.mods.galacticraft.api.galaxies.CelestialBody;
import micdoodle8.mods.galacticraft.api.galaxies.GalaxyRegistry;
import micdoodle8.mods.galacticraft.api.galaxies.Moon;
import micdoodle8.mods.galacticraft.api.galaxies.Planet;

/**
 * Galacticraft 类型隔离层：本类引用的 {@code micdoodle8.*} 类型只在装了 Galacticraft
 * （modid {@code GalacticraftCore}）时才会被加载，仓库既有可选联动范式。
 *
 * <p>
 * 提供两件事：
 * <ul>
 * <li>{@link #listReachableBodies()}：枚举注册的全部行星/卫星（含 GalaxySpace、AmunRa
 * 等通过 GC API 注册的天体），读出 GT 矿脉系统用的维度键（天体 name，如 {@code "moon"}）、
 * 数字维度 id 与「到达所需火箭等级」；</li>
 * <li>{@link #rocketTierOf(ItemStack)}：识别火箭物品的等级。GC 系火箭物品是平铺的
 * {@code ItemTier{N}Rocket} 类（没有公共接口），这里按类名模式识别，核心三档再做实例兜底。</li>
 * </ul>
 */
public final class GcCompat {

    public static final String MOD_ID = "GalacticraftCore";

    private static Boolean available;

    /** 天体信息（服务端注册完成后填充）。 */
    public static final class BodyInfo {

        /** GC 天体 key，同时是 GT 矿脉/油田系统的维度键（如 "moon"、"mars"）。 */
        public final String name;
        /** 数字维度 id（负数 = 未注册/未加载）。 */
        public final int dimensionId;
        /** 到达该天体所需的火箭等级。 */
        public final int tierRequirement;

        BodyInfo(String name, int dimensionId, int tierRequirement) {
            this.name = name;
            this.dimensionId = dimensionId;
            this.tierRequirement = tierRequirement;
        }
    }

    private GcCompat() {}

    public static boolean isAvailable() {
        if (available == null) available = Loader.isModLoaded(MOD_ID);
        return available;
    }

    /** 枚举所有可达行星与卫星；必须只在 {@link #isAvailable()} 为真时调用。 */
    public static List<BodyInfo> listReachableBodies() {
        List<BodyInfo> bodies = new ArrayList<>();
        for (Planet planet : GalaxyRegistry.getRegisteredPlanets()
            .values()) {
            addBody(bodies, planet);
        }
        for (Moon moon : GalaxyRegistry.getRegisteredMoons()
            .values()) {
            addBody(bodies, moon);
        }
        return bodies;
    }

    private static void addBody(List<BodyInfo> bodies, CelestialBody body) {
        if (body == null || !body.getReachable()) return;
        String name = body.getName();
        if (name == null || name.isEmpty()) return;
        bodies.add(new BodyInfo(name, body.getDimensionID(), body.getTierRequirement()));
    }

    private static final Pattern TIER_ROCKET_CLASS = Pattern.compile("ItemTier(\\d+)Rocket");

    /**
     * 识别火箭物品等级。
     *
     * @return 1~9 的等级；不是火箭物品返回 -1
     */
    public static int rocketTierOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return -1;
        Class<?> cls = stack.getItem()
            .getClass();
        while (cls != null) {
            Matcher matcher = TIER_ROCKET_CLASS.matcher(cls.getSimpleName());
            if (matcher.find()) {
                int tier = Integer.parseInt(matcher.group(1));
                return tier >= 1 && tier <= 9 ? tier : -1;
            }
            // 匿名/子类兜底：往父类找一层
            cls = cls.getSuperclass();
        }
        return -1;
    }
}
