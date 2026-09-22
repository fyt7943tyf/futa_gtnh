package com.futa_gtnh.locator;

import net.minecraft.block.Block;

import gregtech.common.blocks.GTBlockOre;

/**
 * 和 GT 矿石元数据编码打交道的那一小块。
 *
 * <p>
 * <b>为什么单独放一个类：</b>这里直接引用了 {@code gregtech.common.blocks.GTBlockOre}，
 * 而它只在较新的 GT5U 里存在。把它隔离出来、由调用方用 {@code try/catch} 包住，
 * 换到一个没有这个类的 GT 版本时会退化成「精确匹配」，而不是把整个扫描炸掉。
 *
 * <p>
 * <b>背景（这是「世界生成的矿找不到、自己放的矿找得到」的根因）：</b>
 * GT 把矿石的全部信息编码在<b>元数据</b>里，而不是方块 id：
 *
 * <pre>
 * meta = 小矿标志 * 16000 + 自然标志 * 8000 + 石种 * 1000 + 材料索引
 * </pre>
 *
 * 也就是说 {@code GTBlockOre} 一个方块要表示成百上千种矿。
 * 其中<b>「自然标志」这一位专门用来区分「世界生成的」和「玩家放下的」</b>：
 * {@code OreManager.getOreBlockForWorldGen} 里写死了 {@code info.isNatural = true}，
 * 而创造标签页里的那份（也就是寻物魔杖能选到的那份）是 {@code false}。
 *
 * <p>
 * 于是如果只做「元数据精确相等」，就必然出现那个诡异的现象：
 * 自己放下的矿永远找得到（因为放置走的就是 {@code isNatural = false} 那一份），
 * 而世界生成的矿永远找不到。这里改成比较<b>矿的身份</b>，把自然标志位排除在外。
 */
final class GtOreSupport {

    private GtOreSupport() {}

    /** 只为把本类加载起来用。类加载失败时调用方会捕获到错误。 */
    static void ping() {}

    static boolean isOre(Block block) {
        return block instanceof GTBlockOre;
    }

    /**
     * 两个元数据是不是「同一种矿」。
     *
     * <p>
     * 比较材料、石种、大小矿三项，<b>故意不看自然标志位</b> ——
     * 世界生成的矿和自己放下的矿在玩家眼里就是同一种东西，
     * 搜「铁矿石」两样都该找到。
     *
     * <p>
     * 用的是 {@code GTBlockOre} 自己的公开解码方法，而不是我在外面重算一遍
     * 位移和取模：编码方式是 GT 的内部约定，让它自己解码才不会在 GT 改动时错位。
     */
    static boolean sameVariant(Block block, int a, int b) {
        GTBlockOre ore = (GTBlockOre) block;
        return ore.getMaterialIndex(a) == ore.getMaterialIndex(b) && ore.getStoneIndex(a) == ore.getStoneIndex(b)
            && ore.isSmallOre(a) == ore.isSmallOre(b);
    }
}
