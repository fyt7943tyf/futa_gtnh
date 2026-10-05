package com.futa_gtnh.locator;

import java.util.Locale;
import java.util.Optional;

import net.minecraft.world.ChunkCoordIntPair;

import com.futa_gtnh.FutaGtnhMod;

import gregtech.crossmod.visualprospecting.VisualProspectingDatabase;

/**
 * 问 VisualProspecting「这个区块是哪条矿脉」。
 *
 * <p>
 * <b>为什么要它：</b>寻物魔杖的矿脉模式原本只能扫<b>已加载</b>的区块（见
 * {@link LocatorScan#tick()} 里那段 {@code chunkExists} 判断）——没加载的区块一律跳过，
 * 因为把上千个区块从磁盘拉起来比卡顿严重得多。可是玩家真正想找的矿脉，往往就在
 * 自己昨天路过、现在没加载的地方。
 *
 * <p>
 * GT 的探矿仪（Detrav Scanner）能显示那些地方，靠的不是自己算，而是
 * <b>VisualProspecting 从存档里解析出来的矿脉记录</b>：它直接读 {@code region/*.mca}
 * 区域文件（VP 的 {@code database/cachebuilder/RegionReader}），把每个区块生成出来的
 * 矿脉解析出来，<b>不需要把区块加载进游戏</b>。GT 通过
 * {@link VisualProspectingDatabase#getVeinName(int, ChunkCoordIntPair)} 把这份数据
 * 暴露出来，这里就是用它。
 *
 * <p>
 * <b>能查到什么、查不到什么：</b>只要那个区块在存档里存在（曾经被生成过），VP 就有记录，
 * 哪怕它现在完全没加载。反过来，<b>从没生成过的区块谁也没有数据</b> —— 那要重算世界生成，
 * GT 没有公开接口，这里也不去猜。
 *
 * <p>
 * <b>没装 VP 时是安全的：</b>GT 那侧的实现里 {@code database == null} 就直接返回空
 * （见 {@code VisualProspectingDatabase.getVeinName}），所以未安装时这里每次都拿到空、
 * 魔杖的行为与改动前完全一样，也不会多加载任何区块。
 */
final class VeinDatabaseLookup {

    private VeinDatabaseLookup() {}

    /** 名字匹配成功的次数：只用来在日志里说明「这条数据真的被用上了」。 */
    private static int matchLogged;

    private static final int LOG_LIMIT = 3;

    private static boolean availabilityLogged;

    /**
     * 开一次搜索时说明一下数据源状态。
     *
     * <p>
     * 「矿脉搜不到」这种反馈最难查的地方在于：到底是没装 VP、还是这个区块从没生成过、
     * 还是名字没匹配上，从外面看都只是「没找到」。所以这里把前两种在日志里说清楚，
     * 匹配上的情况由 {@link #matches} 那条日志负责。
     */
    static void logAvailability() {
        if (availabilityLogged) return;
        availabilityLogged = true;
        try {
            if (cpw.mods.fml.common.Loader.isModLoaded("visualprospecting")) {
                FutaGtnhMod.LOG.info("寻物魔杖：检测到 VisualProspecting —— 矿脉搜索能查到未加载区块里已生成的矿脉");
            } else {
                FutaGtnhMod.LOG.info("寻物魔杖：没有 VisualProspecting —— 矿脉搜索只能扫已加载的区块");
            }
        } catch (Throwable t) {
            // 只是日志，出问题不影响搜索
        }
    }

    /**
     * 这个区块是不是目标矿脉。
     *
     * @param dimensionId 维度
     * @param chunkX      区块 X
     * @param chunkZ      区块 Z
     * @param entry       玩家要找的那条矿脉
     * @return 是这条矿脉就返回 true；没有数据 / 不是它 / 没装 VP 都返回 false
     */
    static boolean matches(int dimensionId, int chunkX, int chunkZ, OreVeinCatalog.Entry entry) {
        if (entry == null) return false;

        String name;
        try {
            Optional<String> found = VisualProspectingDatabase
                .getVeinName(dimensionId, new ChunkCoordIntPair(chunkX, chunkZ));
            if (found == null || !found.isPresent()) return false;
            name = found.get();
        } catch (Throwable t) {
            // 别的模组换了实现、或者 VP 版本对不上：当作没有数据，别把整次搜索搞崩
            if (matchLogged == 0) {
                FutaGtnhMod.LOG.warn("寻物魔杖：查 VisualProspecting 的矿脉数据失败，退回逐方块扫描", t);
                matchLogged = -1;
            }
            return false;
        }

        boolean hit = nameMatches(name, entry);
        if (hit && matchLogged >= 0 && matchLogged < LOG_LIMIT) {
            matchLogged++;
            FutaGtnhMod.LOG.info(
                "寻物魔杖：区块 ({}, {}) 命中 VisualProspecting 的矿脉「{}」（找的是「{}」）—— 该区块未加载也能找到",
                chunkX,
                chunkZ,
                name,
                entry.getTitle());
        }
        return hit;
    }

    /**
     * VP 给的名字和我们要找的矿脉是不是同一条。
     *
     * <p>
     * 两边的名字<b>不是同一个字段</b>：VP 返回的是 GT 的 {@code oreMix.getLocalizedName()}
     * （例如「铁矿脉」），而条目这边有内部名（{@code oreMixName}，例如「ore.mix.iron」）
     * 和自己拼的显示名。所以这里做三重比较，都按「去格式码、小写、去掉空白与矿脉后缀」
     * 归一化之后比：
     *
     * <ol>
     * <li>和条目自己的显示名相等；</li>
     * <li>和条目的内部名相等；</li>
     * <li>VP 的名字里含有主材料的本地化名（「铁矿脉」里有「铁」）。</li>
     * </ol>
     *
     * <p>
     * 第三条看着松，但它才是真正兜住的那条：GT 的本地化名是「主材料 + 矿脉」拼的，
     * 而拼接细节（空格、后缀、语言）跨版本会变。宁可放宽一点也别让功能静默失效 ——
     * 命中的日志会打出两边各自叫什么，真错了能一眼看出来。
     */
    static boolean nameMatches(String veinName, OreVeinCatalog.Entry entry) {
        String left = normalize(veinName);
        if (left.isEmpty()) return false;

        String title = normalize(entry.getTitle());
        if (!title.isEmpty() && (left.equals(title) || left.contains(title) || title.contains(left))) {
            return true;
        }

        String key = normalize(entry.getKey());
        if (!key.isEmpty() && left.equals(key)) {
            return true;
        }

        // 「铁矿脉」 vs 主材料「铁」：主材料名要有两个字以上，否则「铜」这种单字会误伤
        // （比如「铜矿脉」和「青铜矿脉」都含「铜」）
        String primary = normalize(entry.getPrimaryMaterialName());
        return primary.length() >= 2 && left.contains(primary);
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String out = text.toLowerCase(Locale.ROOT);
        // 去掉 §a 这类格式码
        out = out.replaceAll("\u00a7.", "");
        out = out.replace(" ", "")
            .replace("_", "")
            .replace(".", "");
        // 中英文的「矿脉 / vein」后缀都去掉再比
        out = out.replace("矿脉", "")
            .replace("vein", "");
        return out.trim();
    }
}
