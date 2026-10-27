package com.futa_gtnh.supply;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/** Differential oracle is the frozen release implementation, not a reimplementation of the optimized formulas. */
public final class SupplyQueryOptimizationRegression {

    private static int checks;

    public static void run() throws Exception {
        randomLedger();
        saturation();
        views();
        attachments();
        System.out.println(
            "Production query optimization: " + checks
                + " checks passed (frozen baseline, exact expiry, source index, coherent bounded cache and current-binding history)");
    }

    static SupplySource source(int n) {
        return new SupplySource(SupplySource.IO, 0, n, 64, 0, 5, "");
    }

    static Object resource(int n) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("queryResource", n);
        if ((n & 1) == 0) {
            ItemStack stack = new ItemStack(Items.iron_ingot);
            stack.setTagCompound(tag);
            return ItemKey.of(stack);
        }
        return FluidKey.of(new FluidStack(FluidRegistry.WATER, 1, tag));
    }

    static NBTTagCompound action(String kind, String line, SupplySource source) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("action", kind);
        tag.setString("lineId", line);
        tag.setString("name", line);
        if (source != null) tag.setTag("source", source.write());
        return tag;
    }

    static String create(ProductionLines lines, SupplyLedger ledger, String name) {
        return lines.mutate(action("create", name, null), ledger)
            .getString("lineId");
    }

    private static void check(boolean okay, String label) {
        if (!okay) throw new AssertionError(label);
        checks++;
    }

    private static void randomLedger() {
        SupplyLedger next = new SupplyLedger();
        BaselineSupplyLedger old = new BaselineSupplyLedger();
        Random random = new Random(7943);
        Object[] keys = new Object[32];
        SupplySource[] sources = new SupplySource[16];
        for (int i = 0; i < keys.length; i++) keys[i] = resource(i);
        for (int i = 0; i < sources.length; i++) sources[i] = source(i);
        long minute = 0;
        for (int i = 0; i < 30000; i++) {
            if (i % 29 == 0) minute += random.nextInt(4);
            if (i % 2003 == 0) minute += 61;
            Object key = keys[random.nextInt(keys.length)];
            SupplySource source = sources[random.nextInt(sources.length)];
            long amount = i % 113 == 0 ? Long.MAX_VALUE : random.nextInt(10000) + 1;
            boolean in = random.nextBoolean();
            old.record(key, source, amount, in, minute);
            next.record(key, source, amount, in, minute);
            if (i % 19 == 0) {
                for (int window : new int[] { 1, 10, 60 }) {
                    BaselineSupplyLedger.Snapshot a = old.snapshot(key, window, minute);
                    SupplyLedger.Snapshot b = next.snapshot(key, window, minute);
                    check(
                        a.incoming == b.incoming && a.outgoing == b.outgoing,
                        "resource total differs at " + i + "/" + window);
                    check(a.rows.size() == b.rows.size(), "source row count differs");
                    for (int row = 0; row < a.rows.size(); row++) check(
                        a.rows.get(row).source.equals(b.rows.get(row).source)
                            && a.rows.get(row).incoming == b.rows.get(row).incoming
                            && a.rows.get(row).outgoing == b.rows.get(row).outgoing,
                        "source row/order differs");
                    Map<Object, long[]> expected = new HashMap<>();
                    long[] metrics = new long[4];
                    for (BaselineSupplyLedger.Flow flow : old.flows(window, minute)) if (flow.source.equals(source)) {
                        expected.put(flow.key, new long[] { flow.incoming, flow.outgoing });
                        int offset = flow.key instanceof ItemKey ? 0 : 2;
                        metrics[offset] = SupplyLedger.add(metrics[offset], flow.incoming);
                        metrics[offset + 1] = SupplyLedger.add(metrics[offset + 1], flow.outgoing);
                    }
                    Map<Object, long[]> actual = new HashMap<>();
                    next.visitFlows(
                        Collections.singleton(source.id()),
                        window,
                        minute,
                        (k, s, n, o, order) -> actual.put(k, new long[] { n, o }));
                    check(
                        expected.keySet()
                            .equals(actual.keySet()),
                        "indexed source must exclude unrelated resources");
                    for (Object id : expected.keySet())
                        check(Arrays.equals(expected.get(id), actual.get(id)), "indexed source totals differ");
                    SupplyLedger.Metrics m = next.metrics(source.id(), window, minute);
                    check(
                        Arrays.equals(metrics, new long[] { m.itemIn, m.itemOut, m.fluidIn, m.fluidOut }),
                        "device fixed totals differ from summing the oracle pairs");
                }
            }
            if (i % 101 == 0) {
                old.expire(minute);
                next.expire(minute);
                check(
                    old.keyCount() == next.keyCount() && old.pairCount() == next.pairCount(),
                    "expiry/cardinality differs");
            }
            if (i % 5003 == 0) {
                old.clear();
                next.clear();
                check(
                    next.sources()
                        .isEmpty(),
                    "clear removes reverse index");
            }
        }
    }

    private static void saturation() {
        SupplyLedger next = new SupplyLedger();
        BaselineSupplyLedger old = new BaselineSupplyLedger();
        Object key = resource(0);
        SupplySource source = source(3);
        for (long minute : new long[] { 0, 1, 9, 10, 11, 59, 60, 61, 119, 120, 121 }) {
            long value = minute < 10 ? Long.MAX_VALUE : 17;
            old.record(key, source, value, false, minute);
            next.record(key, source, value, false, minute);
            for (int window : new int[] { 1, 10, 60 }) check(
                old.snapshot(key, window, minute).outgoing == next.snapshot(key, window, minute).outgoing,
                "saturated totals must recover exactly when old buckets expire");
        }
        next.record(key, source, 999, false, 120);
        check(
            next.snapshot(key, 60, 121).outgoing == old.snapshot(key, 60, 121).outgoing,
            "out-of-order write must not change current counts");
        next.advance(1000000000L);
        check(
            next.snapshot(key, 60, 1000000000L).outgoing == 0,
            "long idle advance must not loop through every missing minute");
        SupplyLedger.Wide exact = new SupplyLedger.Wide();
        for (int i = 0; i < 16384 * 60; i++) exact.add(Long.MAX_VALUE);
        check(exact.value() == Long.MAX_VALUE, "wide source accumulator exceeds 64 bits safely");
        for (int i = 0; i < 16384 * 60; i++) exact.subtract(Long.MAX_VALUE);
        check(exact.value() == 0, "wide subtraction is reversible after very large saturated totals");
    }

    private static void views() throws Exception {
        ProductionLines next = new ProductionLines();
        SupplyLedger ledger = new SupplyLedger();
        BaselineProductionLines old = new BaselineProductionLines();
        BaselineSupplyLedger reference = new BaselineSupplyLedger();
        String a = create(next, ledger, "甲线"), b = create(next, ledger, "乙线");
        for (int i = 0; i < 20; i++) next.mutate(action("bind", i < 10 ? a : b, source(i)), ledger);
        old.read(next.write());
        for (int i = 0; i < 32; i++)
            for (int source = 0; source < 22; source++) for (int minute = 0; minute < 60; minute++) {
                long n = (i + source + minute) % 19 + 1;
                ledger.record(resource(i), source(source), n, (i & 2) == 0, minute);
                reference.record(resource(i), source(source), n, (i & 2) == 0, minute);
            }
        long now = 3599000;
        for (String line : new String[] { "", a, b, "missing" })
            for (String selected : new String[] { "", source(2).id(), source(21).id(), "missing" })
                for (int window : new int[] { 1, 10, 60 }) for (int page : new int[] { -1, 0, 1, 999 }) {
                    next.clearSnapshots();
                    NBTTagCompound actual = next.snapshot(ledger, line, selected, window, page, page, page, now),
                        expected = old.snapshot(reference, line, selected, window, page, page, page, now);
                    check(
                        expected.equals(actual),
                        "full paginated protocol differs from oracle: " + line
                            + "/"
                            + selected
                            + "/"
                            + window
                            + "/"
                            + page
                            + " expected="
                            + expected
                            + " actual="
                            + actual);
                }
        NBTTagCompound bind = action("bind", b, source(2));
        next.mutate(bind, ledger);
        old.mutate(bind, reference);
        check(
            next.snapshot(ledger, a, "", 60, 0, 0, 0, now)
                .equals(old.snapshot(reference, a, "", 60, 0, 0, 0, now)),
            "old line loses moved IO history immediately");
        check(
            next.snapshot(ledger, b, "", 60, 0, 0, 0, now)
                .equals(old.snapshot(reference, b, "", 60, 0, 0, 0, now)),
            "new line receives the IO's existing hour without duplicate counts");
        SupplyLedger single = new SupplyLedger();
        ProductionLines cached = new ProductionLines();
        Object key = resource(0);
        single.record(key, source(1), 100, false, 0);
        NBTTagCompound first = cached.snapshot(single, "", "", 10, 0, 0, 0, 30000);
        Object view = firstView(cached);
        single.record(key, source(1), 20, false, 0);
        NBTTagCompound hit = cached.snapshot(single, "", "", 10, 0, 0, 0, 30500);
        check(
            hit.equals(first) && view == firstView(cached),
            "one-second cache is a coherent shared snapshot, including its sample time");
        NBTTagCompound fresh = cached.snapshot(single, "", "", 10, 0, 0, 0, 31000);
        check(
            fresh.getCompoundTag("totals")
                .getLong("itemOut") == 120 && fresh.getLong("age") == 31000,
            "continuous writes do not prevent expiry of cached counts");
        NBTTagCompound boundary = cached.snapshot(single, "", "", 1, 0, 0, 0, 60000);
        check(
            boundary.getCompoundTag("totals")
                .getLong("itemOut") == 0 && !boundary.getBoolean("hasRate"),
            "minute boundary cannot reuse a previous-minute cache");
        single.clear();
        single.record(key, source(1), 3, false, 0);
        check(
            cached.snapshot(single, "", "", 10, 0, 0, 0, 31000)
                .getCompoundTag("totals")
                .getLong("itemOut") == 3,
            "ledger clear invalidates cached history and time regression is safe");
        for (int i = 0; i < 100; i++) cached.snapshot(single, "", source(i).id(), 10, 0, 0, 0, 31000);
        Field field = ProductionLines.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        check(((Map<?, ?>) field.get(cached)).size() <= ProductionLines.MAX_SNAPSHOTS, "snapshot count is bounded");
        check(
            !cached.bindingPage(0)
                .hasKey("totals")
                && !cached.bindingPage(0)
                    .hasKey("resources"),
            "binding page has no telemetry query payload");
        SupplyLedger large = new SupplyLedger();
        ProductionLines bounded = new ProductionLines();
        for (int i = 0; i < 600; i++) large.record(resource(i), source(i), i + 1, false, 0);
        bounded.snapshot(large, "", "", 60, 0, 0, 0, 30000);
        Object shared = firstView(bounded);
        NBTTagCompound page = bounded.snapshot(large, "", "", 60, 1, 1, 1, 30000);
        check(shared == firstView(bounded), "different pages and viewers reuse the same aggregate snapshot");
        page.getCompoundTag("totals")
            .setLong("itemOut", -1);
        check(
            bounded.snapshot(large, "", "", 60, 0, 0, 0, 30000)
                .getCompoundTag("totals")
                .getLong("itemOut") > 0,
            "returned NBT must not mutate cached aggregates");
        for (int i = 0; i < 100; i++) bounded.snapshot(large, "", source(i).id(), 60, 0, 0, 0, 30000);
        Field weight = ProductionLines.class.getDeclaredField("cachedRows");
        weight.setAccessible(true);
        check(
            weight.getInt(bounded) <= ProductionLines.MAX_CACHED_ROWS
                && ((Map<?, ?>) field.get(bounded)).size() < ProductionLines.MAX_SNAPSHOTS,
            "weighted budget evicts large snapshots before reaching the entry cap");
    }

    private static Object firstView(ProductionLines lines) throws Exception {
        Field field = ProductionLines.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(lines)).values()
            .iterator()
            .next();
    }

    private static void attachments() throws Exception {
        ProductionLines lines = new ProductionLines();
        SupplyLedger ledger = new SupplyLedger();
        String a = create(lines, ledger, "接口线");
        SupplySource parent = new SupplySource(SupplySource.AE_MACHINE, 0, 10, 64, 10, -1, "");
        SupplySource child = new SupplySource(SupplySource.AE_BUS, 0, 11, 64, 10, 4, "");
        lines.mutate(action("bind", a, parent), ledger);
        ProductionAttachments.Scan scan = new ProductionAttachments.Scan(true);
        scan.buses.add(child);
        scan.checked.add(child.id());
        lines.refreshAttachments(ignored -> scan, 32);
        ledger.record(resource(0), child, 7, false, 0);
        lines.snapshot(ledger, a, "", 10, 0, 0, 0, 30000);
        Object before = firstView(lines);
        lines.refreshAttachments(ignored -> scan, 32);
        lines.snapshot(ledger, a, "", 10, 0, 0, 0, 30000);
        check(before == firstView(lines), "unchanged automatic discovery must not invalidate every cached query");
        String b = create(lines, ledger, "接口二线");
        lines.mutate(action("bind", b, parent), ledger);
        lines.refreshAttachments(ignored -> scan, 32);
        check(
            lines.snapshot(ledger, a, "", 10, 0, 0, 0, 30000)
                .getCompoundTag("totals")
                .getLong("itemOut") == 0
                && lines.snapshot(ledger, b, "", 10, 0, 0, 0, 30000)
                    .getCompoundTag("totals")
                    .getLong("itemOut") == 7,
            "inherited bus history follows its parent's current binding");
    }
}
