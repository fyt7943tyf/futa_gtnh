package com.futa_gtnh.reactor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.reactor.ReactorAeTerminal.TerminalHandle;
import com.futa_gtnh.reactor.ReactorDeployer.ManifestRow;

import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;

/**
 * 「缺失下单」的批量订单状态机 —— 一键把一份布局清单里所有缺失材料的差额
 * 各下一份 AE 合成订单（骨架照抄 {@code me.PublicMeCrafting}，那边是单玩家
 * 单会话的 GUI 流程，这里是无 GUI 的批量版）。
 *
 * <p>
 * 生命周期：{@link #order} 逐行 {@code beginCraftingJob}（异步计算）→ tick 里
 * 等计划完成 → 计划完整就<b>自动</b> {@code submitJob(job, null, …)}（standalone
 * 任务，产物自动回注终端网络的 ME 存储）；计划缺材料 / CPU 拒收 / 计算失败则
 * 退掉在途账并逐行聊天报错。订单产物落进 ME 网络后，部署取料的 AE 段
 * （{@code ReactorDeployer#takeFromAeLoose}）就能拿到 —— 这就是闭环。
 *
 * <p>
 * <b>在途账（{@link #PENDING}）</b>：下单瞬间按行加账，行被放弃（缺材料/失败/
 * 取消）时退账；任务完成不退账 —— 产物落袋后清单的可用量自然上涨。账是内存的，
 * AE CPU 任务重启后仍在：重启窗口内理论上可重复下单，产物落袋后自纠正（宁可
 * 少订不可多订的方向是安全的）。
 */
public final class ReactorAeOrders {

    private ReactorAeOrders() {}

    /** 与 PublicMeCrafting 同款保鲜窗口：计划算好但一直没能提交就放弃（tick）。 */
    private static final long BATCH_TTL = 6000L;

    /** 一行订单（一种材料的差额）。 */
    private static final class Row {

        final String label;
        final Item item;
        final int meta;
        final int amount;

        Future<ICraftingJob> future;
        ICraftingJob job;
        ICraftingLink link;

        Row(String label, Item item, int meta, int amount) {
            this.label = label;
            this.item = item;
            this.meta = meta;
            this.amount = amount;
        }
    }

    /** 一个玩家的整批订单（一键产生的全部行）。 */
    private static final class Batch {

        final EntityPlayerMP player;
        final TerminalHandle handle;
        final long layoutId;
        final boolean looseNbt;
        final List<Row> rows = new ArrayList<>();
        long created;

        Batch(EntityPlayerMP player, TerminalHandle handle, long layoutId, boolean looseNbt) {
            this.player = player;
            this.handle = handle;
            this.layoutId = layoutId;
            this.looseNbt = looseNbt;
        }
    }

    private static final Map<UUID, Batch> BATCHES = new LinkedHashMap<>();
    /** 全服在途订单账：物品id:meta → 数量（宽松口径）。 */
    private static final Map<Long, Integer> PENDING = new HashMap<>();
    private static long ticks;

    /** @return (物品, 耐久) 的全服在途订单量；清单展示与差额防重复共用 */
    public static int pendingAmount(Item item, int meta) {
        Integer pending = PENDING.get(pendingKey(item, meta));
        return pending == null ? 0 : pending;
    }

    /** @return 玩家是否有未走完的批次（有则不允许重复下单） */
    public static boolean hasActiveBatch(EntityPlayerMP player) {
        return player != null && BATCHES.containsKey(player.getUniqueID());
    }

    // ==================================================================
    // 下单入口
    // ==================================================================

    /**
     * 一键缺失下单：按当前清单对每种「缺失且有样板」的材料按差额下单。
     * 结果全部走聊天栏汇报。
     */
    public static void order(EntityPlayerMP player, long layoutId, boolean looseNbt) {
        if (!Config.reactorOrderEnable) return;

        ReactorLayout layout = ReactorLayoutManager.copyOf(layoutId);
        if (layout == null) {
            chat(player, "futa_gtnh.reactor.msg.order_no_layout");
            return;
        }
        if (BATCHES.containsKey(player.getUniqueID())) {
            chat(player, "futa_gtnh.reactor.msg.order_busy");
            return;
        }
        TerminalHandle handle = ReactorAeTerminal.find(player);
        if (handle == null) {
            chat(player, "futa_gtnh.reactor.msg.order_no_terminal");
            return;
        }
        if (!handle.canCraft(player)) {
            chat(player, "futa_gtnh.reactor.msg.order_no_permission");
            return;
        }

        // 差额 = 需求 - 玩家 - 共享背包 - AE网络 - 在途订单（宽松口径，防重复下单）
        List<Row> accepted = new ArrayList<>();
        List<String> noPattern = new ArrayList<>();
        for (ManifestRow row : ReactorDeployer.buildManifest(player, layout, looseNbt)) {
            int shortfall = row.needed - row.availablePlayer
                - row.availableStorage
                - row.availableAe
                - pendingAmount(row.display.getItem(), row.display.getItemDamage());
            if (shortfall <= 0) continue;
            String label = row.display.getDisplayName();
            if (accepted.size() >= Config.reactorOrderMaxPending) {
                noPattern.add(label);
                continue;
            }
            if (!ReactorAeTerminal.hasPattern(handle, row.display.getItem(), row.display.getItemDamage())) {
                noPattern.add(label);
                continue;
            }
            Row order = new Row(label, row.display.getItem(), row.display.getItemDamage(), shortfall);
            try {
                order.future = handle.crafting()
                    .beginCraftingJob(
                        player.worldObj,
                        handle.grid,
                        new PlayerSource(player, null),
                        outputStack(order.item, order.meta, shortfall),
                        null);
            } catch (RuntimeException failure) {
                FutaGtnhMod.LOG.warn("反应堆缺失下单：{} 的合成计划创建失败", label, failure);
                noPattern.add(label);
                continue;
            }
            accepted.add(order);
        }

        if (accepted.isEmpty()) {
            chat(player, "futa_gtnh.reactor.msg.order_empty");
            return;
        }
        // 无线操作的能耗：整批一笔，扣不起就整批作废（future 还只是计算，取消无副作用）
        if (!ReactorAeTerminal.drainPower(handle, player, Config.reactorOrderAeCost)) {
            for (Row order : accepted) {
                if (order.future != null) order.future.cancel(false);
            }
            chat(player, "futa_gtnh.reactor.msg.order_no_power");
            return;
        }

        Batch batch = new Batch(player, handle, layoutId, looseNbt);
        batch.rows.addAll(accepted);
        batch.created = ticks;
        BATCHES.put(player.getUniqueID(), batch);
        for (Row order : accepted) {
            PENDING.merge(pendingKey(order.item, order.meta), order.amount, Integer::sum);
        }

        chat(player, "futa_gtnh.reactor.msg.order_head", accepted.size());
        for (String label : noPattern) {
            chat(player, "futa_gtnh.reactor.msg.order_row_rejected", label);
        }
    }

    // ==================================================================
    // 每 tick 推进
    // ==================================================================

    public static void tick() {
        ticks++;
        if (BATCHES.isEmpty()) return;

        Iterator<Batch> batches = BATCHES.values()
            .iterator();
        List<Batch> previewDirty = new ArrayList<>();
        while (batches.hasNext()) {
            Batch batch = batches.next();
            Iterator<Row> rows = batch.rows.iterator();
            while (rows.hasNext()) {
                Row row = rows.next();

                if (row.future != null && row.future.isDone()) {
                    Future<ICraftingJob> future = row.future;
                    row.future = null;
                    try {
                        row.job = future.get();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread()
                            .interrupt();
                        refund(row);
                        report(batch, "futa_gtnh.reactor.msg.order_row_error", row.label, "interrupted");
                        rows.remove();
                        previewDirty.add(batch);
                        continue;
                    } catch (ExecutionException | RuntimeException failure) {
                        refund(row);
                        report(batch, "futa_gtnh.reactor.msg.order_row_error", row.label, message(failure));
                        rows.remove();
                        previewDirty.add(batch);
                        continue;
                    }
                    if (row.job.isSimulation()) {
                        // 计划缺材料或缺路径：AE 的报错原文会指名缺什么
                        String detail = messageText(row.job.getErrorMessage());
                        row.job = null;
                        refund(row);
                        report(batch, "futa_gtnh.reactor.msg.order_row_missing", row.label, detail);
                        rows.remove();
                        previewDirty.add(batch);
                        continue;
                    }
                }

                if (row.job != null && row.link == null) {
                    // 计划完整：自动提交。standalone 任务（requester=null）的产物
                    // 由 AE CPU 自动回注网络 ME 存储 —— 部署取料的 AE 段接着就能用
                    ICraftingLink link;
                    try {
                        ICraftingGrid crafting = batch.handle.crafting();
                        link = crafting == null ? null
                            : crafting.submitJob(row.job, null, null, true, new PlayerSource(batch.player, null));
                    } catch (RuntimeException failure) {
                        // 提交异常时 CPU 可能已经收了材料 —— 与 PublicMeCrafting 同款
                        // 保守处理：在途账不退（产物若出现会直接进网络），只报错提醒核对
                        row.job = null;
                        report(batch, "futa_gtnh.reactor.msg.order_row_error", row.label, message(failure));
                        rows.remove();
                        previewDirty.add(batch);
                        continue;
                    }
                    row.job = null;
                    if (link == null) {
                        refund(row);
                        report(batch, "futa_gtnh.reactor.msg.order_row_no_cpu", row.label);
                        rows.remove();
                        previewDirty.add(batch);
                        continue;
                    }
                    row.link = link;
                    report(batch, "futa_gtnh.reactor.msg.order_row_submitted", row.label, row.amount);
                }

                if (row.link != null && (row.link.isDone() || row.link.isCanceled())) {
                    if (row.link.isDone()) {
                        // 产物已落网络：不退在途账，清单可用量自然上涨
                        report(batch, "futa_gtnh.reactor.msg.order_row_done", row.label, row.amount);
                    } else {
                        // 玩家/AE 侧取消：材料由 CPU 退回网络，产物不会出现
                        refund(row);
                        report(batch, "futa_gtnh.reactor.msg.order_row_canceled", row.label);
                    }
                    rows.remove();
                    previewDirty.add(batch);
                    continue;
                }

                if (row.link == null && row.job == null && row.future == null) continue;
                if (row.link == null && ticks - batch.created > BATCH_TTL) {
                    // 计划算好了却一直没能提交（网络反复变动/玩家长期离线）
                    if (row.future != null) row.future.cancel(false);
                    refund(row);
                    report(batch, "futa_gtnh.reactor.msg.order_row_expired", row.label);
                    rows.remove();
                    previewDirty.add(batch);
                }
            }
            if (batch.rows.isEmpty()) batches.remove();
        }

        // 有行出了结果的批次重推一次预览，清单上的可用量/在途数立即可见
        for (Batch batch : previewDirty) {
            if (!batch.player.playerNetServerHandler.netManager.isChannelOpen()) continue;
            ReactorLayout layout = ReactorLayoutManager.copyOf(batch.layoutId);
            if (layout != null) ReactorLayoutManager.sendPreviewTo(batch.player, layout, batch.looseNbt);
        }
    }

    /** 玩家登出：清批次。未提交的行退在途账；已提交的 standalone 任务让 AE CPU 跑完。 */
    public static void forget(UUID playerId) {
        Batch batch = BATCHES.remove(playerId);
        if (batch == null) return;
        for (Row row : batch.rows) {
            if (row.future != null) {
                row.future.cancel(false);
                refund(row);
            } else if (row.job != null) {
                refund(row);
            }
            // 已提交（link != null）的行：任务在 CPU 上继续，产物会进网络；
            // 在途账保留到自然完成前的窗口，重复下单会被它挡住（方向安全）
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 下单目标一律是「全新变体」（样板产出就是全新件，带损耗的 NBT 没有样板）。 */
    private static IAEItemStack outputStack(Item item, int meta, int amount) {
        return AEItemStack.create(new ItemStack(item, amount, meta));
    }

    private static void refund(Row row) {
        long key = pendingKey(row.item, row.meta);
        Integer remaining = PENDING.merge(key, -row.amount, Integer::sum);
        if (remaining != null && remaining <= 0) PENDING.remove(key);
    }

    private static long pendingKey(Item item, int meta) {
        return ((long) Item.getIdFromItem(item) << 32) | (meta & 0xFFFFFFFFL);
    }

    private static void chat(EntityPlayerMP player, String key, Object... args) {
        if (player == null) return;
        player.addChatMessage(new ChatComponentTranslation(key, args));
    }

    /** 行级汇报：玩家在线才发，顺带记日志（排查「为什么没下单」全靠这几行）。 */
    private static void report(Batch batch, String key, Object... args) {
        chat(batch.player, key, args);
        FutaGtnhMod.LOG.info("反应堆缺失下单：" + key, args);
    }

    private static String message(Throwable error) {
        return messageText(error == null ? null : error.getMessage());
    }

    private static String messageText(String text) {
        return text == null || text.isEmpty() ? "未知原因" : text;
    }
}
