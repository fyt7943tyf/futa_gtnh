package com.futa_gtnh.stats;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.api.IoFlowStatsStatus;
import com.futa_gtnh.api.IoNodeFlowDetail;
import com.futa_gtnh.api.IoNodeFlowListSnapshot;
import com.futa_gtnh.api.IoNodeFlowSample;
import com.futa_gtnh.api.IoNodeFlowSummary;
import com.futa_gtnh.api.IoResourceFlow;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import cpw.mods.fml.common.registry.GameData;

/**
 * 把内部记录转成 {@code api} 包的只读 DTO。只在服务端主线程调用。
 *
 * <p>
 * 资源的注册名 / 显示名在这里现场推导：ItemKey → 原型 ItemStack → 注册名 + metadata +
 * 显示名；FluidKey → 流体注册名。推导失败（对应模组被卸载之类）时降级为注册名空串，
 * 行还在、曲线还在，只是网页上没有图标和名字。
 */
public final class IoFlowStatsSnapshots {

    private IoFlowStatsSnapshots() {}

    public static IoFlowStatsStatus status() {
        return new IoFlowStatsStatus(
            IoFlowStats.isLoaded() ? IoFlowStatsStatus.State.READY : IoFlowStatsStatus.State.NOT_READY,
            IoFlowStats.getGeneration());
    }

    public static IoNodeFlowListSnapshot listSnapshot() {
        List<IoNodeFlowSummary> rows = new ArrayList<>();
        for (IoFlowStats.NodeRecord node : IoFlowStats.nodeRecords()) {
            rows.add(summaryOf(node));
        }
        return new IoNodeFlowListSnapshot(
            status(),
            rows,
            intervalSeconds(),
            IoFlowStats.SAMPLE_COUNT,
            System.currentTimeMillis());
    }

    public static IoNodeFlowDetail detail(String key) {
        IoFlowStats.NodeRecord node = IoFlowStats.nodeRecord(key);
        if (node == null) return null;

        int[] order = orderedIndices(node);
        List<IoNodeFlowSample> samples = new ArrayList<>(order.length);
        for (int position = 0; position < order.length; position++) {
            int index = order[position];
            samples.add(
                new IoNodeFlowSample(
                    order.length - 1 - position,
                    node.ringItemIn[index],
                    node.ringItemOut[index],
                    node.ringFluidIn[index],
                    node.ringFluidOut[index]));
        }

        List<IoResourceFlow> resources = new ArrayList<>(node.resources.size());
        for (java.util.Map.Entry<Object, IoFlowStats.ResRecord> entry : node.resources.entrySet()) {
            IoResourceFlow row = resourceOf(entry.getKey(), entry.getValue(), order);
            if (row != null) resources.add(row);
        }

        return new IoNodeFlowDetail(
            status(),
            summaryOf(node),
            intervalSeconds(),
            System.currentTimeMillis(),
            samples,
            resources);
    }

    private static IoNodeFlowSummary summaryOf(IoFlowStats.NodeRecord node) {
        int count = node.ringCount;
        long latestItemIn = 0, latestItemOut = 0, latestFluidIn = 0, latestFluidOut = 0;
        double sumItemIn = 0, sumItemOut = 0, sumFluidIn = 0, sumFluidOut = 0;
        if (count > 0) {
            int newest = (node.ringIndex - 1 + IoFlowStats.SAMPLE_COUNT) % IoFlowStats.SAMPLE_COUNT;
            latestItemIn = node.ringItemIn[newest];
            latestItemOut = node.ringItemOut[newest];
            latestFluidIn = node.ringFluidIn[newest];
            latestFluidOut = node.ringFluidOut[newest];
            for (int i = 0; i < IoFlowStats.SAMPLE_COUNT; i++) {
                sumItemIn += node.ringItemIn[i];
                sumItemOut += node.ringItemOut[i];
                sumFluidIn += node.ringFluidIn[i];
                sumFluidOut += node.ringFluidOut[i];
            }
        }

        return new IoNodeFlowSummary(
            node.key,
            node.dim,
            node.x,
            node.y,
            node.z,
            node.type == 1 ? "shared_terminal" : "io_node",
            node.name == null ? "" : node.name,
            modeName(node),
            node.enabled,
            node.online,
            count,
            latestItemIn,
            latestItemOut,
            latestFluidIn,
            latestFluidOut,
            count > 0 ? sumItemIn / count : 0.0,
            count > 0 ? sumItemOut / count : 0.0,
            count > 0 ? sumFluidIn / count : 0.0,
            count > 0 ? sumFluidOut / count : 0.0,
            node.totalItemIn,
            node.totalItemOut,
            node.totalFluidIn,
            node.totalFluidOut,
            node.resources.size());
    }

    private static String modeName(IoFlowStats.NodeRecord node) {
        switch (node.statsMode) {
            case IN:
                return "IN";
            case OUT:
                return "OUT";
            default:
                return "BOTH";
        }
    }

    private static IoResourceFlow resourceOf(Object key, IoFlowStats.ResRecord record, int[] order) {
        String resourceType;
        String registryName = "";
        String metadata = null;
        String displayName = "";
        boolean hasNbt = false;
        boolean aggregate = false;

        if (key == IoFlowStats.OTHERS_ITEM || key == IoFlowStats.OTHERS_FLUID) {
            resourceType = record.fluid ? "fluid" : "item";
            aggregate = true;
            displayName = "其他（超出跟踪上限）";
        } else if (record.fluid && key instanceof FluidKey) {
            resourceType = "fluid";
            FluidStack prototype = ((FluidKey) key).prototype();
            if (prototype != null && prototype.getFluid() != null) {
                String name = FluidRegistry.getFluidName(prototype.getFluid());
                registryName = name == null ? "" : name;
                try {
                    displayName = prototype.getLocalizedName();
                } catch (RuntimeException e) {
                    displayName = registryName;
                }
                hasNbt = prototype.tag != null && !prototype.tag.hasNoTags();
            }
        } else if (!record.fluid && key instanceof ItemKey) {
            resourceType = "item";
            ItemStack prototype = ((ItemKey) key).prototype();
            if (prototype != null && prototype.getItem() != null) {
                String name = GameData.getItemRegistry()
                    .getNameForObject(prototype.getItem());
                registryName = name == null ? "" : name;
                metadata = Integer.toString(prototype.getItemDamage());
                try {
                    displayName = prototype.getDisplayName();
                } catch (RuntimeException e) {
                    displayName = registryName;
                }
                hasNbt = prototype.getTagCompound() != null;
            }
        } else {
            return null; // 键和类型对不上：跳过这一行
        }

        int length = order.length;
        long[] historyIn = new long[length];
        long[] historyOut = new long[length];
        long sumIn = 0, sumOut = 0;
        for (int position = 0; position < length; position++) {
            int index = order[position];
            historyIn[position] = record.ringIn[index];
            historyOut[position] = record.ringOut[index];
            sumIn += record.ringIn[index];
            sumOut += record.ringOut[index];
        }

        long latestIn = length > 0 ? historyIn[length - 1] : 0L;
        long latestOut = length > 0 ? historyOut[length - 1] : 0L;
        return new IoResourceFlow(
            resourceType,
            aggregate ? null : registryName,
            aggregate ? null : metadata,
            displayName,
            hasNbt,
            aggregate,
            latestIn,
            latestOut,
            length > 0 ? (double) sumIn / length : 0.0,
            length > 0 ? (double) sumOut / length : 0.0,
            record.totalIn,
            record.totalOut,
            historyIn,
            historyOut);
    }

    /**
     * 环形历史的读取顺序（最老 → 最新）。
     *
     * <p>
     * 没写满时有效数据在 0..count-1，写入位置就是 count（最老的是 0）；
     * 写满后写入位置始终指向「最老的那个」，从它开始绕一圈。
     */
    private static int[] orderedIndices(IoFlowStats.NodeRecord node) {
        int count = node.ringCount;
        int[] order = new int[count];
        int start = count < IoFlowStats.SAMPLE_COUNT ? 0 : node.ringIndex;
        for (int i = 0; i < count; i++) {
            order[i] = (start + i) % IoFlowStats.SAMPLE_COUNT;
        }
        return order;
    }

    private static int intervalSeconds() {
        return IoFlowStats.SAMPLE_INTERVAL_TICKS / 20;
    }
}
