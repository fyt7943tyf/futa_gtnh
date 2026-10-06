// 读网页配方索引的磁盘缓存，只解出「物品 id -> 身份键」这一段。
//
// 为什么要这个工具：id 是本次会话的目录序号，跨会话会变，只有键是稳定的；
// 而「同一种流体出现两个 id」这类问题，光看网页上的名字分不出是哪个注册名的物品，
// 必须把键打出来。
//
// 用法：java DumpIndexKeys.java <web_recipes.dat> [要查的 id，逗号分隔] [过滤子串]
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class DumpIndexKeys {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: java DumpIndexKeys.java <web_recipes.dat> [ids] [filter]");
            return;
        }
        File file = new File(args[0]);
        String wantIds = args.length > 1 ? args[1] : "";
        String filter = args.length > 2 ? args[2] : "";

        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            int magic = in.readInt();
            int version = in.readInt();
            String fingerprint = in.readUTF();
            int handlers = in.readInt();
            for (int i = 0; i < handlers; i++) {
                in.readUTF();
                in.readUTF();
                in.readUTF();
            }
            int total = in.readInt();
            List<String> keys = new ArrayList<>(total);
            List<String> stacks = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                // v13 起每个条目存两份键：先「注册名@meta」（用来找回物品），再身份键。
                // v12 及更早只有身份键一份。
                String first = in.readUTF();
                if (version >= 13) {
                    stacks.add(first);
                    keys.add(in.readUTF());
                } else {
                    stacks.add("");
                    keys.add(first);
                }
            }

            System.out.println(
                "magic=" + Integer.toHexString(magic)
                    + " version="
                    + version
                    + " handlers="
                    + handlers
                    + " items="
                    + total);
            System.out.println("fingerprint=" + fingerprint);

            if (!wantIds.isEmpty()) {
                System.out.println("--- 指定 id ---");
                for (String part : wantIds.split(",")) {
                    part = part.trim();
                    if (part.isEmpty()) continue;
                    int id = Integer.parseInt(part);
                    System.out.println(
                        "  id=" + id
                            + "  identity="
                            + (id >= 0 && id < total ? keys.get(id) : "<越界>")
                            + (version >= 13 && id >= 0 && id < total ? "  stack=" + stacks.get(id) : ""));
                }
            }

            if (!filter.isEmpty()) {
                System.out.println("--- 键里含「" + filter + "」的条目 ---");
                int shown = 0;
                for (int i = 0; i < total; i++) {
                    if (keys.get(i)
                        .contains(filter)
                        || stacks.get(i)
                            .contains(filter)) {
                        System.out.println(
                            "  id=" + i
                                + "  identity="
                                + keys.get(i)
                                + (version >= 13 ? "  stack=" + stacks.get(i) : ""));
                        if (++shown >= 40) {
                            System.out.println("  …（只列前 40 条）");
                            break;
                        }
                    }
                }
                if (shown == 0) System.out.println("  （没有）");
            }

            Map<String, List<Integer>> byKey = new LinkedHashMap<>();
            for (int i = 0; i < total; i++) byKey.computeIfAbsent(keys.get(i), k -> new ArrayList<>())
                .add(i);
            int dups = 0;
            StringBuilder dupText = new StringBuilder();
            int fluidKeys = 0;
            for (Map.Entry<String, List<Integer>> e : byKey.entrySet()) {
                if (e.getKey()
                    .startsWith("fluid:")) fluidKeys++;
                if (e.getValue()
                    .size() > 1) {
                    dups++;
                    if (dups <= 20) dupText.append("  ")
                        .append(e.getKey())
                        .append(" -> ")
                        .append(e.getValue())
                        .append('\n');
                }
            }
            System.out.println("--- 汇总 ---");
            System.out.println(
                "  条目 " + total
                    + " 个；不同身份 " + byKey.size()
                    + " 个；fluid: 身份 "
                    + fluidKeys
                    + " 个；同身份的重复条目（编号保留、指向第一个） " + dups
                    + " 个");
            if (dups > 0) System.out.print(dupText);
        }
    }
}
