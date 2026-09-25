package com.futa_gtnh.mixins;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import cpw.mods.fml.common.Loader;

/**
 * 让本模组的 mixin 在「没装匠魂」时安静地不生效。
 *
 * <p>
 * 本项目对匠魂是<b>可选联动</b>（{@code compileOnly}，见 dependencies.gradle），
 * 而混入的目标 {@code tconstruct.tools.logic.CraftingStationLogic} 在缺席时根本不存在。
 * 默认行为是整个 mixin 配置加载失败并抛错 —— 那是「装了匠魂才玩得转」的写法，
 * 对一个主打共享存储的模组来说太重了。这里在应用之前先问一句「匠魂在不在」，
 * 不在就直接跳过，玩家的游戏该怎么跑还怎么跑。
 *
 * <p>
 * 判定用两个来源，任何一个成立就算在场：
 * <ol>
 * <li>FML 的模组列表（正常启动时最权威）；</li>
 * <li>启动类加载器里能不能找到目标类（模组列表还没建好的极早期阶段兜底，
 * 以及开发环境里 -dev jar 直接挂在 classpath 上的情况）。</li>
 * </ol>
 * 结果只在「在场」时缓存：不在场时不缓存，避免在模组列表就绪之前把
 * 「暂时还没发现」误判成永久缺席。
 */
public class FutaGtnhMixinPlugin implements IMixinConfigPlugin {

    /** 匠魂里最能代表「这个模组在」的一个类。 */
    private static final String TCONSTRUCT_PROBE = "tconstruct.tools.logic.CraftingStationLogic";

    /** LootGames 里最能代表「这个模组在」的一个类。 */
    private static final String LOOTGAMES_PROBE = "ru.timeconqueror.lootgames.api.block.GameMasterBlock";

    /** lootgames 目标的 mixin 全部收在这个子包里，按包前缀分流探测。 */
    private static final String LOOTGAMES_MIXIN_PACKAGE = "com.futa_gtnh.mixins.lootgames.";

    /**
     * 目标是原版类、任何时候都该生效的 mixin。
     *
     * <p>
     * 其余的 mixin 都针对可选依赖（匠魂 / LootGames），缺席时要整组跳过 —— 见
     * {@link #shouldApplyMixin} 的分流逻辑。新加原版目标的 mixin 记得
     * 把类名登记进来，否则可选依赖缺席的环境里它会被一起跳掉。
     */
    private static final Set<String> VANILLA_TARGET_MIXINS = new HashSet<>(
        Arrays.asList(
            "MixinBlockReed",
            "MixinEntityItem",
            "MixinEntityPlayer",
            "MixinEntityPlayerMP",
            "MixinEntityPlayerSP",
            "MixinEntityRenderer"));

    private static boolean present;

    private static boolean lootgamesPresent;

    private static boolean tinkersPresent() {
        if (present) return true;

        try {
            if (Loader.isModLoaded("TConstruct")) {
                present = true;
                return true;
            }
        } catch (Throwable ignored) {
            // 启动早期 Loader 还没准备好：走下面的类查找
        }

        try {
            if (Launch.classLoader != null
                && Launch.classLoader.getResource(TCONSTRUCT_PROBE.replace('.', '/') + ".class") != null) {
                present = true;
                return true;
            }
        } catch (Throwable ignored) {
            // 拿不到类加载器（理论上不会）：当作缺席
        }

        return false;
    }

    /** 和 {@link #tinkersPresent()} 同一套两来源判定，只是换了个探测类。 */
    private static boolean lootgamesPresent() {
        if (lootgamesPresent) return true;

        try {
            if (Loader.isModLoaded("lootgames")) {
                lootgamesPresent = true;
                return true;
            }
        } catch (Throwable ignored) {
            // 同上：启动早期兜底走类查找
        }

        try {
            if (Launch.classLoader != null
                && Launch.classLoader.getResource(LOOTGAMES_PROBE.replace('.', '/') + ".class") != null) {
                lootgamesPresent = true;
                return true;
            }
        } catch (Throwable ignored) {
            // 同上
        }

        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // lootgames 目标的 mixin：装了 lootgames 才应用，没装连碰都不碰
        if (mixinClassName.startsWith(LOOTGAMES_MIXIN_PACKAGE)) return lootgamesPresent();
        // 原版目标的 mixin（俯瞰远程 GUI 的距离校验放宽等）无条件生效
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (VANILLA_TARGET_MIXINS.contains(simpleName)) return true;
        // 其余的只针对匠魂；匠魂不在就没有任何东西可混入
        return tinkersPresent();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
