package com.futa_gtnh.cardboard;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * 纸箱打包黑名单。
 *
 * <p>
 * 默认项取自 GT-Not-Leisure 的 {@code loadCardBoardBoxBlackList}：门类（拆下来
 * 会变成半个门的残缺状态）、纸箱自己（套娃会产生垃圾数据）。GT-Not-Leisure 还
 * 黑了神龙反应堆核心 / IC2 核反应堆 —— 那两处是「方块实体状态离开原位置没有
 * 意义」的典型。神龙那边是闭源 mod 没法编译期引用，IC2 的方块注册名在不同版本
 * 间也不稳定，所以这里统一走 <b>字符串解析</b>：{@code modid:注册名}，解析不到
 * 的条目安静跳过（日志记一次），不影响其它条目。
 *
 * <p>
 * 配置项 {@code cardboardBoxBlacklist} 追加在默认项之后，格式同上。
 */
public final class CardboardBoxBlacklist {

    private CardboardBoxBlacklist() {}

    /**
     * 默认黑名单。神龙进化 / IC2 的条目是「尽力解析」：mod 没装或注册名对不上时
     * 自动落空，不留死代码。
     */
    private static final String[] DEFAULTS = { "minecraft:wooden_door", "minecraft:iron_door",
        "DraconicEvolution:draconicReactorCore", "DraconicEvolution:chaosCrystal", "IC2:reactorVessel",
        "IC2:reactorChamber" };

    private static Set<Integer> blockedIds;
    private static boolean warned;

    /** @return (x,y,z) 的方块是否被黑名单命中。纸箱自身永远不可打包（防套娃）。 */
    public static boolean isBlocked(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == FutaGtnhMod.blockCardboardBox) return true;
        if (block == Blocks.air) return true;
        Set<Integer> ids = blockedIds();
        return ids != null && ids.contains(Block.getIdFromBlock(block));
    }

    private static Set<Integer> blockedIds() {
        if (blockedIds != null) return blockedIds;
        blockedIds = new HashSet<>();
        for (String entry : DEFAULTS) resolve(entry);
        for (String entry : Config.cardboardBoxBlacklist) resolve(entry);
        return blockedIds;
    }

    private static void resolve(String entry) {
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) return;
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            warnOnce("纸箱黑名单条目格式不对（应为 modid:注册名）：{}", trimmed);
            return;
        }
        String modId = trimmed.substring(0, separator);
        String name = trimmed.substring(separator + 1);
        Block block = GameRegistry.findBlock(modId, name);
        if (block == null || block == Blocks.air) {
            warnOnce("纸箱黑名单条目没有解析到方块（mod 没装或名字不对）：{}", trimmed);
            return;
        }
        blockedIds.add(Block.getIdFromBlock(block));
    }

    private static void warnOnce(String message, String entry) {
        if (warned) return;
        warned = true;
        FutaGtnhMod.LOG.warn(message, entry);
    }
}
