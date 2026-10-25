package com.futa_gtnh.supply;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded minute rings; transfer recording performs only hash lookups and integer additions. */
public final class SupplyLedger {

    public static final int MINUTES = 60;
    public static final int MAX_KEYS = 4096;
    public static final int MAX_PAIRS = 16384;
    public static final int MAX_SOURCES = 4096;
    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    private final Map<String, SupplySource> sources = new LinkedHashMap<>();
    private int pairs;
    private long dropped;

    public synchronized void record(Object key, SupplySource source, long amount, boolean incoming, long minute) {
        if (key == null || source == null || amount <= 0 || minute < 0) return;
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_KEYS) {
                dropped++;
                return;
            }
            entry = new Entry();
            entries.put(key, entry);
        }
        entry.lastMinute = minute;
        entry.total.add(minute, amount, incoming);
        Ring ring = entry.bySource.get(source);
        if (ring == null) {
            if (pairs >= MAX_PAIRS || (!sources.containsKey(source.id()) && sources.size() >= MAX_SOURCES)) {
                dropped++;
                return;
            }
            ring = new Ring();
            entry.bySource.put(source, ring);
            sources.put(source.id(), source);
            pairs++;
        }
        ring.add(minute, amount, incoming);
    }

    public synchronized Snapshot snapshot(Object key, int minutes, long minute) {
        int window = minutes == 60 ? 60 : 10;
        Entry entry = entries.get(key);
        Snapshot result = new Snapshot();
        result.dropped = dropped;
        if (entry == null) return result;
        result.incoming = entry.total.sum(minute, window, true);
        result.outgoing = entry.total.sum(minute, window, false);
        for (Map.Entry<SupplySource, Ring> pair : entry.bySource.entrySet()) {
            long out = pair.getValue()
                .sum(minute, window, false);
            long in = pair.getValue()
                .sum(minute, window, true);
            if (out != 0 || in != 0) result.rows.add(new Row(pair.getKey(), out, in));
        }
        result.rows.sort(
            Comparator.comparingLong((Row row) -> Math.max(0L, row.outgoing - row.incoming))
                .reversed()
                .thenComparing(row -> row.source.id()));
        return result;
    }

    public synchronized SupplySource source(String id) {
        return sources.get(id);
    }

    public synchronized int keyCount() {
        return entries.size();
    }

    public synchronized int pairCount() {
        return pairs;
    }

    /** Bounded page of previously issued keys, including resources with zero current stock. */
    public synchronized List<Object> catalog(int page, int pageSize) {
        int start = Math.max(0, page) * pageSize;
        List<Object> result = new ArrayList<>(pageSize);
        int index = 0;
        for (Object key : entries.keySet()) {
            if (index++ < start) continue;
            result.add(key);
            if (result.size() == pageSize) break;
        }
        return result;
    }

    /** Once per minute, not once per transfer. No world or inventory reads. */
    public synchronized void expire(long minute) {
        Iterator<Entry> keys = entries.values()
            .iterator();
        while (keys.hasNext()) {
            Entry entry = keys.next();
            if (minute - entry.lastMinute >= MINUTES) {
                pairs -= entry.bySource.size();
                keys.remove();
            } else {
                Iterator<Ring> rings = entry.bySource.values()
                    .iterator();
                while (rings.hasNext()) {
                    if (minute - rings.next().lastMinute >= MINUTES) {
                        rings.remove();
                        pairs--;
                    }
                }
            }
        }
        // Rebuild only this bounded set once a minute so abandoned inlets cannot exhaust the cap permanently.
        sources.clear();
        for (Entry entry : entries.values())
            for (SupplySource source : entry.bySource.keySet()) sources.put(source.id(), source);
    }

    public synchronized void clear() {
        entries.clear();
        sources.clear();
        pairs = 0;
        dropped = 0;
    }

    public static long add(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }

    private static final class Entry {

        final Ring total = new Ring();
        final Map<SupplySource, Ring> bySource = new HashMap<>();
        long lastMinute;
    }

    static final class Ring {

        final long[] incoming = new long[MINUTES];
        final long[] outgoing = new long[MINUTES];
        long lastMinute = -1;

        void add(long minute, long amount, boolean in) {
            if (minute < lastMinute) return;
            long gap = lastMinute < 0 ? 0 : Math.min(MINUTES, minute - lastMinute);
            for (long i = 0; i < gap; i++) {
                int slot = (int) ((minute - i) % MINUTES);
                incoming[slot] = 0;
                outgoing[slot] = 0;
            }
            lastMinute = minute;
            long[] target = in ? incoming : outgoing;
            int slot = (int) (minute % MINUTES);
            target[slot] = SupplyLedger.add(target[slot], amount);
        }

        long sum(long minute, int window, boolean in) {
            if (lastMinute < 0 || minute < lastMinute || minute - lastMinute >= MINUTES) return 0;
            long sum = 0;
            long[] values = in ? incoming : outgoing;
            for (int offset = 0; offset < window && offset <= minute; offset++) {
                long epoch = minute - offset;
                if (epoch <= lastMinute && lastMinute - epoch < MINUTES)
                    sum = SupplyLedger.add(sum, values[(int) (epoch % MINUTES)]);
            }
            return sum;
        }
    }

    public static final class Snapshot {

        public long incoming, outgoing, dropped;
        public final List<Row> rows = new ArrayList<>();
    }

    public static final class Row {

        public final SupplySource source;
        public final long outgoing, incoming;

        Row(SupplySource source, long outgoing, long incoming) {
            this.source = source;
            this.outgoing = outgoing;
            this.incoming = incoming;
        }
    }
}
