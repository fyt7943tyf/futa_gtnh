package com.futa_gtnh.supply;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.GsonBuilder;

/** Opt-in end-to-end query benchmark. Both algorithms run in the same JVM on the same real MC keys/NBT. */
public final class ProductionQueryBenchmark {

    private static volatile long blackhole;
    private static final long NOW = 3599999L;
    private static final int WARMUP = 150, SAMPLES = 400;
    private static com.sun.management.ThreadMXBean allocations;

    public static void run() throws Exception {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean
            && ((com.sun.management.ThreadMXBean) bean).isThreadAllocatedMemorySupported()) {
            allocations = (com.sun.management.ThreadMXBean) bean;
            allocations.setThreadAllocatedMemoryEnabled(true);
        }
        SupplyLedger ledger = new SupplyLedger();
        BaselineSupplyLedger reference = new BaselineSupplyLedger();
        ProductionLines lines = new ProductionLines();
        BaselineProductionLines old = new BaselineProductionLines();
        Object[] keys = new Object[4096];
        SupplySource[] sources = new SupplySource[4096];
        String[] groups = new String[64];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = SupplyQueryOptimizationRegression.resource(i);
            sources[i] = SupplyQueryOptimizationRegression.source(i);
        }
        for (int i = 0; i < groups.length; i++)
            groups[i] = SupplyQueryOptimizationRegression.create(lines, ledger, "性能线" + i);
        for (int i = 0; i < sources.length; i++)
            lines.mutate(SupplyQueryOptimizationRegression.action("bind", groups[i / 64], sources[i]), ledger);
        old.read(lines.write());
        for (int minute = 0; minute < 60; minute++)
            for (int key = 0; key < keys.length; key++) for (int branch = 0; branch < 4; branch++) {
                int source = (key + branch * 1024) % sources.length;
                long incoming = (key + minute) % 7 + 1, outgoing = (source + minute) % 11 + 2;
                reference.record(keys[key], sources[source], incoming, true, minute);
                reference.record(keys[key], sources[source], outgoing, false, minute);
                ledger.record(keys[key], sources[source], incoming, true, minute);
                ledger.record(keys[key], sources[source], outgoing, false, minute);
            }
        require(
            ledger.pairCount() == 16384 && reference.pairCount() == 16384 && ledger.keyCount() == 4096,
            "full fixture must reach exact caps");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("baseline", "v1.34.0 / 9a88d19a0ef94fbd3198ec57652a21479cca05ac (frozen Java classes)");
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("vm", System.getProperty("java.vm.name"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put(
            "processors",
            Runtime.getRuntime()
                .availableProcessors());
        report.put(
            "maxHeapMiB",
            Runtime.getRuntime()
                .maxMemory() / 1048576);
        report.put("pairs", 16384);
        report.put("resources", 4096);
        report.put("sources", 4096);
        report.put("lines", 64);
        report.put("windowMinutes", 60);
        report.put("populatedMinutes", 60);
        report.put("originalBucketReadsPerFullQuery", 1966080);
        report.put("warmupQueriesPerVariant", WARMUP);
        report.put("measuredQueriesPerVariant", SAMPLES);
        report.put(
            "notes",
            "Synthetic in-process Java queries with real item/fluid keys and paginated NBT, no world/AE inventory IO; not a server TPS result. Cold rebuilds query aggregates but reuses metadata indexes; warm mode deliberately hits one coherent snapshot. Allocation counts include all allocations on the benchmark thread.");
        List<Map<String, Object>> cases = new ArrayList<>();
        report.put("queries", cases);
        String[] scopes = { "", groups[0], "" };
        String[] selected = { "", "", sources[17].id() };
        String[] labels = { "all_cold", "line_cold", "io_cold" };
        for (int c = 0; c < labels.length; c++) {
            final String scope = scopes[c], source = selected[c];
            NBTTagCompound expected = old.snapshot(reference, scope, source, 60, 0, 0, 0, NOW);
            lines.clearSnapshots();
            require(
                expected.equals(lines.snapshot(ledger, scope, source, 60, 0, 0, 0, NOW)),
                "query outputs must agree before timing " + labels[c]);
            IntFunction<NBTTagCompound> before = i -> old.snapshot(reference, scope, source, 60, 0, i % 8, i % 8, NOW);
            IntFunction<NBTTagCompound> after = i -> {
                lines.clearSnapshots();
                return lines.snapshot(ledger, scope, source, 60, 0, i % 8, i % 8, NOW);
            };
            cases.add(compare(labels[c], before, after));
        }
        lines.clearSnapshots();
        lines.snapshot(ledger, "", "", 60, 0, 0, 0, NOW);
        cases.add(
            compare(
                "all_paginate_cache_hit",
                i -> old.snapshot(reference, "", "", 60, 0, i % 512, i % 512, NOW),
                i -> lines.snapshot(ledger, "", "", 60, 0, i % 512, i % 512, NOW)));
        NBTTagCompound metadata = lines.bindingPage(0),
            legacy = old.snapshot(reference, "", sources[17].id(), 60, 0, 0, 0, NOW);
        require(
            metadata.getTag("lines")
                .equals(legacy.getTag("lines")) && metadata.getInteger("linePages") == legacy.getInteger("linePages"),
            "binding metadata must agree");
        cases.add(
            compare(
                "binding_metadata_only",
                i -> old.snapshot(reference, "", sources[17].id(), 60, i % 8, 0, 0, NOW),
                i -> lines.bindingPage(i % 8)));
        report.put("recordHotPath", record(ledger, reference, keys[0], sources[0]));
        long start = System.nanoTime();
        reference.expire(60);
        double before = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        ledger.expire(60);
        double after = (System.nanoTime() - start) / 1e6;
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("baselineMs", before);
        boundary.put("optimizedMs", after);
        boundary.put("samples", 1);
        report.put("minuteBoundary", boundary);
        lines.clearSnapshots();
        require(
            old.snapshot(reference, "", "", 60, 0, 0, 0, 3600001)
                .equals(lines.snapshot(ledger, "", "", 60, 0, 0, 0, 3600001)),
            "minute expiry still matches at full capacity");
        start = System.nanoTime();
        reference.expire(120);
        before = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        ledger.expire(120);
        after = (System.nanoTime() - start) / 1e6;
        Map<String, Object> idle = new LinkedHashMap<>();
        idle.put("baselineMs", before);
        idle.put("optimizedMs", after);
        idle.put("samples", 1);
        report.put("idleHourExpiry", idle);
        require(
            ledger.pairCount() == 0 && reference.pairCount() == 0,
            "expired fixture must release all pairs/indexes");
        File output = new File("build/reports/supply-query-benchmark.json");
        output.getParentFile()
            .mkdirs();
        String json = new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(report);
        Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("PRODUCTION_QUERY_BENCHMARK_SUCCESS " + output.getAbsolutePath());
        System.out.println(json);
    }

    private static Map<String, Object> compare(String name, IntFunction<NBTTagCompound> before,
        IntFunction<NBTTagCompound> after) {
        for (int i = 0; i < WARMUP; i++) {
            consume(before.apply(i));
            consume(after.apply(i));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", name);
        Map<String, Object> old = measure(before), next = measure(after);
        result.put("baseline", old);
        result.put("optimized", next);
        result.put(
            "medianSpeedup",
            ((Number) old.get("p50Ms")).doubleValue() / ((Number) next.get("p50Ms")).doubleValue());
        return result;
    }

    private static Map<String, Object> measure(IntFunction<NBTTagCompound> query) {
        long[] elapsed = new long[SAMPLES];
        long allocated = allocated(), gc = gcCount();
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            consume(query.apply(i));
            elapsed[i] = System.nanoTime() - start;
        }
        allocated = allocated < 0 ? -1 : allocated() - allocated;
        gc = gcCount() - gc;
        Arrays.sort(elapsed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("p50Ms", elapsed[SAMPLES / 2] / 1e6);
        result.put("p95Ms", elapsed[(int) (SAMPLES * .95)] / 1e6);
        result.put("p99Ms", elapsed[(int) (SAMPLES * .99)] / 1e6);
        result.put("maxMs", elapsed[SAMPLES - 1] / 1e6);
        result.put("allocatedBytesPerQuery", allocated < 0 ? null : allocated / (double) SAMPLES);
        result.put("gcCollections", gc);
        return result;
    }

    private static Map<String, Object> record(SupplyLedger next, BaselineSupplyLedger old, Object key,
        SupplySource source) {
        int records = 1000000;
        long allocation = allocated(), start = System.nanoTime();
        for (int i = 0; i < records; i++) old.record(key, source, 1, false, 59);
        long before = System.nanoTime() - start, oldAllocation = allocation < 0 ? -1 : allocated() - allocation;
        allocation = allocated();
        start = System.nanoTime();
        for (int i = 0; i < records; i++) next.record(key, source, 1, false, 59);
        long after = System.nanoTime() - start, newAllocation = allocation < 0 ? -1 : allocated() - allocation;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("baselineNsPerRecord", before / (double) records);
        result.put("optimizedNsPerRecord", after / (double) records);
        result.put("baselineAllocatedBytesPerRecord", oldAllocation / (double) records);
        result.put("optimizedAllocatedBytesPerRecord", newAllocation / (double) records);
        return result;
    }

    private static long allocated() {
        return allocations == null ? -1
            : allocations.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId());
    }

    private static long gcCount() {
        long count = 0;
        for (java.lang.management.GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
            if (bean.getCollectionCount() > 0) count += bean.getCollectionCount();
        return count;
    }

    private static void consume(NBTTagCompound value) {
        blackhole ^= value.getInteger("sourceCount") + value.getInteger("lineCount")
            + value.getLong("age")
            + value.getCompoundTag("totals")
                .getLong("itemOut");
    }

    private static void require(boolean okay, String reason) {
        if (!okay) throw new AssertionError(reason);
    }
}
