package com.futa_gtnh.spacecenter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import com.futa_gtnh.block.BlockSpaceCenterPart;
import com.futa_gtnh.spacecenter.SpaceCenterMachine.OreChunk;

/**
 * 虚拟采矿场 / 流体钻机状态机的回归检查：下潜、开采、缺料暂停、读档续挖、纪元重置、
 * 区域推进与流体口径。
 *
 * <p>
 * 状态机只依赖 {@link MachineHost} 和 NBT，材料对它是不透明句柄，所以这里用一个内存假宿主
 * 直接驱动：不起游戏、不初始化 GT 的世界生成与材料表。{@link SpaceCenterMachine} 的数值口径
 * （EU/t、周期、区域）也在这里钉死。
 *
 * <p>
 * 这类检查针对的是编译器看不出来的状态机毛病：下潜到底之后原地打转只烧管道、读档后把
 * 挖过的矿再发一遍、手动重置后先把旧纪元的队列挖完……
 */
public final class SpaceCenterMachinesRegression {

    private static int checks;

    /** 推进到条件满足为止时的 tick 上限（防止状态机卡死时检查挂住）。 */
    private static final int TICK_LIMIT = 2_000_000;

    private SpaceCenterMachinesRegression() {}

    /** 内存假宿主：矿脉表按 "era:cx:cz" 给，油田表按 "cx:cz" → 流体给，资源可设上限。 */
    private static final class FakeHost implements MachineHost {

        final Map<String, OreChunk> ores = new HashMap<>();
        final Map<String, Map<String, Integer>> oil = new HashMap<>();
        String fluid = "fluid.a";
        boolean autoReset = true;
        boolean unknownPlanet;
        double factor = 0.8D;
        /** 剩余资源；Integer.MAX_VALUE = 管够。 */
        int pipes = Integer.MAX_VALUE;
        int drillingFluid = Integer.MAX_VALUE;
        int pipesUsed;
        int drillingFluidUsed;
        /** 挖出来的矿石（按顺序，记的是条目名）。 */
        final List<String> mined = new ArrayList<>();
        /** 钻机交付的流体 "fluid:amount"。 */
        final List<String> emittedOil = new ArrayList<>();

        @Override
        public OreChunk simulateChunk(String planet, int era, int chunkX, int chunkZ) {
            if (unknownPlanet) return null;
            return ores.get(era + ":" + chunkX + ":" + chunkZ);
        }

        @Override
        public int oilUpperBound(String planet, String fluidKey, int chunkX, int chunkZ) {
            if (unknownPlanet) return -1;
            Map<String, Integer> chunk = oil.get(chunkX + ":" + chunkZ);
            Integer bound = chunk == null ? null : chunk.get(fluidKey);
            return bound == null ? 0 : bound;
        }

        @Override
        public String drillingFluid(String planet) {
            return fluid;
        }

        @Override
        public boolean autoResetVein() {
            return autoReset;
        }

        @Override
        public double oilYieldFactor() {
            return factor;
        }

        @Override
        public boolean consumeMiningPipe() {
            if (pipes <= 0) return false;
            pipes--;
            pipesUsed++;
            return true;
        }

        @Override
        public boolean consumeDrillingFluid() {
            if (drillingFluid < 2000) return false;
            drillingFluid -= 2000;
            drillingFluidUsed += 2000;
            return true;
        }

        @Override
        public void emitOre(OreChunk.Entry ore) {
            mined.add(ore.name);
        }

        @Override
        public void emitOil(String planet, int amount) {
            emittedOil.add(fluid + ":" + amount);
        }
    }

    /** 一类矿石的条目：材料留空（状态机不读它），靠名字核对顺序。 */
    private static OreChunk.Entry ore(String name, int count, boolean small) {
        return new OreChunk.Entry(null, name, count, small);
    }

    /**
     * 一个区块的矿石：3 个 A、2 个 B（矿脉）+ 1 个 C（小矿），矿脉最低层 Y=40。
     * 条目顺序就是状态机该消费的顺序（真实宿主按材料 id 排好后交进来）。
     */
    private static OreChunk standardOre() {
        return new OreChunk(
            40,
            Arrays.asList(ore("A", 3, false), ore("B", 2, false), ore("C", 1, true)));
    }

    private static void fill(FakeHost host, int era, OreChunk ore) {
        for (int cx = -64; cx <= 64; cx++) {
            for (int cz = -64; cz <= 64; cz++) {
                host.ores.put(era + ":" + cx + ":" + cz, ore);
            }
        }
    }

    private static Map<String, Integer> fluids(Object... keyAndValue) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < keyAndValue.length; i += 2) {
            map.put((String) keyAndValue[i], (Integer) keyAndValue[i + 1]);
        }
        return map;
    }

    /** 推进一个批次（不超过 20t），批次开头照指挥中心的做法清一遍软暂停。 */
    private static void step(SpaceCenterMachine machine, FakeHost host, int ticks) {
        // 缺料的会再次置位；「区域采完」是硬停不动
        if (machine.pauseReason != null && !SpaceCenterMachine.PAUSE_AREA_EXHAUSTED.equals(machine.pauseReason)) {
            machine.pauseReason = null;
        }
        machine.advance(ticks, host);
    }

    /** 推进指定 tick 数（按 20t 批次，最后一个批次带零头）。 */
    private static void run(SpaceCenterMachine machine, int ticks, FakeHost host) {
        for (int left = ticks; left > 0; left -= 20) {
            step(machine, host, Math.min(20, left));
        }
    }

    /** 按批次推进到条件满足；超时视为状态机卡死。 */
    private static void runUntil(SpaceCenterMachine machine, FakeHost host, Predicate<SpaceCenterMachine> done) {
        for (int ticks = 0; ticks < TICK_LIMIT; ticks += 20) {
            if (done.test(machine)) return;
            step(machine, host, 20);
        }
        throw new IllegalStateException("FAILED: state machine never settled (stuck?)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("FAILED: " + message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        tierNumbersMatchSpec();
        structureDefinitionBuilds();
        effectsGeometryMatchesStructure();
        langPlaceholdersAreConsistent();
        heightResolutionMatchesStructure();
        minerDescendsThenMines();
        minerMissingPipePausesWithoutProgress();
        minerMissingFluidRetriesSameOre();
        minerSaveLoadResumesWithoutRemining();
        minerResetDropsOldEraQueue();
        minerAdvancesThroughAreaAndResetsEra();
        minerUnknownPlanetPauses();
        drillerDescendsThenPumpsSelectedFluidOnly();
        System.out.println("Space center machine regression: " + checks + " checks passed");
    }

    // ------------------------------------------------------------------

    private static void tierNumbersMatchSpec() {
        long[] minerEu = { 48, 192, 768, 3072 };
        int[] minerCycle = { 240, 100, 40, 15 };
        int[] minerSide = { 6, 8, 12, 18 };
        long[] drillerEu = { 112, 448, 1792, 7168 };
        int[] drillerDescendCycle = { 30, 15, 7, 3 };
        for (int tier = 1; tier <= 4; tier++) {
            check(SpaceCenterMachine.minerEu(tier) == minerEu[tier - 1], "miner EU/t T" + tier);
            check(SpaceCenterMachine.minerCycle(tier) == minerCycle[tier - 1], "miner cycle T" + tier);
            check(SpaceCenterMachine.minerAreaSide(tier) == minerSide[tier - 1], "miner area T" + tier);
            check(SpaceCenterMachine.drillerEu(tier) == drillerEu[tier - 1], "driller EU/t T" + tier);

            SpaceCenterMiner miner = new SpaceCenterMiner(tier, "moon");
            check(miner.euPerTick() == minerEu[tier - 1] && miner.cycleTicks() == minerCycle[tier - 1],
                "miner instance numbers T" + tier);

            SpaceCenterDriller driller = new SpaceCenterDriller(tier, "moon");
            check(driller.euPerTick() == drillerEu[tier - 1] && driller.cycleTicks() == drillerDescendCycle[tier - 1],
                "driller descending cycle T" + tier);
            driller.descendRemaining = 0;
            check(driller.cycleTicks() == 8, "driller bottom cycle T" + tier);
        }
    }

    /**
     * 结构定义必须能装配，而且形状里的每一格都进了元素表。
     *
     * <p>
     * 2.1.0 的中间层用 '.' 表示空气却没有绑定元素，StructureLib 在 build() 时抛
     * Missing Structure Element bindings。这个构建是惰性的：编译、注册、服务端冒烟全碰不到，
     * 直到玩家在 NEI 里按 R 看多方块预览、或者第一次成型检查才崩。所以这里拿真实的
     * StructureLib，把三个 piece 完整装配一遍（元素本体换成哑元），按元素实例反查
     * 「哪个字符落进了元素表、落了几格」，并钉住形状里的硬性约定。
     */
    private static void structureDefinitionBuilds() {
        check(
            SpaceCenterStructure.kindOf(SpaceCenterStructure.AIR) == SpaceCenterStructure.Kind.AIR,
            "air is a registered structure element");

        // 每个字符一个独立的哑元素：装配后再按实例反查字符。
        // isAir() 是单例，直接用的话五个字符会共用一个实例，所以用 ofChain 各包一层拿到新实例
        Map<IStructureElement<Object>, Character> byElement = new HashMap<>();
        IStructureDefinition<Object> definition = SpaceCenterStructure.build(kind -> {
            IStructureElement<Object> element = StructureUtility.ofChain(StructureUtility.isAir());
            for (char c : new char[] { SpaceCenterStructure.TRUSS, SpaceCenterStructure.HOLO,
                SpaceCenterStructure.ANTENNA, SpaceCenterStructure.AIR, SpaceCenterStructure.HATCH }) {
                if (SpaceCenterStructure.kindOf(c) == kind) byElement.put(element, c);
            }
            return element;
        });

        // 底座 7×7：25 个桁架 + 23 个可贴仓/口的位置（第 24 个被控制器占着）
        check(
            cells(definition, SpaceCenterStructure.PIECE_BASE, byElement).equals(cellsOf("C=25", "H=23")),
            "base piece cells: " + cells(definition, SpaceCenterStructure.PIECE_BASE, byElement));
        // 中间层 7×7：24 个桁架 + 24 格空气 + 正中 1 个全息核心（2.1.0 崩的就是这 24 格空气）
        check(
            cells(definition, SpaceCenterStructure.PIECE_LAYER, byElement).equals(cellsOf("C=24", ".=24", "K=1")),
            "layer piece cells: " + cells(definition, SpaceCenterStructure.PIECE_LAYER, byElement));
        // 顶盖 7×7：24 个桁架 + 内圈 8 个全息核心 + 外圈 16 根天线 + 正中 1 根天线
        check(
            cells(definition, SpaceCenterStructure.PIECE_TOP, byElement).equals(cellsOf("C=24", "K=8", "A=17")),
            "top piece cells: " + cells(definition, SpaceCenterStructure.PIECE_TOP, byElement));

        // 形状约定：三个 piece 都是 7×7 的单层，共用同一套水平 footprint；
        // 控制器只有一个，在底座最前排的正中（= 偏移量）
        for (String[][] shape : new String[][][] { SpaceCenterStructure.BASE, SpaceCenterStructure.LAYER,
            SpaceCenterStructure.TOP }) {
            check(shape.length == 1 && shape[0].length == 7, "each piece is a single 7-deep level");
            for (String row : shape[0]) check(row.length() == 7, "every row is 7 wide");
        }
        check(
            countChar(SpaceCenterStructure.BASE, SpaceCenterStructure.CONTROLLER) == 1
                && countChar(SpaceCenterStructure.LAYER, SpaceCenterStructure.CONTROLLER) == 0
                && countChar(SpaceCenterStructure.TOP, SpaceCenterStructure.CONTROLLER) == 0,
            "exactly one controller in the structure");
        check(
            SpaceCenterStructure.BASE[0][SpaceCenterStructure.DEPTH_OFFSET]
                .charAt(SpaceCenterStructure.HORIZONTAL_OFFSET) == SpaceCenterStructure.CONTROLLER,
            "controller sits at (HORIZONTAL_OFFSET, DEPTH_OFFSET)");

        // 位置约定：控制器在前缘，全息核心在底座中心（控制器往后 3 格）——特效的锚点扫描靠这一条；
        // 中间层内部除它以外全是空气；顶盖中心是天线，外面一圈全息核心
        int center = SpaceCenterStructure.WIDTH / 2;
        check(center - SpaceCenterStructure.DEPTH_OFFSET == 3, "center is 3 blocks behind the controller");
        for (int row = 1; row < SpaceCenterStructure.WIDTH - 1; row++) {
            for (int col = 1; col < SpaceCenterStructure.WIDTH - 1; col++) {
                int ring = Math.max(Math.abs(row - center), Math.abs(col - center));
                check(
                    SpaceCenterStructure.LAYER[0][row].charAt(col) == (ring == 0 ? SpaceCenterStructure.HOLO
                        : SpaceCenterStructure.AIR),
                    "layer interior at (" + row + "," + col + ")");
                check(
                    SpaceCenterStructure.TOP[0][row].charAt(col) == (ring == 1 ? SpaceCenterStructure.HOLO
                        : SpaceCenterStructure.ANTENNA),
                    "top interior at (" + row + "," + col + ")");
            }
        }

        // 数量口径：最矮 49 个桁架、每多一层 +24
        check(SpaceCenterStructure.TRUSS_MIN == 49, "truss count of the 2-layer structure");
        check(SpaceCenterStructure.TRUSS_PER_LAYER == 24, "truss per extra layer");
    }

    /**
     * 一个 piece 的元素表里，按字符统计的格数。
     *
     * <p>
     * 元素表里除了形状的格子，还有 StructureLib 自己挂的条目（控制器、以及每块固定带着的几个内部元素），
     * 它们不在反查表里、不参与统计——这里只回答「形状里的每个格子是不是都带着正确的元素进了表」。
     */
    private static Map<Character, Integer> cells(IStructureDefinition<Object> definition, String piece,
        Map<IStructureElement<Object>, Character> byElement) {
        Map<Character, Integer> counts = new TreeMap<>();
        for (IStructureElement<Object> cell : definition.getStructureFor(piece)) {
            Character c = cell == null ? null : byElement.get(cell);
            if (c != null) counts.merge(c, 1, Integer::sum);
        }
        return counts;
    }

    /** {"C=25", "H=24"} → {'C': 25, 'H': 24} */
    private static Map<Character, Integer> cellsOf(String... entries) {
        Map<Character, Integer> counts = new TreeMap<>();
        for (String entry : entries) counts.put(entry.charAt(0), Integer.parseInt(entry.substring(2)));
        return counts;
    }

    private static int countChar(String[][] shape, char c) {
        int n = 0;
        for (String[] level : shape) {
            for (String row : level) n += countChar(row, c);
        }
        return n;
    }

    private static int countChar(String row, char c) {
        int n = 0;
        for (int i = 0; i < row.length(); i++) {
            if (row.charAt(i) == c) n++;
        }
        return n;
    }

    /**
     * 特效的锚点扫描（{@link SpaceCenterGeometry}）必须和真实形状对上。
     *
     * <p>
     * 用形状字符串在内存里搭出真机网格：控制器在 (0,0,0)、结构沿背向展开，四个朝向 ×
     * 两种高度都摸一遍，'H' 槽位「全摆桁架」和「摆的是仓（摸出来是别的方块）」各来一遍，
     * 钉住背向、结构中心列、顶盖 y、全息核心与天线的数量和具体位置。
     *
     * <p>
     * 2.1.0 的特效把锚点钉在控制器正上方——网格里那一列往上只有桁架，扫描会直接摸空
     * 返回 null，这里的断言一条也过不去；所以它是那次「特效全都没了」的回归门。
     */
    private static void effectsGeometryMatchesStructure() {
        for (int[] dir : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
            SpaceCenterGeometry.Anchors anchors = SpaceCenterGeometry.scan(grid(dir, 4, true), 0, 0, 0);
            String where = "grid facing " + dir[0] + "," + dir[1];
            check(anchors != null, "scan finds anchors: " + where);
            check(anchors.backX == dir[0] && anchors.backZ == dir[1], "back direction of " + where);
            check(anchors.centerX == 3 * dir[0] && anchors.centerZ == 3 * dir[1], "center column of " + where);
            check(anchors.topY == 3, "top y of " + where);
            check(anchors.holo.size() == 2 + 8, "holo cores of " + where + ": " + anchors.holo.size());
            check(anchors.antennas.size() == 17, "antennas of " + where + ": " + anchors.antennas.size());
        }

        // 具体位置：中心列的全息核心逐层在 (3,1,0)、(3,2,0)；顶盖中心天线在 (3,3,0)
        SpaceCenterGeometry.Anchors anchors = SpaceCenterGeometry.scan(grid(new int[] { 1, 0 }, 4, true), 0, 0, 0);
        check(has(anchors.holo, 3, 1, 0) && has(anchors.holo, 3, 2, 0), "column holo cores at (3,1,0) and (3,2,0)");
        check(has(anchors.antennas, 3, 3, 0), "top-center antenna at (3,3,0)");

        // 最矮的两层机（没有中间层）：中心列直接顶到顶盖
        SpaceCenterGeometry.Anchors minimal = SpaceCenterGeometry.scan(grid(new int[] { 1, 0 }, 2, true), 0, 0, 0);
        check(minimal != null && minimal.topY == 1, "two-layer machine scans to top y 1");
        check(minimal.holo.size() == 8, "two-layer machine has only the top holo ring");

        // 'H' 槽位摆的是仓（摸出来不是桁架）也照样能摸：背向判定不依赖那圈
        SpaceCenterGeometry.Anchors hatched = SpaceCenterGeometry.scan(grid(new int[] { 1, 0 }, 4, false), 0, 0, 0);
        check(hatched != null && hatched.backX == 1 && hatched.topY == 3, "scan works with hatch-filled base edges");

        // 摸不到就该闭嘴：空世界、没盖顶盖、顶盖正中缺天线，全都返回 null
        check(SpaceCenterGeometry.scan((x, y, z) -> -1, 0, 0, 0) == null, "scan of an empty world is null");
        SpaceCenterGeometry.PartMetaLookup withoutTop = (wx, wy, wz) -> wy >= 3
            ? -1
            : grid(new int[] { 1, 0 }, 4, true).metaAt(wx, wy, wz);
        check(SpaceCenterGeometry.scan(withoutTop, 0, 0, 0) == null, "scan without the top layer is null");
        SpaceCenterGeometry.PartMetaLookup brokenTopCenter = (wx, wy, wz) -> wx == 3 && wy == 3 && wz == 0
            ? BlockSpaceCenterPart.META_TRUSS
            : grid(new int[] { 1, 0 }, 4, true).metaAt(wx, wy, wz);
        check(SpaceCenterGeometry.scan(brokenTopCenter, 0, 0, 0) == null, "scan without the top-center antenna is null");
    }

    /**
     * 按真实形状在内存里搭一台指挥中心：控制器在 (0,0,0)，背向 = dir（结构往 dir 方向展开）。
     *
     * <p>
     * 世界坐标 → 形状坐标的反变换用两个正交的单位向量内积搞定：
     * 行（纵深）= 沿背向的投影，列（水平）= 沿侧向的投影 + 中心列偏移。
     * '~' 是控制器本体、'.' 是空气、底座边缘的 'H' 槽位看 {@code hatchSlotsAreTruss}，
     * 摸出来都不是桁架之外的形态方块。
     */
    private static SpaceCenterGeometry.PartMetaLookup grid(int[] dir, int height, boolean hatchSlotsAreTruss) {
        int dx = dir[0], dz = dir[1];
        return (wx, wy, wz) -> {
            int row = wx * dx + wz * dz;
            int col = -wx * dz + wz * dx + SpaceCenterStructure.WIDTH / 2;
            if (row < 0 || row >= SpaceCenterStructure.WIDTH || col < 0 || col >= SpaceCenterStructure.WIDTH || wy < 0
                || wy >= height) return -1;
            char c;
            if (wy == 0) c = SpaceCenterStructure.BASE[0][row].charAt(col);
            else if (wy == height - 1) c = SpaceCenterStructure.TOP[0][row].charAt(col);
            else c = SpaceCenterStructure.LAYER[0][row].charAt(col);
            switch (c) {
                case SpaceCenterStructure.TRUSS:
                    return BlockSpaceCenterPart.META_TRUSS;
                case SpaceCenterStructure.HOLO:
                    return BlockSpaceCenterPart.META_HOLO_CORE;
                case SpaceCenterStructure.ANTENNA:
                    return BlockSpaceCenterPart.META_ANTENNA;
                case SpaceCenterStructure.HATCH:
                    return hatchSlotsAreTruss ? BlockSpaceCenterPart.META_TRUSS : -1;
                default:
                    return -1; // '~' 是控制器本体、'.' 是空气，都不是形态方块组
            }
        };
    }

    private static boolean has(List<int[]> list, int x, int y, int z) {
        for (int[] p : list) {
            if (p[0] == x && p[1] == y && p[2] == z) return true;
        }
        return false;
    }

    private static final String LANG_DIR = "src/main/resources/assets/futa_gtnh/lang/";
    private static final String LANG_PREFIX = "futa_gtnh.space_center.";
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?s");

    /**
     * 语言文件的占位符必须和代码传参对得上。
     *
     * <p>
     * 带参数的词条靠 String.format 填值，占位符比参数多就是 MissingFormatArgumentException，
     * 而它跑在界面的重绘里——2.1.0 钻机那一行的 tooltip 就是这么悬停即崩的。
     * SpaceCenterGui 里现在对格式化失败做了兜底（不再崩），但兜底只是难看，不该当成正常：
     * 这里按调用点把参数个数钉死，并要求中英文词条的占位符个数一致。
     */
    private static void langPlaceholdersAreConsistent() throws Exception {
        Map<String, String> zh = loadLang("zh_CN.lang");
        Map<String, String> en = loadLang("en_US.lang");
        check(zh.keySet().equals(en.keySet()), "zh/en key sets are identical");
        check(zh.size() > 60, "lang files were actually loaded: " + zh.size());

        // 调用点 → 参数个数（与 SpaceCenterGui 里的 tr(...) 调用一一对应）
        Map<String, Integer> argCounts = new LinkedHashMap<>();
        argCounts.put("gui.status.0", 4);
        argCounts.put("gui.status.1", 2);
        argCounts.put("gui.page", 3);
        argCounts.put("gui.selected", 2);
        argCounts.put("gui.locked", 1);
        argCounts.put("gui.more_veins", 1);
        argCounts.put("gui.tt.miner", 5);
        argCounts.put("gui.tt.driller", 5);
        argCounts.put("gui.more_machines", 1);
        for (Map.Entry<String, Integer> entry : argCounts.entrySet()) {
            String key = LANG_PREFIX + entry.getKey();
            check(placeholders(zh.get(key)) == entry.getValue(), "zh placeholders of " + entry.getKey());
            check(placeholders(en.get(key)) == entry.getValue(), "en placeholders of " + entry.getKey());
        }
        // 其余词条不带参数：新加了带占位符的词条就要同步登记到上面的表里，别让调用点漏掉
        for (String key : zh.keySet()) {
            if (argCounts.containsKey(key.substring(LANG_PREFIX.length()))) continue;
            int zhCount = placeholders(zh.get(key));
            int enCount = placeholders(en.get(key));
            check(zhCount == enCount, "zh/en placeholder parity of " + key);
            check(zhCount == 0, "formatted entry is not registered in the arity table: " + key);
        }
    }

    private static int placeholders(String value) {
        if (value == null) return -1;
        int n = 0;
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) n++;
        return n;
    }

    /** 读 .lang：跳过空行与 # 注释，按第一个 = 切开。 */
    private static Map<String, String> loadLang(String name) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(new File(LANG_DIR + name).toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            String key = trimmed.substring(0, eq).trim();
            if (key.startsWith(LANG_PREFIX)) entries.put(key, trimmed.substring(eq + 1));
        }
        return entries;
    }

    /** 多方块的层数判定：用「底座上方第 n 格是什么」的谓词模拟各种搭法。 */
    private static void heightResolutionMatchesStructure() {
        for (int total = 2; total <= 9; total++) {
            final int built = total;
            Predicate<Integer> top = n -> n == built - 1;
            Predicate<Integer> layer = n -> n >= 1 && n < built - 1;
            check(
                SpaceCenterMachine.resolveHeight(2, 9, top::test, layer::test) == built,
                "height of a " + built + "-layer structure");
        }
        // 底座 + 7 层中间层、没有顶盖：到了层数上限也不能算成型
        //（旧逻辑是「height < max 才循环」，到上限直接放行，残缺结构会算成 9 层）
        check(
            SpaceCenterMachine.resolveHeight(2, 9, n -> false, n -> n >= 1 && n <= 7) == -1,
            "missing top at the max height is rejected");
        check(SpaceCenterMachine.resolveHeight(2, 9, n -> false, n -> false) == -1, "base only is rejected");
        // 3 层，但顶盖该在的位置上是别的东西
        check(SpaceCenterMachine.resolveHeight(2, 9, n -> false, n -> n == 1) == -1, "junk where the top should be is rejected");
        // 配置把上限收到 4 层
        check(
            SpaceCenterMachine.resolveHeight(2, 4, n -> n == 4, n -> n >= 1 && n <= 3) == -1,
            "taller than the configured max is rejected");
        check(SpaceCenterMachine.resolveHeight(2, 4, n -> n == 3, n -> n >= 1 && n <= 2) == 4, "exactly the configured max is accepted");
    }

    /** 下潜阶段只耗管道不产矿；到底之后每个周期恰好 1 矿 + 2000mB，且绝不原地打转。 */
    private static void minerDescendsThenMines() {
        FakeHost host = new FakeHost();
        fill(host, 0, standardOre());
        SpaceCenterMiner miner = new SpaceCenterMiner(1, "moon");
        int cycle = miner.cycleTicks();
        int descend = 40 - 8; // veinMinY - 8

        check(miner.descendRemaining == 0, "new miner has not computed depth yet");
        run(miner, descend * cycle - 1, host);
        check(host.pipesUsed == descend - 1, "descending consumes one pipe per cycle: " + host.pipesUsed);
        check(host.mined.isEmpty(), "no ore while descending");

        run(miner, cycle, host);
        check(host.pipesUsed == descend, "last pipe of the descent");
        check(host.mined.isEmpty(), "still no ore on the cycle that finishes the descent");

        run(miner, cycle * 6, host);
        check(host.pipesUsed == descend, "pipes stop being consumed once at the bottom");
        check(
            host.mined.equals(Arrays.asList("A", "A", "A", "B", "B", "C")),
            "6 ores in entry order: " + host.mined);
        check(host.drillingFluidUsed == 6 * 2000, "2000mB per ore: " + host.drillingFluidUsed);
        check(miner.remainingBlocks() == 0, "chunk queue drained");

        // 队列空了才换下一块，并重新下潜
        run(miner, cycle, host);
        check(miner.chunkIndex == 1, "advances to the next chunk: " + miner.chunkIndex);
        run(miner, cycle * descend, host);
        check(host.pipesUsed == descend * 2, "second chunk descends again: " + host.pipesUsed);
        check(host.mined.size() == 6, "second chunk has not been mined yet");
    }

    private static void minerMissingPipePausesWithoutProgress() {
        FakeHost host = new FakeHost();
        fill(host, 0, standardOre());
        host.pipes = 5;
        SpaceCenterMiner miner = new SpaceCenterMiner(1, "moon");
        runUntil(miner, host, m -> m.pauseReason != null);
        check(host.pipesUsed == 5, "stops at the last available pipe: " + host.pipesUsed);
        check(SpaceCenterMachine.PAUSE_NO_MINING_PIPE.equals(miner.pauseReason), "paused for mining pipe");
        check(miner.chunkIndex == 0 && host.mined.isEmpty(), "no progress without pipes");

        host.pipes = 1000;
        runUntil(miner, host, m -> host.mined.size() == 6);
        check(host.pipesUsed == 32, "resumes the descent where it stopped: " + host.pipesUsed);
        check(host.mined.size() == 6, "then mines the whole chunk");
    }

    private static void minerMissingFluidRetriesSameOre() {
        FakeHost host = new FakeHost();
        fill(host, 0, standardOre());
        host.drillingFluid = 2000 * 3;
        SpaceCenterMiner miner = new SpaceCenterMiner(1, "moon");
        runUntil(miner, host, m -> m.pauseReason != null);
        check(host.mined.equals(Arrays.asList("A", "A", "A")), "mines only what the fluid allows: " + host.mined);
        check(SpaceCenterMachine.PAUSE_NO_DRILLING_FLUID.equals(miner.pauseReason), "paused for drilling fluid");

        host.drillingFluid = 2000 * 10;
        runUntil(miner, host, m -> host.mined.size() == 6);
        check(
            host.mined.equals(Arrays.asList("A", "A", "A", "B", "B", "C")),
            "continues with the same ore: " + host.mined);
    }

    /** 读档只记「还剩几个」；重建后必须从剩余的那个继续，既不重挖也不多下潜。 */
    private static void minerSaveLoadResumesWithoutRemining() {
        // 开采到一半存档
        FakeHost first = new FakeHost();
        fill(first, 0, standardOre());
        SpaceCenterMiner miner = new SpaceCenterMiner(1, "moon");
        run(miner, 32 * 240 + 2 * 240 + 100, first);
        check(first.mined.equals(Arrays.asList("A", "A")), "two ores before saving: " + first.mined);
        NBTTagCompound saved = new NBTTagCompound();
        miner.save(saved);

        FakeHost second = new FakeHost();
        fill(second, 0, standardOre());
        SpaceCenterMachine restored = SpaceCenterMachine.create(SpaceCenterMachine.Type.MINER, 1, "moon");
        restored.load(saved);
        runUntil(restored, second, m -> second.mined.size() == 4);
        check(second.pipesUsed == 0, "no extra descent after reload: " + second.pipesUsed);
        check(second.mined.equals(Arrays.asList("A", "B", "B", "C")), "exactly the remaining ores: " + second.mined);

        // 下潜到一半存档：读档后只补完剩下的管道
        FakeHost third = new FakeHost();
        fill(third, 0, standardOre());
        SpaceCenterMiner mid = new SpaceCenterMiner(1, "moon");
        run(mid, 10 * 240, third);
        check(mid.descendRemaining == 22, "half-way down: " + mid.descendRemaining);
        NBTTagCompound midSaved = new NBTTagCompound();
        mid.save(midSaved);
        FakeHost fourth = new FakeHost();
        fill(fourth, 0, standardOre());
        SpaceCenterMachine midRestored = SpaceCenterMachine.create(SpaceCenterMachine.Type.MINER, 1, "moon");
        midRestored.load(midSaved);
        runUntil(midRestored, fourth, m -> fourth.mined.size() == 6);
        check(fourth.pipesUsed == 22, "only the remaining pipes: " + fourth.pipesUsed);
        check(fourth.mined.equals(Arrays.asList("A", "A", "A", "B", "B", "C")), "whole chunk exactly once: " + fourth.mined);

        // 从没推进过就存档：读档后按新区块对待，正常下潜
        FakeHost fifth = new FakeHost();
        fill(fifth, 0, standardOre());
        SpaceCenterMiner fresh = new SpaceCenterMiner(1, "moon");
        NBTTagCompound freshSaved = new NBTTagCompound();
        fresh.save(freshSaved);
        SpaceCenterMachine freshRestored = SpaceCenterMachine.create(SpaceCenterMachine.Type.MINER, 1, "moon");
        freshRestored.load(freshSaved);
        runUntil(freshRestored, fifth, m -> fifth.mined.size() == 6);
        check(fifth.pipesUsed == 32 && fifth.mined.size() == 6, "never-advanced machine descends normally");

        // 越界 / 负数的存档值不能把状态机带崩
        NBTTagCompound corrupt = new NBTTagCompound();
        corrupt.setInteger("era", -7);
        corrupt.setInteger("chunkIndex", 99999);
        corrupt.setInteger("descend", -50);
        corrupt.setInteger("queued", 12345);
        SpaceCenterMachine salvage = SpaceCenterMachine.create(SpaceCenterMachine.Type.MINER, 1, "moon");
        salvage.load(corrupt);
        SpaceCenterMiner salvaged = (SpaceCenterMiner) salvage;
        check(salvaged.era == 0 && salvaged.chunkIndex == 35 && salvaged.descendRemaining == 0,
            "out-of-range NBT is clamped");
        // 记录的剩余数比整块矿石还多：按「一个都没挖过」处理，整块恰好出一遍，不会更多
        FakeHost sixth = new FakeHost();
        fill(sixth, 0, standardOre());
        run(salvage, 5000, sixth);
        check(sixth.mined.equals(Arrays.asList("A", "A", "A", "B", "B", "C")),
            "oversized resume count yields the chunk exactly once: " + sixth.mined);
        check(sixth.pipesUsed == 0, "no descent when the save says it was already at the bottom");
    }

    /** 手动重置后旧纪元的队列必须作废，且新纪元的第 0 块不能被跳过。 */
    private static void minerResetDropsOldEraQueue() {
        FakeHost host = new FakeHost();
        fill(host, 0, standardOre());
        OreChunk newOre = new OreChunk(24, Collections.singletonList(ore("X", 10, false)));
        fill(host, 1, newOre);
        SpaceCenterMiner miner = new SpaceCenterMiner(1, "moon");
        run(miner, 32 * 240 + 2 * 240 + 100, host);
        check(host.mined.equals(Arrays.asList("A", "A")), "two ores before reset: " + host.mined);

        miner.resetVein();
        check(miner.era == 1 && miner.chunkIndex == 0 && miner.remainingBlocks() == 0, "reset state");
        runUntil(miner, host, m -> host.mined.size() == 12);
        check(
            host.mined.subList(2, 12)
                .stream()
                .allMatch("X"::equals),
            "no leftover ore from the old era: " + host.mined);
        check(host.pipesUsed == 32 + 16, "new era descends to its own depth: " + host.pipesUsed);
    }

    private static void minerAdvancesThroughAreaAndResetsEra() {
        // 空区块：下潜 16 根管道，没有任何矿
        OreChunk empty = new OreChunk(Integer.MAX_VALUE, Collections.<OreChunk.Entry>emptyList());
        FakeHost host = new FakeHost();
        fill(host, 0, empty);
        host.autoReset = false;
        SpaceCenterMiner miner = new SpaceCenterMiner(1, "moon");
        runUntil(miner, host, m -> m.pauseReason != null);
        check(miner.chunkIndex == 35, "walked the whole 6x6 area: " + miner.chunkIndex);
        check(SpaceCenterMachine.PAUSE_AREA_EXHAUSTED.equals(miner.pauseReason), "hard stop without auto reset");
        check(host.pipesUsed == 36 * 16, "16 pipes per empty chunk: " + host.pipesUsed);
        run(miner, 5000, host);
        check(host.pipesUsed == 36 * 16 && miner.chunkIndex == 35, "hard stop stays put");

        // 换到新纪元后接着跑（新纪元的矿脉表也要有，否则机器会如实报「星球未知」）
        host.autoReset = true;
        fill(host, 1, empty);
        SpaceCenterMiner auto = new SpaceCenterMiner(1, "moon");
        runUntil(auto, host, m -> ((SpaceCenterMiner) m).era == 1);
        check(auto.chunkIndex == 0 && auto.pauseReason == null, "auto reset wraps to a new era and keeps running");

        // 第 2 级走的是 8×8
        FakeHost wide = new FakeHost();
        fill(wide, 0, empty);
        wide.autoReset = false;
        SpaceCenterMiner tier2 = new SpaceCenterMiner(2, "moon");
        runUntil(tier2, wide, m -> m.pauseReason != null);
        check(tier2.chunkIndex == 63, "T2 walks an 8x8 area: " + tier2.chunkIndex);
    }

    private static void minerUnknownPlanetPauses() {
        FakeHost host = new FakeHost(); // 矿脉表是空的
        SpaceCenterMiner miner = new SpaceCenterMiner(1, "moon");
        runUntil(miner, host, m -> m.pauseReason != null);
        check(SpaceCenterMachine.PAUSE_UNKNOWN_PLANET.equals(miner.pauseReason), "unknown planet pauses");
        check(host.pipesUsed == 0 && host.mined.isEmpty(), "unknown planet burns nothing");

        fill(host, 0, standardOre());
        runUntil(miner, host, m -> host.mined.size() == 6);
        check(host.mined.size() == 6, "recovers once the planet is known");
    }

    private static void drillerDescendsThenPumpsSelectedFluidOnly() {
        FakeHost host = new FakeHost();
        host.oil.put("0:0", fluids("fluid.a", 100, "fluid.b", 500));
        host.oil.put("1:0", fluids("fluid.b", 500));
        SpaceCenterDriller driller = new SpaceCenterDriller(1, "moon"); // T1：工作区 1×1 区块

        runUntil(driller, host, m -> ((SpaceCenterDriller) m).descendRemaining == 1);
        check(host.pipesUsed == 63 && host.emittedOil.isEmpty(), "descending: pipes only");
        runUntil(driller, host, m -> ((SpaceCenterDriller) m).atBottom());
        check(host.pipesUsed == 64, "64 pipes to reach the bottom");
        check(driller.cycleTicks() == 8, "8t cycle at the bottom");

        int before = host.emittedOil.size();
        run(driller, 8 * 10, host);
        check(host.pipesUsed == 64, "no pipes while pumping");
        check(host.emittedOil.size() == before + 10, "one emission per 8t cycle: " + host.emittedOil.size());
        check("fluid.a:80".equals(host.emittedOil.get(host.emittedOil.size() - 1)), "selected fluid x0.8");

        // 只统计所选流体：同一工作区里另一种流体的存量不能被算进来
        for (String emission : host.emittedOil) {
            check(emission.startsWith("fluid.a:"), "no other fluid leaks into the output: " + emission);
        }

        // T2 的工作区是 2×2；选 b 时只累计 b 的存量：(500 + 500) × 0.8
        host.fluid = "fluid.b";
        SpaceCenterDriller other = new SpaceCenterDriller(2, "moon");
        other.descendRemaining = 0;
        run(other, 8 * 3, host);
        check("fluid.b:800".equals(host.emittedOil.get(host.emittedOil.size() - 1)), "b across the 2x2 area x0.8");

        // 配置系数直接作用在产量上
        host.factor = 0.5D;
        SpaceCenterDriller half = new SpaceCenterDriller(2, "moon");
        half.descendRemaining = 0;
        run(half, 8 * 1, host);
        check("fluid.b:500".equals(host.emittedOil.get(host.emittedOil.size() - 1)), "yield factor is applied");

        // 所选流体在工作区里没有油田 → 暂停，什么都不产出
        FakeHost dry = new FakeHost();
        SpaceCenterDriller dryDriller = new SpaceCenterDriller(1, "moon");
        dryDriller.descendRemaining = 0;
        run(dryDriller, 100, dry);
        check(SpaceCenterMachine.PAUSE_NO_OIL_FIELD.equals(dryDriller.pauseReason), "no field pauses");
        check(dry.emittedOil.isEmpty(), "nothing emitted without a field");

        // 星球没有配置任何流体 → 同样按「没有油田」暂停
        dry.fluid = null;
        SpaceCenterDriller unconfigured = new SpaceCenterDriller(1, "moon");
        unconfigured.descendRemaining = 0;
        run(unconfigured, 100, dry);
        check(SpaceCenterMachine.PAUSE_NO_OIL_FIELD.equals(unconfigured.pauseReason), "no fluid configured pauses");

        // 星球未知（查不到油田）→ 另一种暂停。
        // 注意「没有流体」排在它前面：真实宿主对不在星球表里的星球同样先回 null 流体，
        // 那种情况会落到上面的「没有油田」；这里要有流体才会走到查油田这一步
        dry.fluid = "fluid.a";
        dry.unknownPlanet = true;
        SpaceCenterDriller unknown = new SpaceCenterDriller(1, "moon");
        unknown.descendRemaining = 0;
        run(unknown, 100, dry);
        check(SpaceCenterMachine.PAUSE_UNKNOWN_PLANET.equals(unknown.pauseReason), "unknown planet pauses");

        // 下潜缺管道：停在最后一根，补上后接着下
        FakeHost starved = new FakeHost();
        starved.pipes = 10;
        SpaceCenterDriller starving = new SpaceCenterDriller(1, "moon");
        runUntil(starving, starved, m -> m.pauseReason != null);
        check(starved.pipesUsed == 10 && starving.descendRemaining == 54, "driller stops at the last pipe");
        check(SpaceCenterMachine.PAUSE_NO_MINING_PIPE.equals(starving.pauseReason), "driller paused for pipes");
        starved.pipes = 1000;
        runUntil(starving, starved, m -> ((SpaceCenterDriller) m).atBottom());
        check(starved.pipesUsed == 64, "driller finishes the descent after resupply");
    }
}
