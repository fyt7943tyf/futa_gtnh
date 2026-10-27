package com.futa_gtnh.supply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.network.Utf8Text;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/** Saved device membership; throughput is derived from the one shared telemetry ledger. */
final class ProductionLines {

    static final int MAX_LINES = 256;
    private final Map<String, String> lines = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    // Derived membership is rebuilt from saved interface bindings, so the existing disk format stays intact.
    private final Map<String, Binding> attached = new LinkedHashMap<>();
    private int attachmentCursor;
    private Map<String, Binding> effectiveCache;
    private Map<String, List<Binding>> membersCache;
    private List<Map.Entry<String, String>> lineRowsCache;
    private final Map<String, Scope> scopes = new HashMap<>();
    private final LinkedHashMap<QueryKey, View> snapshots = new LinkedHashMap<>(16, 0.75F, true);
    private int cachedRows;
    static final int MAX_CACHED_ROWS = 32768, MAX_SNAPSHOTS = 64;
    static final long SNAPSHOT_MILLIS = 1000;

    private void metadataChanged() {
        effectiveCache = null;
        membersCache = null;
        lineRowsCache = null;
        scopes.clear();
        clearSnapshots();
    }

    /** Also used by the uncached benchmark; clearing these views never discards telemetry. */
    void clearSnapshots() {
        snapshots.clear();
        cachedRows = 0;
    }

    NBTTagCompound mutate(NBTTagCompound request, SupplyLedger ledger) {
        String action = request.getString("action");
        String lineId = resolve(request.getString("lineId"));
        String text = Utf8Text.limit(
            request.getString("name")
                .trim(),
            96);
        if ("create".equals(action)) {
            if (text.isEmpty()) return result(false, "产线名称不能为空", "");
            if (lines.size() >= MAX_LINES) return result(false, "产线数量已达到上限", "");
            lineId = UUID.randomUUID()
                .toString();
            lines.put(lineId, text);
            metadataChanged();
            return result(true, "已创建产线", lineId);
        }
        if ("rename".equals(action) || "delete".equals(action) || "bind".equals(action)) {
            if (lineId == null) return result(false, "找不到产线；请使用列表中的 UUID", "");
        }
        if ("rename".equals(action)) {
            if (text.isEmpty()) return result(false, "产线名称不能为空", lineId);
            if (!text.equals(lines.put(lineId, text))) metadataChanged();
            return result(true, "已重命名产线", lineId);
        }
        if ("delete".equals(action)) {
            lines.remove(lineId);
            final String removed = lineId;
            bindings.values()
                .removeIf(binding -> binding.lineId.equals(removed));
            attached.values()
                .removeIf(binding -> !bindings.containsKey(binding.parentId));
            metadataChanged();
            return result(true, "已删除产线分组；设备及库存未改变", "");
        }
        if ("bind".equals(action) || "unbind".equals(action)) {
            String sourceId = request.getString("sourceId");
            SupplySource source = source(request, ledger);
            if (source != null) sourceId = source.id();
            if (source == null || !source.hasPosition() || !source.player.isEmpty())
                return result(false, "请选择已记录的设备或填写有效的维度、坐标、类型和工作面", lineId);
            if ("unbind".equals(action)) {
                if (bindings.remove(sourceId) == null) return result(
                    false,
                    attached.containsKey(sourceId) ? "此总线自动跟随接口；请解绑接口，或为总线手动指定其他产线" : "该设备尚未绑定产线",
                    lineId);
                final String removed = sourceId;
                attached.values()
                    .removeIf(binding -> binding.parentId.equals(removed));
                metadataChanged();
                return result(true, "已解除设备绑定", lineId);
            }
            if (!bindings.containsKey(sourceId) && bindings.size() >= SupplyLedger.MAX_SOURCES)
                return result(false, "绑定设备数量已达到上限", lineId);
            Binding previous = bindings.get(sourceId);
            if (previous == null || !previous.lineId.equals(lineId) || !previous.source.equals(source)) {
                bindings.put(sourceId, new Binding(lineId, source));
                metadataChanged();
            }
            return result(true, "已绑定设备；同一设备只归属一条产线", lineId);
        }
        return result(false, "未知产线操作", lineId);
    }

    SupplySource source(NBTTagCompound request, SupplyLedger ledger) {
        if (request.hasKey("source", 10)) return SupplySource.read(request.getCompoundTag("source"));
        String id = request.getString("sourceId");
        SupplySource source = ledger.source(id);
        Binding member = bindings.get(id);
        if (member == null) member = attached.get(id);
        return source != null ? source : member == null ? null : member.source;
    }

    /** Bounded periodic repair also covers interfaces bound before this feature was installed. */
    void refreshAttachments(Function<SupplySource, ProductionAttachments.Scan> scan, int budget) {
        List<Binding> parents = new ArrayList<>();
        for (Binding binding : bindings.values())
            if (binding.source.kind == SupplySource.AE_MACHINE) parents.add(binding);
        int count = Math.min(Math.max(0, budget), parents.size());
        for (int i = 0; i < count; i++) {
            if (attachmentCursor >= parents.size()) attachmentCursor = 0;
            refreshAttachment(parents.get(attachmentCursor++), scan);
        }
    }

    void refreshRelated(SupplySource source, Function<SupplySource, ProductionAttachments.Scan> scan) {
        if (source == null || !source.hasPosition()) return;
        if (source.kind == SupplySource.AE_MACHINE) {
            Binding parent = bindings.get(source.id());
            if (parent != null) refreshAttachment(parent, scan);
        } else if (source.kind == SupplySource.AE_BUS && source.side >= 0) {
            net.minecraftforge.common.util.ForgeDirection face = net.minecraftforge.common.util.ForgeDirection
                .getOrientation(source.side);
            for (int side : new int[] { -1, face.getOpposite()
                .ordinal() }) {
                SupplySource candidate = new SupplySource(
                    SupplySource.AE_MACHINE,
                    source.dim,
                    source.x + face.offsetX,
                    source.y + face.offsetY,
                    source.z + face.offsetZ,
                    side,
                    "");
                Binding parent = bindings.get(candidate.id());
                if (parent != null) refreshAttachment(parent, scan);
            }
        }
    }

    private void refreshAttachment(Binding parent, Function<SupplySource, ProductionAttachments.Scan> scan) {
        ProductionAttachments.Scan result;
        try {
            result = scan.apply(parent.source);
        } catch (RuntimeException unavailable) {
            return;
        }
        if (result == null) return; // An unloaded parent or neighbor must not erase known membership.
        Map<String, SupplySource> present = new LinkedHashMap<>();
        for (SupplySource source : result.buses) present.put(source.id(), source);
        boolean changed = attached.values()
            .removeIf(
                binding -> binding.parentId.equals(parent.source.id()) && (!result.interfacePresent
                    || result.checked.contains(binding.source.id()) && !present.containsKey(binding.source.id())));
        for (SupplySource source : present.values()) {
            if (!attached.containsKey(source.id()) && attached.size() >= SupplyLedger.MAX_SOURCES) continue;
            Binding before = attached.get(source.id());
            if (before == null || !before.lineId.equals(parent.lineId)
                || !before.parentId.equals(parent.source.id())
                || !before.source.equals(source)) {
                attached.put(source.id(), new Binding(parent.lineId, source, parent.source.id()));
                changed = true;
            }
        }
        if (changed) metadataChanged();
    }

    private Map<String, Binding> effectiveBindings() {
        if (effectiveCache != null) return effectiveCache;
        Map<String, Binding> effective = new LinkedHashMap<>(bindings);
        for (Binding child : attached.values()) {
            Binding parent = bindings.get(child.parentId);
            if (parent != null && !effective.containsKey(child.source.id())
                && effective.size() < SupplyLedger.MAX_SOURCES)
                effective.put(child.source.id(), new Binding(parent.lineId, child.source, child.parentId));
        }
        effectiveCache = Collections.unmodifiableMap(effective);
        membersCache = new HashMap<>();
        for (Binding binding : effective.values())
            membersCache.computeIfAbsent(binding.lineId, ignored -> new ArrayList<>())
                .add(binding);
        lineRowsCache = new ArrayList<>(lines.entrySet());
        return effectiveCache;
    }

    private String resolve(String id) {
        if (lines.containsKey(id)) return id;
        String found = null;
        for (Map.Entry<String, String> line : lines.entrySet()) {
            if (!line.getValue()
                .equals(id)) continue;
            if (found != null) return null;
            found = line.getKey();
        }
        return found;
    }

    static NBTTagCompound result(boolean ok, String message, String lineId) {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("ok", ok);
        result.setString("message", message);
        result.setString("lineId", lineId == null ? "" : lineId);
        return result;
    }

    /** Binding windows need metadata only, including inactive devices; no ledger or window reads. */
    NBTTagCompound bindingPage(int linePage) {
        NBTTagCompound root = new NBTTagCompound();
        writeLines(root, linePage);
        return root;
    }

    private void writeLines(NBTTagCompound root, int linePage) {
        effectiveBindings();
        int page = page(root, "line", linePage, lineRowsCache.size());
        NBTTagList tags = new NBTTagList();
        for (int i = page * SupplyTracker.PAGE_SIZE; i < end(page, lineRowsCache.size()); i++) {
            Map.Entry<String, String> line = lineRowsCache.get(i);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", line.getKey());
            tag.setString("name", line.getValue());
            List<Binding> members = membersCache.get(line.getKey());
            tag.setInteger("sourceCount", members == null ? 0 : members.size());
            tags.appendTag(tag);
        }
        root.setTag("lines", tags);
    }

    private Scope scope(SupplyLedger ledger, String line, boolean all) {
        String id = all ? "" : line == null ? "?" : line;
        Scope previous = scopes.get(id);
        if (previous != null && previous.ledger == ledger && previous.sourcesRevision == ledger.sourceRevision())
            return previous;
        Map<String, Binding> effective = effectiveBindings();
        LinkedHashMap<String, Identity> devices = new LinkedHashMap<>();
        Iterable<Binding> members = all ? effective.values() : membersCache.getOrDefault(line, Collections.emptyList());
        for (Binding member : members) devices.put(member.source.id(), new Identity(member.source, member.lineId));
        if (all) for (SupplySource source : ledger.sources()) if (source.hasPosition()) {
            Binding binding = effective.get(source.id());
            devices.putIfAbsent(source.id(), new Identity(source, binding == null ? "" : binding.lineId));
        }
        Scope next = new Scope(ledger, ledger.sourceRevision(), devices);
        scopes.put(id, next);
        return next;
    }

    NBTTagCompound snapshot(SupplyLedger ledger, String requestedLine, String sourceId, int minutes, int linePage,
        int sourcePage, int resourcePage, long now) {
        String lineId = resolve(requestedLine);
        boolean all = requestedLine == null || requestedLine.isEmpty();
        String selected = sourceId == null ? "" : sourceId;
        QueryKey key = new QueryKey(all ? "" : lineId == null ? "?" : lineId, selected, SupplyLedger.window(minutes));
        View view = snapshots.get(key);
        if (view == null || view.ledger != ledger
            || view.generation != ledger.generation()
            || view.structure != ledger.structureRevision()
            || now < view.time
            || now - view.time >= SNAPSHOT_MILLIS
            || now / 60000L != view.time / 60000L) {
            if (view != null) {
                snapshots.remove(key);
                cachedRows -= view.weight();
            }
            view = build(ledger, scope(ledger, lineId, all), selected, key.minutes, now);
            snapshots.put(key, view);
            cachedRows += view.weight();
            Iterator<View> iterator = snapshots.values()
                .iterator();
            while (snapshots.size() > MAX_SNAPSHOTS || cachedRows > MAX_CACHED_ROWS) {
                View removed = iterator.next();
                cachedRows -= removed.weight();
                iterator.remove();
            }
        }
        long observed = SupplyLedger.observedMillis(key.minutes, view.time);
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("minutes", key.minutes);
        root.setLong("observedMillis", observed);
        root.setBoolean("hasRate", observed > 0);
        root.setBoolean("bucketed", true);
        root.setLong("age", view.time);
        root.setLong("dropped", ledger.dropped());
        root.setString("lineId", lineId == null ? "" : lineId);
        root.setString("lineName", lineId == null ? "" : lines.get(lineId));
        root.setBoolean("lineFound", all || lineId != null);
        root.setString("sourceId", selected);
        root.setBoolean("sourceFound", selected.isEmpty() || view.scope.devices.containsKey(selected));
        writeLines(root, linePage);
        root.setTag("totals", view.total.metrics(observed));
        int page = page(root, "source", sourcePage, view.devices.size());
        NBTTagList sources = new NBTTagList();
        for (int i = page * SupplyTracker.PAGE_SIZE; i < end(page, view.devices.size()); i++) {
            Device device = view.devices.get(i);
            NBTTagCompound tag = device.source.write(), metrics = device.metrics(observed);
            for (Object raw : metrics.func_150296_c()) {
                String name = (String) raw;
                tag.setTag(name, metrics.getTag(name));
            }
            tag.setString("id", device.source.id());
            tag.setString("name", SupplyTracker.name(device.source));
            tag.setString("lineId", device.lineId);
            tag.setString("lineName", lines.getOrDefault(device.lineId, ""));
            writeParent(tag, effectiveBindings().get(device.source.id()));
            tag.setBoolean("active", device.active);
            sources.appendTag(tag);
        }
        root.setTag("sources", sources);
        page = page(root, "resource", resourcePage, view.resources.size());
        NBTTagList resources = new NBTTagList();
        for (int i = page * SupplyTracker.PAGE_SIZE; i < end(page, view.resources.size()); i++) {
            Resource resource = view.resources.get(i);
            if (!SupplyTracker.boundedKey(resource.key)) continue;
            NBTTagCompound tag = SupplyTracker.writeKey(resource.key);
            tag.setString("unit", resource.key instanceof FluidKey ? "mB" : "item");
            resource.amounts.write(tag, "", observed);
            resources.appendTag(tag);
        }
        root.setTag("resources", resources);
        return root;
    }

    private View build(SupplyLedger ledger, Scope scope, String selected, int minutes, long now) {
        ledger.advance(now / 60000L);
        List<Device> devices = new ArrayList<>(scope.devices.size());
        Device total = new Device(SupplySource.UNIDENTIFIED, "");
        for (Identity identity : scope.devices.values()) {
            SupplyLedger.Metrics metrics = ledger.metrics(identity.source.id(), minutes, now / 60000L);
            Device device = new Device(identity.source, identity.line);
            device.add(metrics);
            devices.add(device);
            if (selected.isEmpty() || selected.equals(identity.source.id())) total.add(metrics);
        }
        Map<Object, Resource> resources = new HashMap<>();
        java.util.Collection<String> ids = selected.isEmpty() ? scope.devices.keySet()
            : scope.devices.containsKey(selected) ? Collections.singletonList(selected) : Collections.emptyList();
        ledger.visitFlows(ids, minutes, now / 60000L, (key, source, in, out, order) -> {
            if (key instanceof ItemKey || key instanceof FluidKey)
                resources.computeIfAbsent(key, ignored -> new Resource(key, order)).amounts.add(in, out);
        });
        List<Resource> ordered = new ArrayList<>(resources.values());
        ordered.sort(Comparator.comparingLong(value -> value.order));
        return new View(ledger, scope, now, devices, ordered, total);
    }

    private static final class Identity {

        final SupplySource source;
        final String line;

        Identity(SupplySource source, String line) {
            this.source = source;
            this.line = line;
        }
    }

    private static final class Scope {

        final SupplyLedger ledger;
        final long sourcesRevision;
        final LinkedHashMap<String, Identity> devices;

        Scope(SupplyLedger ledger, long revision, LinkedHashMap<String, Identity> devices) {
            this.ledger = ledger;
            this.sourcesRevision = revision;
            this.devices = devices;
        }
    }

    private static final class Resource {

        final Object key;
        final long order;
        final Amounts amounts = new Amounts();

        Resource(Object key, long order) {
            this.key = key;
            this.order = order;
        }
    }

    private static final class View {

        final SupplyLedger ledger;
        final Scope scope;
        final long time, generation, structure;
        final List<Device> devices;
        final List<Resource> resources;
        final Device total;

        View(SupplyLedger ledger, Scope scope, long time, List<Device> devices, List<Resource> resources,
            Device total) {
            this.ledger = ledger;
            this.scope = scope;
            this.time = time;
            this.devices = devices;
            this.resources = resources;
            this.total = total;
            generation = ledger.generation();
            structure = ledger.structureRevision();
        }

        int weight() {
            return devices.size() + resources.size() + 1;
        }
    }

    private static final class QueryKey {

        final String line, source;
        final int minutes;

        QueryKey(String line, String source, int minutes) {
            this.line = line;
            this.source = source;
            this.minutes = minutes;
        }

        @Override
        public int hashCode() {
            return (line.hashCode() * 31 + source.hashCode()) * 31 + minutes;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof QueryKey key && minutes == key.minutes
                && line.equals(key.line)
                && source.equals(key.source);
        }
    }

    private static int page(NBTTagCompound root, String prefix, int requested, int size) {
        int pages = Math.max(1, (size + SupplyTracker.PAGE_SIZE - 1) / SupplyTracker.PAGE_SIZE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        root.setInteger(prefix + "Page", page);
        root.setInteger(prefix + "Pages", pages);
        root.setInteger(prefix + "Count", size);
        return page;
    }

    NBTTagCompound binding(SupplySource source) {
        NBTTagCompound tag = new NBTTagCompound();
        Binding binding = effectiveBindings().get(source.id());
        tag.setString("lineId", binding == null ? "" : binding.lineId);
        tag.setString("lineName", binding == null ? "" : lines.getOrDefault(binding.lineId, ""));
        writeParent(tag, binding);
        return tag;
    }

    private void writeParent(NBTTagCompound tag, Binding binding) {
        Binding parent = binding == null ? null : bindings.get(binding.parentId);
        tag.setBoolean("autoBound", parent != null);
        if (parent != null) tag.setTag("bindingParent", parent.source.write());
    }

    private static int end(int page, int size) {
        return Math.min(size, (page + 1) * SupplyTracker.PAGE_SIZE);
    }

    NBTTagCompound write() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList lineTags = new NBTTagList();
        for (Map.Entry<String, String> line : lines.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", line.getKey());
            tag.setString("name", line.getValue());
            lineTags.appendTag(tag);
        }
        root.setTag("lines", lineTags);
        NBTTagList bindingTags = new NBTTagList();
        for (Binding binding : bindings.values()) {
            NBTTagCompound tag = binding.source.write();
            tag.setString("lineId", binding.lineId);
            bindingTags.appendTag(tag);
        }
        root.setTag("bindings", bindingTags);
        return root;
    }

    /** Validate the complete replacement before committing; malformed metadata must never be overwritten. */
    void read(NBTTagCompound root) {
        Map<String, String> nextLines = new LinkedHashMap<>();
        Map<String, Binding> nextBindings = new LinkedHashMap<>();
        NBTTagList list = root.getTagList("lines", 10);
        if (list.tagCount() > MAX_LINES) throw new IllegalArgumentException("Too many production lines");
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            String id = UUID.fromString(tag.getString("id"))
                .toString();
            String name = Utf8Text.limit(
                tag.getString("name")
                    .trim(),
                96);
            if (name.isEmpty() || nextLines.put(id, name) != null)
                throw new IllegalArgumentException("Invalid production line");
        }
        list = root.getTagList("bindings", 10);
        if (list.tagCount() > SupplyLedger.MAX_SOURCES) throw new IllegalArgumentException("Too many device bindings");
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            String lineId = tag.getString("lineId");
            SupplySource source = SupplySource.read(tag);
            if (!nextLines.containsKey(lineId) || !source.hasPosition()
                || !source.player.isEmpty()
                || nextBindings.put(source.id(), new Binding(lineId, source)) != null)
                throw new IllegalArgumentException("Invalid production device binding");
        }
        lines.clear();
        lines.putAll(nextLines);
        bindings.clear();
        bindings.putAll(nextBindings);
        attached.clear();
        attachmentCursor = 0;
        metadataChanged();
    }

    void clear() {
        lines.clear();
        bindings.clear();
        attached.clear();
        attachmentCursor = 0;
        metadataChanged();
    }

    private static final class Binding {

        final String lineId;
        final SupplySource source;
        final String parentId;

        Binding(String lineId, SupplySource source) {
            this(lineId, source, "");
        }

        Binding(String lineId, SupplySource source, String parentId) {
            this.lineId = lineId;
            this.source = source;
            this.parentId = parentId;
        }
    }

    private static final class Amounts {

        long incoming, outgoing;

        void add(long in, long out) {
            incoming = SupplyLedger.add(incoming, in);
            outgoing = SupplyLedger.add(outgoing, out);
        }

        void write(NBTTagCompound tag, String prefix, long observed) {
            String in = prefix.isEmpty() ? "in" : prefix + "In";
            String out = prefix.isEmpty() ? "out" : prefix + "Out";
            tag.setLong(in, incoming);
            tag.setLong(out, outgoing);
            tag.setDouble(in + "PerMinute", observed <= 0 ? 0 : incoming * 60_000d / observed);
            tag.setDouble(out + "PerMinute", observed <= 0 ? 0 : outgoing * 60_000d / observed);
        }
    }

    private static final class Device {

        final SupplySource source;
        final String lineId;
        final Amounts items = new Amounts(), fluids = new Amounts();
        boolean active;

        Device(SupplySource source, String lineId) {
            this.source = source;
            this.lineId = lineId;
        }

        void add(SupplyLedger.Metrics metrics) {
            items.add(metrics.itemIn, metrics.itemOut);
            fluids.add(metrics.fluidIn, metrics.fluidOut);
            active = metrics.itemIn != 0 || metrics.itemOut != 0 || metrics.fluidIn != 0 || metrics.fluidOut != 0;
        }

        void add(SupplyLedger.Flow flow) {
            if (flow.key instanceof ItemKey) items.add(flow.incoming, flow.outgoing);
            else if (flow.key instanceof FluidKey) fluids.add(flow.incoming, flow.outgoing);
            else return;
            active = true;
        }

        NBTTagCompound metrics(long observed) {
            NBTTagCompound tag = new NBTTagCompound();
            items.write(tag, "item", observed);
            fluids.write(tag, "fluid", observed);
            return tag;
        }
    }
}
