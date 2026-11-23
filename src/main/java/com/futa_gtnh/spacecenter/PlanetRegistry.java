package com.futa_gtnh.spacecenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.gtnewhorizon.gtnhlib.hash.Fnv1a64;

import gregtech.GTLoggers;
import gregtech.GTMod;
import gregtech.api.objects.GTUOFluid;
import gregtech.common.WorldgenGTOreLayer;

/**
 * 星球注册表：把「Galacticraft 天体 × GT 矿脉/油田数据」拼成深空采矿指挥中心可用的星球列表。
 *
 * <p>
 * 数据来源（全部来自 GT5u 本体，无额外依赖）：
 * <ul>
 * <li>天体（名字/维度 id/火箭等级）：Galacticraft API（经 {@link GcCompat} 隔离）；</li>
 * <li>矿脉：{@link WorldgenGTOreLayer#sList} 按 {@code canGenerateIn(dimName)} 过滤；</li>
 * <li>油田流体：{@code GTMod.proxy.mUndergroundOil.GetDimension(dimId)}。</li>
 * </ul>
 *
 * <p>
 * 星球种子（复现真实矿脉/油田的输入）由 {@link PlanetSeeds} 在维度加载时捕获并持久化；
 * 服务器侧单例构建，客户端只通过 GUI 同步数据读展示信息，不自行构建。
 */
public final class PlanetRegistry {

    /** 一个可远程开采的星球。 */
    public static final class PlanetInfo {

        /** GT 维度键（= GC 天体 name，如 "moon"），矿脉/油田查询都用它。 */
        public final String dimName;
        /** 数字维度 id（Galacticraft 注册值，如月球 -28）。 */
        public final int dimensionId;
        /** 到达所需火箭等级。 */
        public final int tierRequirement;
        /** 该星球允许生成的 GT 矿脉（运行时配置生效后的列表）。 */
        public final List<WorldgenGTOreLayer> veins;
        /** 该星球可钻的地下流体（GT 油田配置）。 */
        public final List<GTUOFluid> fluids;
        /** GUI 列表排序与图标用的稳定 id。 */
        public final int index;

        PlanetInfo(String dimName, int dimensionId, int tierRequirement, List<WorldgenGTOreLayer> veins,
            List<GTUOFluid> fluids, int index) {
            this.dimName = dimName;
            this.dimensionId = dimensionId;
            this.tierRequirement = tierRequirement;
            this.veins = Collections.unmodifiableList(veins);
            this.fluids = Collections.unmodifiableList(fluids);
            this.index = index;
        }

        public boolean hasVeins() {
            return !veins.isEmpty();
        }

        public boolean hasFluids() {
            return !fluids.isEmpty();
        }

        /** 该星球各矿脉的总权重（权重预览用）。 */
        public int totalVeinWeight() {
            int total = 0;
            for (WorldgenGTOreLayer vein : veins) total += vein.mWeight;
            return total;
        }
    }

    private static volatile List<PlanetInfo> planets = null;

    private PlanetRegistry() {}

    /** 服务端调用：Galacticraft 与 GT 世界生成注册完成后构建（postInit 之后首次使用时惰性构建）。 */
    public static List<PlanetInfo> planets() {
        if (planets != null) return planets;
        synchronized (PlanetRegistry.class) {
            if (planets != null) return planets;
            List<PlanetInfo> result = new ArrayList<>();
            if (GcCompat.isAvailable()) {
                int index = 0;
                for (GcCompat.BodyInfo body : GcCompat.listReachableBodies()) {
                    List<WorldgenGTOreLayer> veins = new ArrayList<>();
                    for (WorldgenGTOreLayer layer : WorldgenGTOreLayer.sList) {
                        if (layer.canGenerateIn(body.name)) veins.add(layer);
                    }
                    List<GTUOFluid> fluids = new ArrayList<>();
                    try {
                        gregtech.api.objects.GTUODimension uoDim = GTMod.proxy.mUndergroundOil
                            .GetDimension(body.dimensionId);
                        if (uoDim != null) {
                            for (GTUOFluid fluid : uoDim.getFluids()
                                .values()) {
                                if (fluid != null && fluid.getFluid() != null
                                    && fluid.Chance > 0
                                    && fluid.MaxAmount > 0) {
                                    fluids.add(fluid);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        // 维度未注册（对应星球模组没装）时 GetDimension 可能失败，安静跳过油田
                        GTLoggers.GT_FML_LOGGER.debug("spacecenter: fluids lookup failed for {}", body.name, t);
                    }
                    // 没矿也没油的星球没有远程开采价值
                    if (veins.isEmpty() && fluids.isEmpty()) continue;
                    result.add(new PlanetInfo(body.name, body.dimensionId, body.tierRequirement, veins, fluids, index));
                    index++;
                }
            }
            planets = result;
            return result;
        }
    }

    public static PlanetInfo byName(String dimName) {
        for (PlanetInfo info : planets()) {
            if (info.dimName.equals(dimName)) return info;
        }
        return null;
    }

    /** 客户端也能安全调：注册表只在服务端有数据，客户端拿到的是空表（展示走 GUI 同步）。 */
    public static boolean isBuilt() {
        return planets != null;
    }

    /** GUI 同步用的稳定星球 id：名字哈希。 */
    public static long nameKey(String dimName) {
        return Fnv1a64.hashStep(Fnv1a64.initialState(), dimName.hashCode());
    }

    /** 尝试从已加载维度补抓种子（指挥中心 tick 时对缺种子的星球兜底）。 */
    public static Long tryCaptureSeed(int dimensionId) {
        World world = DimensionManager.getWorld(dimensionId);
        if (world == null || world.isRemote) return null;
        return world.getSeed();
    }
}
