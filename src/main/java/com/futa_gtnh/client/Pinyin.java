package com.futa_gtnh.client;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 汉字转拼音，纯粹为了让搜索框能用拼音找中文名的东西。
 *
 * <p>
 * 为什么要做这个：1.7.10 用的 LWJGL2 输入层没有输入法支持
 * （{@code org.lwjgl.input.Keyboard} 上压根没有 {@code enableIME} 这个方法），
 * 所以玩家没办法把中文敲进搜索框。拼音搜索直接绕开了这个问题 ——
 * 想找「铁矿石」敲 {@code tiekuang} 或 {@code tks} 就行。
 *
 * <p>
 * <b>数据来源：</b>{@code assets/futa_gtnh/pinyin.txt}，由 mozillazg/pinyin-data
 * 生成（MIT 许可，上游是 Unicode 联盟的 Unihan 数据库）。两万六千多条，
 * 覆盖 GB2312 全部汉字以及大量生僻字 —— 特意没有只收常用字，
 * 因为 GTNH 的物品名里一堆化学元素名（铱、锇、铪、钌……）恰恰常用字表里没有。
 *
 * <p>
 * <b>懒加载：</b>没打开过搜索界面的话，这个类和它那张表根本不会被碰。
 *
 * <p>
 * 只处理 BMP（{@code char} 能表示的范围）。增补平面的汉字在 Java 里是代理对，
 * 而 GTNH 的物品名用不到，为它把查表结构改成按码点索引不划算。
 */
public final class Pinyin {

    private Pinyin() {}

    private static final String RESOURCE = "/assets/futa_gtnh/pinyin.txt";

    /** 主汉字区。索引 = 码点 - {@link #BLOCK_START}，没有读音的位置是 null。 */
    private static final int BLOCK_START = 0x4E00;
    private static final int BLOCK_END = 0x9FFF;
    private static String[] block;

    /** 主区之外的（扩展 A、兼容区）。数量少，用 Map 就够了。 */
    private static Map<Character, String> extra;

    private static boolean loaded;
    private static boolean loadFailed;

    // ==================================================================
    // 查表
    // ==================================================================

    /**
     * 取一个汉字的读音串（可能形如 {@code "nv,ru"}，逗号分隔多个读音）。
     *
     * @return 没有这个字的读音时返回 null
     */
    private static String readingOf(char c) {
        if (c >= BLOCK_START && c <= BLOCK_END) {
            return block[c - BLOCK_START];
        }
        return extra == null ? null : extra.get(c);
    }

    /**
     * 给一个显示名生成附加的搜索文本。
     *
     * <p>
     * 产出三段，都拼在一个字符串里交给调用方一起 {@code contains}：
     *
     * <ul>
     * <li><b>全拼</b>：{@code 铁矿石 -> tiekuangshi}，所以 {@code tiekuang} 能命中；</li>
     * <li><b>首字母</b>：{@code -> tks}，所以 {@code tks} 也能命中；</li>
     * <li>名字里出现 ü 时额外给一份把 v 换成 u 的写法，
     * 这样「女」敲 {@code nv} 和 {@code nu} 都能搜到。</li>
     * </ul>
     *
     * <p>
     * 不用按字加空格分隔：查询串是按空格切成「与」条件的，
     * 而 {@code tie} 和 {@code kuang} 在拼起来的 {@code tiekuangshi} 里本来就是
     * 两段独立的子串，{@code tie kuang} 照样命中。
     *
     * @return 附加文本（以空格开头，方便直接拼接）；名字里没有汉字时返回空串
     */
    public static String searchSuffix(String name) {
        if (name == null || name.isEmpty()) return "";
        if (!ensureLoaded()) return "";

        StringBuilder full = new StringBuilder(name.length() * 3);
        StringBuilder initials = new StringBuilder(name.length());

        for (int i = 0; i < name.length(); i++) {
            String reading = readingOf(name.charAt(i));
            if (reading == null) continue;

            // 多音字只取第一个读音。全收的话「行」会同时贡献 xing 和 hang，
            // 好处是能搜到，坏处是把一串不相干的拼音也塞进了搜索文本里，
            // 反而制造误命中 —— 第一个读音是 Unihan 里最常用的那个，够用了。
            int comma = reading.indexOf(',');
            String first = comma < 0 ? reading : reading.substring(0, comma);
            if (first.isEmpty()) continue;

            full.append(first);
            initials.append(first.charAt(0));
        }

        if (full.length() == 0) return "";

        StringBuilder suffix = new StringBuilder(full.length() + initials.length() + 8);
        suffix.append(' ')
            .append(full)
            .append(' ')
            .append(initials);

        if (full.indexOf("v") >= 0) {
            suffix.append(' ')
                .append(
                    full.toString()
                        .replace('v', 'u'));
        }
        return suffix.toString();
    }

    // ==================================================================
    // 加载
    // ==================================================================

    private static synchronized boolean ensureLoaded() {
        if (loaded) return !loadFailed;
        loaded = true;

        long start = System.currentTimeMillis();
        try (InputStream in = Pinyin.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                loadFailed = true;
                FutaGtnhMod.LOG.warn("拼音表 {} 不在 jar 里，拼音搜索将被禁用", RESOURCE);
                return false;
            }

            block = new String[BLOCK_END - BLOCK_START + 1];
            extra = new HashMap<>();

            // 读音本身只有四百多种，逐个 new String 的话两万多个条目会各占一份内存。
            // 过一遍池子把它们收敛成共享实例，整张表的实际占用就只剩那个引用数组。
            Map<String, String> pool = new HashMap<>();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;

                int space = line.indexOf(' ');
                if (space <= 0 || space + 1 >= line.length()) continue;

                int codePoint;
                try {
                    codePoint = Integer.parseInt(line.substring(0, space), 16);
                } catch (NumberFormatException e) {
                    continue; // 坏行跳过，不能因为一行脏数据废掉整张表
                }
                if (codePoint < 0 || codePoint > 0xFFFF) continue;

                String reading = line.substring(space + 1);
                String pooled = pool.get(reading);
                if (pooled == null) {
                    pool.put(reading, reading);
                    pooled = reading;
                }

                if (codePoint >= BLOCK_START && codePoint <= BLOCK_END) {
                    block[codePoint - BLOCK_START] = pooled;
                } else {
                    extra.put((char) codePoint, pooled);
                }
                count++;
            }

            FutaGtnhMod.LOG.info("拼音表加载完成：{} 条，用时 {} ms", count, System.currentTimeMillis() - start);
            return true;
        } catch (Throwable t) {
            loadFailed = true;
            // 拼音只是锦上添花，读不出来也必须让搜索照常能用
            FutaGtnhMod.LOG.warn("拼音表加载失败，拼音搜索将被禁用", t);
            return false;
        }
    }
}
