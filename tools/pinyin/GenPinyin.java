import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * 一次性工具：把 mozillazg/pinyin-data 的 pinyin.txt 转成本模组用的紧凑拼音表。
 *
 * <p>
 * <b>不属于构建流程</b>，只在需要更新拼音数据时手动跑一次。生成结果
 * （{@code src/main/resources/assets/futa_gtnh/pinyin.txt}）是提交进版本库的，
 * 所以日常构建和这个工具无关。
 *
 * <p>
 * 用法（JDK 11+ 的单文件源码启动，不需要先编译）：
 *
 * <pre>
 *   # 1. 下载原始数据（约 1 MB）
 *   curl -L -o pinyin.txt https://raw.githubusercontent.com/mozillazg/pinyin-data/master/pinyin.txt
 *   # 2. 转换
 *   java tools/pinyin/GenPinyin.java pinyin.txt src/main/resources/assets/futa_gtnh/pinyin.txt
 * </pre>
 *
 * <p>
 * 源数据以 MIT 许可发布（上游是 Unicode 联盟的 Unihan 数据库），
 * 许可文本随生成结果记在文件头里。
 */
public class GenPinyin {

    public static void main(String[] args) throws Exception {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        Pattern p = Pattern.compile("^U\\+([0-9A-Fa-f]{4,6}):\\s*([^#]+)");
        // 只收 BMP 里的汉字区。增补平面（>U+FFFF）在 Java 里是代理对，
        // 用 char 索引的查表结构处理不了，而 GTNH 的物品名根本用不到那些字。
        int[][] ranges = { { 0x3400, 0x4DBF }, { 0x4E00, 0x9FFF }, { 0xF900, 0xFAFF } };

        TreeMap<Integer, String> table = new TreeMap<>();
        int total = 0, kept = 0, dropped = 0;

        for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
            total++;
            Matcher m = p.matcher(line.trim());
            if (!m.find()) continue;

            int cp = Integer.parseInt(m.group(1), 16);
            if (!inRanges(cp, ranges)) { dropped++; continue; }

            List<String> readings = new ArrayList<>();
            for (String raw : m.group(2).split(",")) {
                String r = normalize(raw.trim());
                if (!r.isEmpty() && !readings.contains(r)) readings.add(r);
                if (readings.size() >= 3) break; // 多音字最多留 3 个，再多只是白占地方
            }
            if (readings.isEmpty()) continue;

            table.put(cp, String.join(",", readings));
            kept++;
        }

        StringBuilder sb = new StringBuilder(table.size() * 12);
        sb.append("# FutaGTNH 拼音表（供搜索用）\n");
        sb.append("#\n");
        sb.append("# 由 mozillazg/pinyin-data 的 pinyin.txt 生成，该数据以 MIT 许可发布，\n");
        sb.append("# 其上游为 Unicode 联盟的 Unihan 数据库（Unicode License）。\n");
        sb.append("# 来源：https://github.com/mozillazg/pinyin-data\n");
        sb.append("#\n");
        sb.append("# 格式：每行「<码点十六进制> <读音>[,<读音>...]」\n");
        sb.append("# 声调已去掉（铁 tiě -> tie）；ü 统一记作 v（女 nǚ -> nv）。\n");
        sb.append("# 多音字保留最多 3 个读音，用逗号分隔，第一个是最常用的。\n");
        for (Map.Entry<Integer, String> e : table.entrySet()) {
            sb.append(String.format("%04X", e.getKey()))
                .append(' ')
                .append(e.getValue())
                .append('\n');
        }

        Files.createDirectories(out.getParent());
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("source lines : " + total);
        System.out.println("kept entries : " + kept);
        System.out.println("dropped (non-BMP / non-CJK): " + dropped);
        System.out.println("distinct syllables : " + new HashSet<>(table.values()).size());
        System.out.println("output bytes : " + Files.size(out));
    }

    private static boolean inRanges(int cp, int[][] ranges) {
        for (int[] r : ranges) {
            if (cp >= r[0] && cp <= r[1]) return true;
        }
        return false;
    }

    /**
     * 去掉声调。先把 ü 系列换成 v（NFD 分解会把 ü 拆成 u + 分音符，
     * 那样就变成 u 了，"nv" 会搜不到「女」），再做一次 NFD 分解并剥掉所有组合记号。
     */
    private static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00FC' || c == '\u01D6' || c == '\u01D8' || c == '\u01DA' || c == '\u01DC') {
                sb.append('v');
            } else {
                sb.append(c);
            }
        }
        String decomposed = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
    }
}
