package com.futa_gtnh.cardboard;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * 纸箱打包黑名单。
 *
 * <p>
 * 默认项取自 GT-Not-Leisure 的 {@code loadCardBoardBoxBlackList}：门类（拆下来
 * 会变成半个门的残缺状态）、纸箱自己（套娃会产生垃圾数据）、神龙反应堆核心 /
 * 混沌水晶、IC2 核反应堆 —— 后两类是「方块实体状态离开原位置没有意义」的典型。
 *
 * <p>
 * <b>解析方式刻意不用写死的注册名字符串</b>（IC2/DE 的方块注册名跨版本不稳定）：
 * <ul>
 * <li>门类直接引用原版 {@code Blocks}；</li>
 * <li>IC2 走 {@code IC2Items.getItem} 按物品名解析（{@code reactorVessel} /
 * {@code reactorChamber}，与 {@code Ic2ReactorCompat} 拿压力容器同一套 API）；</li>
 * <li>神龙进化是闭源 mod，反射读 {@code ModBlocks} 的静态 Block 字段；</li>
 * </ul>
 * 解析不到的条目安静跳过（日志记一次），不影响其它条目。配置项
 * {@code cardboardBoxBlacklist} 追加 {@code modid:注册名} 格式的字符串条目。
 */
public final class CardboardBoxBlacklist {

    private CardboardBoxBlacklist() {}

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

        // 门类：拆下来会缺一半
        blockedIds.add(Block.getIdFromBlock(Blocks.wooden_door));
        blockedIds.add(Block.getIdFromBlock(Blocks.iron_door));

        // IC2 核反应堆（GTNH 整包必带 IC2，守卫只是防御）
        if (Loader.isModLoaded("IC2")) resolveIc2();
        // 神龙反应堆核心 / 混沌水晶（可选 mod，反射解析，缺席自动落空）
        if (Loader.isModLoaded("DraconicEvolution")) resolveDraconic();

        for (String entry : Config.cardboardBoxBlacklist) resolve(entry);
        return blockedIds;
    }

    /** IC2：物品名 → 方块。IC2Items 的名字大小写不敏感（Ic2ReactorCompat 同款用法）。 */
    private static void resolveIc2() {
        try {
            addIc2Block("reactorVessel");
            addIc2Block("reactorChamber");
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("纸箱黑名单：IC2 反应堆方块解析失败（跳过这两项）", t);
        }
    }

    private static void addIc2Block(String itemName) {
        ItemStack stack = ic2.api.item.IC2Items.getItem(itemName);
        Block block = stack == null ? null : Block.getBlockFromItem(stack.getItem());
        if (block != null && block != Blocks.air) {
            blockedIds.add(Block.getIdFromBlock(block));
        } else {
            warnOnce("纸箱黑名单：IC2 物品 {} 没解析到方块（IC2 版本不兼容？）", itemName);
        }
    }

    /** 神龙进化：反射读 ModBlocks 的静态字段（编译期无法引用闭源 mod）。 */
    private static void resolveDraconic() {
        try {
            Class<?> modBlocks = Class.forName("com.brandon3055.draconicevolution.common.ModBlocks");
            addDraconicField(modBlocks, "reactorCore");
            addDraconicField(modBlocks, "chaosCrystal");
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("纸箱黑名单：神龙进化方块解析失败（跳过这两项）", t);
        }
    }

    private static void addDraconicField(Class<?> modBlocks, String fieldName) {
        try {
            Object value = modBlocks.getField(fieldName)
                .get(null);
            if (value instanceof Block) {
                blockedIds.add(Block.getIdFromBlock((Block) value));
                return;
            }
        } catch (Throwable ignored) {
            // 字段改名/类型变了：按「解析不到」处理
        }
        warnOnce("纸箱黑名单：神龙进化方块 {} 没解析到（DE 版本不兼容？）", fieldName);
    }

    /** 配置追加条目：{@code modid:注册名}。 */
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
