package com.futa_gtnh.supply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/** Fixed-window totals and a source index share the same bounded minute rings. */
public final class SupplyLedger {

    public static final int MINUTES = 60;
    public static final int MAX_KEYS = 4096;
    public static final int MAX_PAIRS = 16384;
    public static final int MAX_SOURCES = 4096;
    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    private final Map<String, SourceEntry> sources = new LinkedHashMap<>();
    private Epoch[] epochs = new Epoch[MINUTES];
    private List<SupplySource> sourceList;
    private int pairs;
    private long dropped, minute = -1, generation, sourceRevision, structureRevision, nextOrder;

    public synchronized void record(Object key, SupplySource source, long amount, boolean incoming, long at) {
        if (key == null || source == null || amount <= 0 || at < 0) return;
        advance(at);
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= MAX_KEYS) {
                dropped++;
                return;
            }
            entry = new Entry(nextOrder++);
            entries.put(key, entry);
        }
        entry.lastMinute = Math.max(entry.lastMinute, at);
        entry.total.add(at, amount, incoming);
        Pair pair = entry.bySource.get(source);
        if (pair == null) {
            SourceEntry inlet = sources.get(source.id());
            if (pairs >= MAX_PAIRS || inlet == null && sources.size() >= MAX_SOURCES) {
                dropped++;
                return;
            }
            if (inlet == null) {
                inlet = new SourceEntry(source);
                sources.put(source.id(), inlet);
                sourcesChanged();
            }
            pair = new Pair(key, source, entry.order, inlet);
            entry.bySource.put(source, pair);
            inlet.pairs.put(key, pair);
            pairs++;
            structureRevision++;
        }
        pair.ring.add(at, amount, incoming);
    }

    /** Do expiry work once for the ledger, never rescan W buckets for each query row. */
    public synchronized void advance(long now) {
        if (now < 0 || now <= minute) return;
        if (minute < 0) {
            minute = now;
            return;
        }
        if (now - minute >= MINUTES) {
            for (Entry entry : entries.values()) {
                entry.total.resetTotals();
                for (Pair pair : entry.bySource.values()) pair.ring.resetTotals();
            }
            for (SourceEntry source : sources.values()) source.totals.clear();
            // Drop the old event vectors as a group, rather than walking P * 60 references on the tick thread.
            epochs = new Epoch[MINUTES];
            minute = now;
            return;
        }
        while (minute < now) {
            minute++;
            expireEpoch(minute - 10, 10);
            expireEpoch(minute - MINUTES, MINUTES);
        }
    }

    private void expireEpoch(long at, int window) {
        if (at < 0) return;
        int slot = (int) (at % MINUTES);
        Epoch epoch = epochs[slot];
        if (epoch == null || epoch.minute != at) return;
        for (Ring ring : epoch.rings) ring.expire(at, window);
        if (window == MINUTES) epochs[slot] = null;
    }

    private void enqueue(Ring ring, long at) {
        if (minute - at >= MINUTES) return;
        int slot = (int) (at % MINUTES);
        Epoch epoch = epochs[slot];
        if (epoch == null || epoch.minute != at) epochs[slot] = epoch = new Epoch(at);
        epoch.rings.add(ring);
    }

    public synchronized Snapshot snapshot(Object key, int minutes, long at) {
        advance(at);
        int window = window(minutes);
        Entry entry = entries.get(key);
        Snapshot result = new Snapshot();
        result.dropped = dropped;
        if (entry == null) return result;
        result.incoming = entry.total.sum(at, window, true);
        result.outgoing = entry.total.sum(at, window, false);
        for (Pair pair : entry.bySource.values()) {
            long out = pair.ring.sum(at, window, false), in = pair.ring.sum(at, window, true);
            if (out != 0 || in != 0) result.rows.add(new Row(pair.source, out, in));
        }
        result.rows.sort(
            Comparator.comparingLong((Row row) -> Math.max(0L, row.outgoing - row.incoming))
                .reversed()
                .thenComparing(row -> row.source.id()));
        return result;
    }

    public synchronized SupplySource source(String id) {
        SourceEntry source = sources.get(id);
        return source == null ? null : source.source;
    }

    public static int window(int minutes) {
        return minutes == 1 || minutes == 60 ? minutes : 10;
    }

    public interface FlowVisitor {

        void accept(Object key, SupplySource source, long incoming, long outgoing, long resourceOrder);
    }

    /** Source filtering precedes window reads and object construction. The two indexes own no duplicate buckets. */
    public synchronized void visitFlows(Collection<String> ids, int minutes, long at, FlowVisitor visitor) {
        advance(at);
        int window = window(minutes);
        if (ids == null) {
            for (Entry entry : entries.values())
                for (Pair pair : entry.bySource.values()) visit(pair, window, at, visitor);
        } else {
            for (String id : ids) {
                SourceEntry source = sources.get(id);
                if (source != null) for (Pair pair : source.pairs.values()) visit(pair, window, at, visitor);
            }
        }
    }

    private void visit(Pair pair, int window, long at, FlowVisitor visitor) {
        long in = pair.ring.sum(at, window, true), out = pair.ring.sum(at, window, false);
        if (in != 0 || out != 0) visitor.accept(pair.key, pair.source, in, out, pair.order);
    }

    public synchronized List<Flow> flows(int minutes, long at) {
        List<Flow> result = new ArrayList<>();
        visitFlows(null, minutes, at, (key, source, in, out, order) -> result.add(new Flow(key, source, in, out)));
        return result;
    }

    /** Device totals are incremented from the same per-pair bucket deltas, including saturation and expiry. */
    public synchronized Metrics metrics(String id, int minutes, long at) {
        advance(at);
        SourceEntry source = sources.get(id);
        if (source == null) return new Metrics();
        if (at == minute) return source.totals.snapshot(window(minutes), at);
        Metrics result = new Metrics();
        for (Pair pair : source.pairs.values()) {
            long in = pair.ring.sum(at, window(minutes), true), out = pair.ring.sum(at, window(minutes), false);
            result.add(pair.key, in, out);
        }
        return result;
    }

    public synchronized List<SupplySource> sources() {
        if (sourceList == null) {
            List<SupplySource> list = new ArrayList<>(sources.size());
            for (SourceEntry source : sources.values()) list.add(source.source);
            sourceList = Collections.unmodifiableList(list);
        }
        return sourceList;
    }

    private void sourcesChanged() {
        sourceList = null;
        sourceRevision++;
    }

    public synchronized long sourceRevision() {
        return sourceRevision;
    }

    public synchronized long structureRevision() {
        return structureRevision;
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized int keyCount() {
        return entries.size();
    }

    public synchronized int pairCount() {
        return pairs;
    }

    public static long observedMillis(int minutes, long nowMillis) {
        if (nowMillis <= 0) return 0;
        long firstMinute = Math.max(0L, nowMillis / 60_000L - window(minutes) + 1L);
        return nowMillis - firstMinute * 60_000L;
    }

    public synchronized SupplySource source(Object key, String id, long at) {
        SourceEntry source = sources.get(id);
        Pair pair = source == null ? null : source.pairs.get(key);
        return pair != null && at >= pair.ring.lastMinute && at - pair.ring.lastMinute < MINUTES ? source.source : null;
    }

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

    public synchronized void expire(long at) {
        advance(at);
        Iterator<Entry> keys = entries.values()
            .iterator();
        while (keys.hasNext()) {
            Entry entry = keys.next();
            Iterator<Pair> iterator = entry.bySource.values()
                .iterator();
            while (iterator.hasNext()) {
                Pair pair = iterator.next();
                if (at - pair.ring.lastMinute >= MINUTES) {
                    sources.get(pair.source.id()).pairs.remove(pair.key);
                    iterator.remove();
                    pairs--;
                    structureRevision++;
                }
            }
            if (at - entry.lastMinute >= MINUTES) keys.remove();
        }
        if (sources.values()
            .removeIf(source -> source.pairs.isEmpty())) sourcesChanged();
    }

    public synchronized void clear() {
        entries.clear();
        sources.clear();
        epochs = new Epoch[MINUTES];
        pairs = 0;
        dropped = 0;
        minute = -1;
        nextOrder = 0;
        generation++;
        structureRevision++;
        sourcesChanged();
    }

    public static long add(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }

    private final class Entry {

        final Ring total = new Ring(null, -1);
        final Map<SupplySource, Pair> bySource = new HashMap<>();
        final long order;
        long lastMinute = -1;

        Entry(long order) {
            this.order = order;
        }
    }

    private final class Pair {

        final Object key;
        final SupplySource source;
        final long order;
        final Ring ring;

        Pair(Object key, SupplySource source, long order, SourceEntry inlet) {
            this.key = key;
            this.source = source;
            this.order = order;
            ring = new Ring(inlet.totals, key instanceof ItemKey ? 0 : key instanceof FluidKey ? 2 : -1);
        }
    }

    private final class SourceEntry {

        final SupplySource source;
        final Map<Object, Pair> pairs = new LinkedHashMap<>();
        final Totals totals = new Totals();

        SourceEntry(SupplySource source) {
            this.source = source;
        }
    }

    private final class Epoch {

        final long minute;
        final List<Ring> rings = new ArrayList<>();

        Epoch(long minute) {
            this.minute = minute;
        }
    }

    private final class Ring {

        final long[] incoming = new long[MINUTES], outgoing = new long[MINUTES];
        final Wide tenIn = new Wide(), tenOut = new Wide(), hourIn = new Wide(), hourOut = new Wide();
        final Totals owner;
        final int kind;
        long lastMinute = -1, valid;

        Ring(Totals owner, int kind) {
            this.owner = owner;
            this.kind = kind;
        }

        void add(long at, long amount, boolean in) {
            if (at < lastMinute) return;
            long gap = lastMinute < 0 ? MINUTES : at - lastMinute;
            if (gap >= MINUTES) valid = 0;
            else if (gap > 0) {
                int start = (int) ((lastMinute + 1) % MINUTES);
                long mask = (1L << gap) - 1;
                valid &= ~((mask << start) | (start + gap > MINUTES ? mask >>> (MINUTES - start) : 0));
            }
            int slot = (int) (at % MINUTES);
            long bit = 1L << slot;
            boolean first = (valid & bit) == 0;
            if (first) {
                incoming[slot] = 0;
                outgoing[slot] = 0;
                valid |= bit;
                enqueue(this, at);
            }
            lastMinute = at;
            long[] values = in ? incoming : outgoing;
            long delta = Math.min(amount, Long.MAX_VALUE - values[slot]);
            values[slot] += delta;
            long age = minute - at;
            if (age < 10) (in ? tenIn : tenOut).add(delta);
            if (age < MINUTES) (in ? hourIn : hourOut).add(delta);
            if (owner != null && kind >= 0) owner.add(kind + (in ? 0 : 1), at, delta);
        }

        long bucket(long at, boolean in) {
            if (at < 0 || at > lastMinute || lastMinute - at >= MINUTES) return 0;
            int slot = (int) (at % MINUTES);
            return (valid & (1L << slot)) == 0 ? 0 : (in ? incoming : outgoing)[slot];
        }

        void expire(long at, int window) {
            long in = bucket(at, true), out = bucket(at, false);
            if (window == 10) {
                tenIn.subtract(in);
                tenOut.subtract(out);
            } else {
                hourIn.subtract(in);
                hourOut.subtract(out);
            }
            if (owner != null && kind >= 0) owner.expire(kind, window, in, out);
        }

        long sum(long at, int window, boolean in) {
            if (lastMinute < 0 || at < lastMinute || at - lastMinute >= MINUTES) return 0;
            if (at == minute) {
                if (window == 1) return bucket(at, in);
                return (window == 10 ? (in ? tenIn : tenOut) : (in ? hourIn : hourOut)).value();
            }
            // Only explicit retrospective/debug queries use the legacy oracle path.
            long sum = 0;
            for (int offset = 0; offset < window && offset <= at; offset++)
                sum = SupplyLedger.add(sum, bucket(at - offset, in));
            return sum;
        }

        void resetTotals() {
            tenIn.clear();
            tenOut.clear();
            hourIn.clear();
            hourOut.clear();
        }
    }

    /** Exact unsigned 128-bit accumulator: expiry can recover values after a previously saturated public total. */
    static final class Wide {

        long low, high;

        void add(long amount) {
            long old = low;
            low += amount;
            if (Long.compareUnsigned(low, old) < 0) high++;
        }

        void subtract(long amount) {
            long old = low;
            low -= amount;
            if (Long.compareUnsigned(old, amount) < 0) high--;
            if (high < 0) throw new IllegalStateException("Negative telemetry window total");
        }

        long value() {
            return high != 0 || low < 0 ? Long.MAX_VALUE : low;
        }

        void clear() {
            low = 0;
            high = 0;
        }
    }

    private final class Totals {

        final Wide[] values = new Wide[12];
        long current = -1;

        Totals() {
            for (int i = 0; i < values.length; i++) values[i] = new Wide();
        }

        void add(int kind, long at, long amount) {
            long age = minute - at;
            if (at == minute) {
                if (current != at) {
                    for (int i = 0; i < 4; i++) values[i].clear();
                    current = at;
                }
                values[kind].add(amount);
            }
            if (age < 10) values[4 + kind].add(amount);
            if (age < MINUTES) values[8 + kind].add(amount);
        }

        void expire(int kind, int window, long in, long out) {
            int offset = window == 10 ? 4 : 8;
            values[offset + kind].subtract(in);
            values[offset + kind + 1].subtract(out);
        }

        Metrics snapshot(int window, long at) {
            Metrics result = new Metrics();
            int start = window == 1 ? 0 : window == 10 ? 4 : 8;
            if (window == 1 && current != at) return result;
            result.itemIn = values[start].value();
            result.itemOut = values[start + 1].value();
            result.fluidIn = values[start + 2].value();
            result.fluidOut = values[start + 3].value();
            return result;
        }

        void clear() {
            for (Wide value : values) value.clear();
            current = -1;
        }
    }

    public static final class Metrics {

        public long itemIn, itemOut, fluidIn, fluidOut;

        void add(Object key, long in, long out) {
            if (key instanceof ItemKey) {
                itemIn = SupplyLedger.add(itemIn, in);
                itemOut = SupplyLedger.add(itemOut, out);
            } else if (key instanceof FluidKey) {
                fluidIn = SupplyLedger.add(fluidIn, in);
                fluidOut = SupplyLedger.add(fluidOut, out);
            }
        }
    }

    public static final class Snapshot {

        public long incoming, outgoing, dropped;
        public final List<Row> rows = new ArrayList<>();
    }

    public static final class Flow {

        public final Object key;
        public final SupplySource source;
        public final long incoming, outgoing;

        Flow(Object key, SupplySource source, long incoming, long outgoing) {
            this.key = key;
            this.source = source;
            this.incoming = incoming;
            this.outgoing = outgoing;
        }
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
