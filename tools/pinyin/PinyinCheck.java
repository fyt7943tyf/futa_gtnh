import com.futa_gtnh.client.Pinyin;

/**
 * 手工验证 {@code Pinyin.searchSuffix}：把真实资源文件喂给真实实现，打印结果人工核对。
 *
 * <p>
 * <b>不属于构建流程</b>（本项目没有单测基建，为这一个类引一套 JUnit 不划算）。
 * 在改过拼音表、或者动过 {@code Pinyin} 的解析逻辑之后跑一下：
 *
 * <pre>
 *   # 0. 先构建一次，让 build/classes/java/main 里有 Pinyin.class
 *   gradlew build
 *
 *   # 1. 编译桩件（只有 FutaGtnhMod.LOG，省得把整套 Forge 拖进 classpath）
 *   javac -d .tmpdata/stubclasses -cp &lt;log4j-api.jar&gt; tools/pinyin/stub/com/futa_gtnh/FutaGtnhMod.java
 *
 *   # 2. 编译并运行
 *   javac -encoding UTF-8 -d .tmpdata/testclasses -cp ".tmpdata/stubclasses;build/classes/java/main;src/main/resources;&lt;log4j-api.jar&gt;" tools/pinyin/PinyinCheck.java
 *   java -cp ".tmpdata/testclasses;.tmpdata/stubclasses;build/classes/java/main;src/main/resources;&lt;log4j-api.jar&gt;" PinyinCheck
 * </pre>
 *
 * <p>
 * 桩件必须排在 {@code build/classes/java/main} <b>之前</b>，否则会加载到真实的
 * {@code FutaGtnhMod} 然后因为找不到 Forge 而炸掉。
 *
 * <p>
 * 挑的测试词是有讲究的：铱、锇、钨这些化学元素名不在常用字表里，
 * 而 GTNH 的物品名里全是它们 —— 只收常用字的拼音表会在这里露馅。
 */
public class PinyinCheck {

    private static int failures;

    public static void main(String[] args) {
        expect("铁锭", "tieding", "td");
        expect("铁矿石", "tiekuangshi", "tks");
        expect("锰锭", "mengding", "md");
        expect("女巫之角", "nvwuzhijiao", "nwzj");
        expect("铱锭", "yiding", "yd");
        expect("锇锭", "eding", "ed");
        expect("钨钢", "wugang", "wg");
        expect("压缩机", "yasuoji", "ysj");
        expect("电路板", "dianluban", "dlb");
        expect("下界之星", "xiajiezHixing".toLowerCase(), "xjzx");
        expect("离心机", "lixinji", "lxj");
        expect("扳手", "banshou", "bs");
        expect("柴油发电机", "chaiyoufadianji", "cyfdj");

        // 名字里没有汉字时必须返回空串，不能白白加一堆东西进去
        empty("Iron Ingot");
        empty("");
        empty(null);
        // 混合：英文照旧，汉字部分出拼音
        contains("Iron 锭", "ding", "d");

        System.out.println();
        System.out.println(failures == 0 ? "ALL OK" : (failures + " FAILURES"));
        if (failures != 0) System.exit(1);
    }

    private static void expect(String name, String full, String initials) {
        String suffix = Pinyin.searchSuffix(name);
        boolean ok = suffix.contains(full) && suffix.contains(" " + initials + " ")
            || suffix.endsWith(" " + initials);
        // 首字母在末尾时没有尾随空格，单独兜一下
        if (!ok) ok = suffix.contains(full) && suffix.contains(initials);
        report(ok, name, suffix, "full=" + full + " initials=" + initials);
    }

    private static void empty(String name) {
        String suffix = Pinyin.searchSuffix(name);
        report(suffix.isEmpty(), String.valueOf(name), suffix, "expected empty");
    }

    private static void contains(String name, String needle, String note) {
        String suffix = Pinyin.searchSuffix(name);
        report(suffix.contains(needle), name, suffix, "expected to contain " + needle + " (" + note + ")");
    }

    private static void report(boolean ok, String name, String actual, String expected) {
        if (!ok) failures++;
        System.out.printf("%-5s %-10s -> %-40s (%s)%n", ok ? "ok" : "FAIL", name, actual, expected);
    }
}
