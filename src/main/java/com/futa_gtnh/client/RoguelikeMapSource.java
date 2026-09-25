package com.futa_gtnh.client;

import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * Roguelike Dungeons 的源码侧生成模型。
 *
 * <p>
 * 这里的常量对应 Roguelike-Dungeons 源码中的 {@code DungeonGenerator}：顶层基准为
 * 50，每层向下间隔 10 格，一共最多五层。方块权重对应 {@code ThemeOak}、
 * {@code ThemeSpruce}、{@code ThemeCrypt}、{@code ThemeMossy}、{@code ThemeNether} 和
 * {@code ThemeHouse} 等主题的墙体、地板、楼梯、柱子集合。
 *
 * <p>
 * 这不是「把所有实心方块都当成墙」的通用洞穴扫描器。地图边界只接受这里列出的、
 * 能在 Roguelike 生成器中出现的方块；外部世界的石头、泥土和洞穴轮廓不会成为边界。
 */
public final class RoguelikeMapSource {

    public static final int TOP_LEVEL = 50;
    public static final int VERTICAL_SPACING = 10;
    public static final int LEVEL_COUNT = 5;

    public static final int ROLE_MATERIAL = 1;
    public static final int ROLE_STAIR = 1 << 1;
    public static final int ROLE_PILLAR = 1 << 2;
    public static final int ROLE_DECORATION = 1 << 3;

    private RoguelikeMapSource() {}

    public static int levelY(int level) {
        if (level < 0) return TOP_LEVEL;
        if (level >= LEVEL_COUNT) return TOP_LEVEL - (LEVEL_COUNT - 1) * VERTICAL_SPACING;
        return TOP_LEVEL - level * VERTICAL_SPACING;
    }

    /** 根据源码中的 Dungeon.getLevel 规则把世界高度映射到层编号。 */
    public static int levelForY(int y) {
        if (y < 15) return 4;
        if (y < 25) return 3;
        if (y < 35) return 2;
        if (y < 45) return 1;
        return 0;
    }

    /**
     * 对一个方块给出来源权重和角色。权重越高，越能说明它是地牢生成器留下的结构。
     * 同一批主题方块既可能被用作墙也可能被用作地板，具体角色由扫描时的高度决定。
     */
    public static Classification classify(Block block, int metadata) {
        if (block == null || block == Blocks.air) return Classification.NONE;

        if (block == Blocks.stonebrick) return new Classification(9, ROLE_MATERIAL);
        if (block == Blocks.cobblestone) return new Classification(7, ROLE_MATERIAL);
        if (block == Blocks.mossy_cobblestone) return new Classification(8, ROLE_MATERIAL);
        if (block == Blocks.gravel) return new Classification(4, ROLE_MATERIAL);
        if (block == Blocks.brick_block) return new Classification(9, ROLE_MATERIAL);
        if (block == Blocks.nether_brick) return new Classification(9, ROLE_MATERIAL);
        if (block == Blocks.netherrack) return new Classification(5, ROLE_MATERIAL);
        if (block == Blocks.quartz_ore) return new Classification(5, ROLE_MATERIAL);
        if (block == Blocks.soul_sand) return new Classification(5, ROLE_MATERIAL);
        if (block == Blocks.coal_block) return new Classification(6, ROLE_MATERIAL);
        if (block == Blocks.obsidian) return new Classification(9, ROLE_MATERIAL | ROLE_PILLAR);
        if (block == Blocks.iron_bars) return new Classification(5, ROLE_DECORATION);
        if (block == Blocks.sandstone) return new Classification(6, ROLE_MATERIAL);
        if (block == Blocks.clay) return new Classification(5, ROLE_MATERIAL);
        if (block == Blocks.wool) return new Classification(4, ROLE_DECORATION);
        if (block == Blocks.redstone_block || block == Blocks.gold_block || block == Blocks.diamond_block) {
            return new Classification(6, ROLE_MATERIAL);
        }
        if (block == Blocks.stained_hardened_clay || block == Blocks.hardened_clay) {
            return new Classification(5, ROLE_MATERIAL);
        }
        if (block == Blocks.stone) {
            // ThemeHouse/ThemeCrypt 的花岗岩、闪长岩和安山岩来自石头元数据。
            int stoneType = metadata & 15;
            if (stoneType >= 1 && stoneType <= 5) return new Classification(6, ROLE_MATERIAL | ROLE_PILLAR);
        }

        if (block == Blocks.planks) return new Classification(7, ROLE_MATERIAL | ROLE_PILLAR);
        if (block == Blocks.log || block == Blocks.log2) return new Classification(8, ROLE_PILLAR);

        String name = block.getUnlocalizedName()
            .toLowerCase(Locale.ROOT);

        if (name.contains("stonebrick") || name.contains("stone_brick")) {
            return new Classification(8, ROLE_MATERIAL);
        }
        if (name.contains("netherbrick") || name.contains("nether_brick")) {
            return new Classification(8, ROLE_MATERIAL);
        }
        if (name.contains("cobblestone") || name.contains("mossycobble")) {
            return new Classification(6, ROLE_MATERIAL);
        }
        if (name.contains("brick")) return new Classification(7, ROLE_MATERIAL);
        if (name.contains("andesite") || name.contains("granite") || name.contains("diorite")) {
            return new Classification(5, ROLE_MATERIAL | ROLE_PILLAR);
        }
        if (name.contains("stairs") || name.contains("stair")) {
            return new Classification(7, ROLE_STAIR);
        }
        if (name.contains("slab")) return new Classification(4, ROLE_STAIR | ROLE_DECORATION);
        if (name.contains("plank") || name.contains("wood")) {
            return new Classification(5, ROLE_MATERIAL | ROLE_PILLAR);
        }
        if (name.contains("log")) return new Classification(6, ROLE_PILLAR);
        if (name.contains("lamp") || name.contains("torch") || name.contains("lantern")) {
            return new Classification(2, ROLE_DECORATION);
        }

        return Classification.NONE;
    }

    public static final class Classification {

        private static final Classification NONE = new Classification(0, 0);

        public final int weight;
        public final int roles;

        private Classification(int weight, int roles) {
            this.weight = weight;
            this.roles = roles;
        }
    }
}
