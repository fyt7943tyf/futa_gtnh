package com.futa_gtnh.client;

import java.util.List;
import java.util.Locale;

/**
 * 搜索框的过滤逻辑。语法刻意做成 NEI 那一套 —— GTNH 玩家已经很熟了，
 * 不用再学一遍：
 *
 * <table>
 * <tr>
 * <td>{@code 铁锭}</td>
 * <td>按显示名匹配（不区分大小写）</td>
 * </tr>
 * <tr>
 * <td>{@code @gregtech}</td>
 * <td>按模组 id 或模组名匹配</td>
 * </tr>
 * <tr>
 * <td>{@code *ingotIron}</td>
 * <td>按矿物词典名匹配</td>
 * </tr>
 * <tr>
 * <td>{@code #tooltip内容}</td>
 * <td>按物品 tooltip 匹配</td>
 * </tr>
 * <tr>
 * <td>{@code a b}</td>
 * <td>空格分隔的多个词是「与」关系</td>
 * </tr>
 * <tr>
 * <td>{@code a|b}</td>
 * <td>竖线分隔是「或」关系</td>
 * </tr>
 * </table>
 *
 * <p>
 * 匹配结果写进调用方给的输出列表，不新建列表 —— 搜索框每敲一个字符都要重跑一次，
 * 几千个条目下每次少分配一个 {@code ArrayList} 是有意义的。
 */
public final class StorageSearch {

    private StorageSearch() {}

    /** 一个「或」组里的一个候选词。 */
    private static final class Term {

        static final char PLAIN = '\0';
        static final char MOD = '@';
        static final char ORE = '*';
        static final char TOOLTIP = '#';

        final char prefix;
        final String text;

        Term(char prefix, String text) {
            this.prefix = prefix;
            this.text = text;
        }

        boolean matches(StorageViewEntry entry) {
            switch (prefix) {
                case MOD:
                    return entry.getModId()
                        .toLowerCase(Locale.ROOT)
                        .contains(text)
                        || entry.getModName()
                            .toLowerCase(Locale.ROOT)
                            .contains(text);
                case ORE:
                    return entry.getOreDictText()
                        .contains(text);
                case TOOLTIP:
                    return entry.getTooltipText()
                        .contains(text);
                default:
                    // 名字分支：装了 NotEnoughCharacters 时交给它 —— 拼音、模糊音、
                    // 生僻字、电压名搜索全是现成的且跟随玩家自己的 NEChar 配置；
                    // 没装时走自研的「小写名 + 预计算拼音后缀」子串匹配。
                    // 注册名两条路径都查（@模组 前缀之外直接敲注册名也应能搜到）。
                    if (NecharBridge.isAvailable()) {
                        return NecharBridge.matches(entry.getDisplayName(), text) || entry.getRegistryName()
                            .toLowerCase(Locale.ROOT)
                            .contains(text);
                    }
                    return entry.getSearchName()
                        .contains(text)
                        || entry.getRegistryName()
                            .toLowerCase(Locale.ROOT)
                            .contains(text);
            }
        }
    }

    /** 一个「与」组：组内任意一个候选词命中即可。 */
    private static final class Group {

        final Term[] terms;

        Group(Term[] terms) {
            this.terms = terms;
        }

        boolean matches(StorageViewEntry entry) {
            for (Term term : terms) {
                if (term.matches(entry)) return true;
            }
            return false;
        }
    }

    /** 把查询串编译成「与」组的数组；空查询返回 null 表示「全都要」。 */
    public static Group[] compile(String query) {
        if (query == null) return null;
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return null;

        String[] words = trimmed.split("\\s+");
        Group[] groups = new Group[words.length];
        for (int i = 0; i < words.length; i++) {
            String[] alternatives = words[i].split("\\|");
            Term[] terms = new Term[alternatives.length];
            for (int j = 0; j < alternatives.length; j++) {
                terms[j] = parseTerm(alternatives[j]);
            }
            groups[i] = new Group(terms);
        }
        return groups;
    }

    private static Term parseTerm(String raw) {
        char prefix = Term.PLAIN;
        String text = raw;

        if (!raw.isEmpty()) {
            char first = raw.charAt(0);
            if (first == Term.MOD || first == Term.ORE || first == Term.TOOLTIP) {
                prefix = first;
                text = raw.substring(1);
            }
        }

        return new Term(prefix, text.toLowerCase(Locale.ROOT));
    }

    /** @return 是否命中（{@code groups == null} 表示空查询，一律命中） */
    public static boolean matches(Group[] groups, StorageViewEntry entry) {
        if (groups == null) return true;
        for (Group group : groups) {
            if (!group.matches(entry)) return false;
        }
        return true;
    }

    /**
     * 过滤。
     *
     * @param source 待过滤的全部条目
     * @param groups {@link #compile} 的结果
     * @param out    输出列表（会被清空）
     */
    public static void filter(List<StorageViewEntry> source, Group[] groups, List<StorageViewEntry> out) {
        out.clear();
        if (groups == null) {
            out.addAll(source);
            return;
        }
        for (int i = 0; i < source.size(); i++) {
            StorageViewEntry entry = source.get(i);
            if (matches(groups, entry)) {
                out.add(entry);
            }
        }
    }
}
