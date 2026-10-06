// 从网页配方索引的磁盘缓存里翻配方（不需要重开游戏）。
//
// 为什么要这个工具：网页上「某个物品有哪些配方」是索引算出来的，
// 但「某个配方到底存不存在、被记到了哪个物品名下」光看网页看不出来 ——
// 比如「熔融焊锡的合金炉配方去哪了」这种问题，必须把索引里那几十万条配方按 id 翻一遍。
//
// 用法：
//   java DumpRecipes.java <web_recipes.dat> --item 36885
//       列出「产出里有这个 id」或「材料里有这个 id」的配方
//   java DumpRecipes.java <web_recipes.dat> --handler 合金
//       列出处理器名里含「合金」的配方（看某个机器在索引里到底是什么样）
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class DumpRecipes {

    private static int[] ints;
    private static short[] shorts;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println(
                "用法: java DumpRecipes.java <web_recipes.dat> --handlers | --item <id> | --produces <id> | --handler <标签子串> | --handlerstat <标签子串>");
            return;
        }
        String mode = args[1];
        String value = args.length > 2 ? args[2] : "";

        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(new File(args[0])))))) {
            int magic = in.readInt();
            int version = in.readInt();
            String fingerprint = in.readUTF();
            int handlers = in.readInt();
            List<String> handlerNames = new ArrayList<>(handlers);
            List<String> handlerTags = new ArrayList<>(handlers);
            for (int i = 0; i < handlers; i++) {
                handlerNames.add(in.readUTF());
                handlerTags.add(in.readUTF());
                in.readUTF();
            }
            int items = in.readInt();
            List<String> identities = new ArrayList<>(items);
            for (int i = 0; i < items; i++) {
                if (version >= 13) in.readUTF();
                identities.add(in.readUTF());
            }

            int recipeCount = in.readInt();
            int[] handlerSlot = readInts(in);
            readInts(in); // handlerIndex
            int[] primaryResult = readInts(in);
            int[] primaryAmount = readInts(in);
            readInts(in); // euPerTick
            readInts(in); // durationTicks

            int[] resOffset = readInts(in);
            int[] resId = readInts(in);
            int[] resAmount = readInts(in);
            readInts(in); // resAlt
            readInts(in); // resChance
            readShorts(in);
            readShorts(in);
            int[] ingOffset = readInts(in);
            int[] ingId = readInts(in);
            int[] ingAmount = readInts(in);

            System.out.println(
                "缓存 version=" + version + " 处理器=" + handlers + " 物品=" + items + " 配方=" + recipeCount);

            if (mode.equals("--handlers")) {
                // 每个处理器贡献多少条配方：要剔掉某一类噪音配方时，先看这里
                int[] counts = new int[handlers];
                for (int o = 0; o < recipeCount; o++) {
                    int slot = handlerSlot[o];
                    if (slot >= 0 && slot < handlers) counts[slot]++;
                }
                Integer[] order = new Integer[handlers];
                for (int i = 0; i < handlers; i++) order[i] = Integer.valueOf(i);
                java.util.Arrays.sort(order, (a, b) -> counts[b.intValue()] - counts[a.intValue()]);
                for (int i = 0; i < handlers; i++) {
                    int slot = order[i].intValue();
                    System.out.println("  " + counts[slot] + "\t" + handlerTags.get(slot) + "\t" + handlerNames.get(slot));
                }
                return;
            }

            if (mode.equals("--produces")) {
                int want = Integer.parseInt(value);
                int shown = 0;
                for (int o = 0; o < recipeCount; o++) {
                    if (primaryResult[o] != want && !contains(resId, slice(resOffset, o), want)) continue;
                    System.out.println(describe(o, handlerSlot, handlerNames, primaryResult, primaryAmount, identities));
                    System.out.println("      产出: " + list(resId, resOffset, o, resAmount, identities));
                    System.out.println("      材料: " + list(ingId, ingOffset, o, ingAmount, identities));
                    if (++shown >= 60) {
                        System.out.println("      …（只列前 60 条）");
                        break;
                    }
                }
                if (shown == 0) System.out.println("（没有任何配方产出这个 id）");
                return;
            }

            if (mode.equals("--item")) {
                int want = Integer.parseInt(value);
                int shown = 0;
                for (int o = 0; o < recipeCount; o++) {
                    boolean produces = primaryResult[o] == want || contains(resId, slice(resOffset, o), want);
                    boolean consumes = contains(ingId, slice(ingOffset, o), want);
                    if (!produces && !consumes) continue;
                    System.out.println(describe(o, handlerSlot, handlerNames, primaryResult, primaryAmount, identities));
                    System.out.println("      产出: " + list(resId, resOffset, o, resAmount, identities));
                    System.out.println("      材料: " + list(ingId, ingOffset, o, ingAmount, identities));
                    if (++shown >= 60) {
                        System.out.println("      …（只列前 60 条）");
                        break;
                    }
                }
                if (shown == 0) System.out.println("（没有任何配方涉及这个 id）");
            } else if (mode.equals("--suspect")) {
                // 找出「条目一大堆、主产物却几乎只有一个」的处理器。
                //
                // 这是「NEI 的展示页面被当成配方读进来」的指纹：燃料页、基因采样页这种页面
                // 只是罗列物品（能不能烧、能采什么基因），结果槽里留着模板上的东西，
                // 索引一读就变成「几千种物品都能变成同一个产物」，凭空造出一堆假配方和假环。
                int[] counts = new int[handlers];
                java.util.Map<Integer, java.util.HashSet<Integer>> seen = new java.util.HashMap<>();
                for (int o = 0; o < recipeCount; o++) {
                    int slot = Math.max(0, Math.min(handlers - 1, handlerSlot[o]));
                    counts[slot]++;
                    seen.computeIfAbsent(Integer.valueOf(slot), k -> new java.util.HashSet<>())
                        .add(Integer.valueOf(primaryResult[o]));
                }
                Integer[] order = new Integer[handlers];
                for (int i = 0; i < handlers; i++) order[i] = Integer.valueOf(i);
                java.util.Arrays.sort(order, (a, b) -> counts[b.intValue()] - counts[a.intValue()]);
                for (int i = 0; i < handlers; i++) {
                    int slot = order[i].intValue();
                    if (counts[slot] < 100) break;
                    java.util.HashSet<Integer> set = seen.get(Integer.valueOf(slot));
                    int distinct = set == null ? 0 : set.size();
                    if ((double) distinct / (double) counts[slot] > 0.02D) continue;
                    System.out.println(
                        "  " + counts[slot] + " 条 / 主产物只有 " + distinct + " 种\t" + handlerTags.get(slot) + "\t"
                            + handlerNames.get(slot));
                }
            } else if (mode.equals("--handlerstat")) {
                // 某个处理器在索引里到底长什么样：条目数、不同主产物个数、最常见的主产物。
                //
                // 「条目几千条、主产物却只有一个」是模板被读串了的典型特征（NEI 的燃料页就是
                // 把燃料槽画在熔炉模板上，结果槽里留着上一条熔炉配方的产物）。
                int[] counts = new int[handlers];
                java.util.Map<Integer, Integer> results = new java.util.HashMap<>();
                for (int o = 0; o < recipeCount; o++) {
                    int slot = Math.max(0, Math.min(handlers - 1, handlerSlot[o]));
                    if (!handlerTags.get(slot)
                        .contains(value)) continue;
                    counts[slot]++;
                    Integer key = Integer.valueOf(primaryResult[o]);
                    results.put(key, Integer.valueOf(results.getOrDefault(key, Integer.valueOf(0)).intValue() + 1));
                }
                int total = 0;
                for (int i = 0; i < handlers; i++) {
                    if (counts[i] == 0) continue;
                    total += counts[i];
                    System.out.println("  " + counts[i] + "\t" + handlerTags.get(i) + "\t" + handlerNames.get(i));
                }
                System.out.println("合计 " + total + " 条，不同主产物 " + results.size() + " 个");
                results.entrySet()
                    .stream()
                    .sorted((a, b) -> b.getValue()
                        .intValue()
                        - a.getValue()
                            .intValue())
                    .limit(8)
                    .forEach(
                        e -> System.out.println(
                            "   " + e.getValue() + " 条 -> " + key(e.getKey().intValue(), identities)));
            } else if (mode.equals("--handler")) {
                int shown = 0;
                for (int o = 0; o < recipeCount; o++) {
                    int slot = Math.max(0, Math.min(handlers - 1, handlerSlot[o]));
                    // 按「标签」（handlerClass|recipeMap，纯 ASCII）匹配：中文参数在 Windows 控制台上
                    // 会被按系统编码解码，传进来就乱了，ASCII 标签没有这个问题
                    if (!handlerTags.get(slot)
                        .contains(value)) continue;
                    System.out.println(describe(o, handlerSlot, handlerNames, primaryResult, primaryAmount, identities));
                    System.out.println("      材料: " + list(ingId, ingOffset, o, ingAmount, identities));
                    if (++shown >= 40) {
                        System.out.println("      …（只列前 40 条）");
                        break;
                    }
                }
                if (shown == 0) System.out.println("（没有标签含「" + value + "」的配方）");
            }
        }
    }

    private static String describe(int o, int[] handlerSlot, List<String> handlerNames, int[] primaryResult,
        int[] primaryAmount, List<String> identities) {
        String handler = handlerNames.get(Math.max(0, Math.min(handlerNames.size() - 1, handlerSlot[o])));
        return "  [" + o + "] " + handler
            + "  主产出="
            + key(primaryResult[o], identities)
            + " x"
            + primaryAmount[o];
    }

    private static String key(int id, List<String> identities) {
        if (id < 0 || id >= identities.size()) return "<" + id + ">";
        return identities.get(id) + "#" + id;
    }

    private static List<String> list(int[] flat, int[] offsets, int o, int[] amounts, List<String> identities) {
        List<String> out = new ArrayList<>();
        int from = offsets[o];
        int to = offsets[o + 1];
        for (int i = from; i < to && i < flat.length; i++) {
            if (flat[i] < 0) continue;
            out.add(key(flat[i], identities) + (i < amounts.length && amounts[i] > 1 ? " x" + amounts[i] : ""));
        }
        return out;
    }

    private static boolean contains(int[] flat, int[] range, int want) {
        for (int i = range[0]; i < range[1] && i < flat.length; i++) {
            if (flat[i] == want) return true;
        }
        return false;
    }

    private static int[] slice(int[] offsets, int o) {
        return new int[] { offsets[o], offsets[o + 1] };
    }

    private static int[] readInts(DataInputStream in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0 || length > (1 << 26)) throw new java.io.IOException("数组长度异常: " + length);
        int[] out = new int[length];
        for (int i = 0; i < length; i++) out[i] = in.readInt();
        return out;
    }

    private static short[] readShorts(DataInputStream in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0 || length > (1 << 26)) throw new java.io.IOException("数组长度异常: " + length);
        short[] out = new short[length];
        for (int i = 0; i < length; i++) out[i] = in.readShort();
        return out;
    }
}
