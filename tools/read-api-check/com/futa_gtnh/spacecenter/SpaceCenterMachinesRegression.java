package com.futa_gtnh.spacecenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.nbt.NBTTagCompound;

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

    public static void main(String[] args) {
        tierNumbersMatchSpec();
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
