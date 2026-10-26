package com.futa_gtnh.me;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;

/** Player requests use AE's asynchronous planner and native CPUs; no independent crafting executor exists here. */
public final class PublicMeCrafting {

    private static final int MAX_SESSIONS = 128;
    private static final long PLAN_TTL = 6000;
    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();
    private static long ticks;

    private PublicMeCrafting() {}

    private static final class Session {

        EntityPlayerMP player;
        final String planId = UUID.randomUUID()
            .toString();
        IGrid grid;
        IAEStack<?> output;
        Future<ICraftingJob> future;
        ICraftingJob<?> job;
        ICraftingLink link;
        NBTTagList rows = new NBTTagList();
        long created;
        long bytes;
        int state;
        String message;
    }

    /** States: 0 absent, 1 calculating, 2 ready, 3 missing, 4 submitted, 5 done, 6 canceled, 7 failed. */
    public static String plan(EntityPlayerMP player, IAEStack<?> output) {
        if (player == null || output == null || output.getStackSize() < 1 || output.getStackSize() > 1_000_000_000L) {
            return "合成数量必须为 1 至 1,000,000,000";
        }
        String denied = denial(player);
        if (denied != null) return denied;
        Session previous = SESSIONS.get(player.getUniqueID());
        if (previous != null && previous.future != null && !previous.future.isDone()) {
            return "已有合成计划正在计算，请等待完成或先取消";
        }
        if (previous != null && previous.link != null && !previous.link.isDone() && !previous.link.isCanceled()) {
            return "已有公共合成任务运行中，请先查看状态或取消该任务";
        }
        if (previous == null && SESSIONS.size() >= MAX_SESSIONS) return "公共合成会话已满，请稍后再试";
        if (previous != null && previous.future != null) previous.future.cancel(false);
        IGrid grid = PublicMeNetwork.grid();
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        Session session = new Session();
        session.player = player;
        session.grid = grid;
        session.output = output.copy();
        session.created = ticks;
        session.state = 1;
        session.message = "正在计算 AE 合成计划";
        try {
            session.future = crafting.beginCraftingJob(
                player.worldObj,
                grid,
                new PlayerSource(player, PublicMeNetwork.core()),
                session.output,
                null);
            if (session.future == null) return "AE 未能创建合成计划";
            SESSIONS.put(player.getUniqueID(), session);
            return session.message;
        } catch (RuntimeException error) {
            return "AE 无法计算该合成计划：" + safeMessage(error.getMessage());
        }
    }

    public static String confirm(EntityPlayerMP player) {
        Session session = player == null ? null : SESSIONS.get(player.getUniqueID());
        return confirm(player, session == null ? null : session.planId);
    }

    public static String confirm(EntityPlayerMP player, String expectedPlanId) {
        String denied = denial(player);
        if (denied != null) return denied;
        Session session = SESSIONS.get(player.getUniqueID());
        if (session != null && !session.planId.equals(expectedPlanId)) return "合成计划已更新，请刷新后再确认";
        if (session == null || session.state != 2 || session.job == null) return "没有可提交的合成计划";
        if (session.grid != PublicMeNetwork.grid() || ticks - session.created > PLAN_TTL) {
            session.state = 7;
            return session.message = "网络已变更或计划已过期，请重新计算";
        }
        ICraftingGrid crafting = session.grid.getCache(ICraftingGrid.class);
        ICraftingLink link;
        try {
            link = crafting.submitJob(session.job, null, null, false, new PlayerSource(player, PublicMeNetwork.core()));
        } catch (RuntimeException failure) {
            // A CPU may already have accepted materials. Do not keep a resubmittable plan after an uncertain result.
            session.state = 7;
            session.job = null;
            return session.message = "提交结果不确定，请先在原生 AE 合成状态终端核对任务，再重新规划";
        }
        if (link == null) return session.message = "AE 未接受任务：请检查可用合成 CPU、CPU 容量和当前材料";
        session.link = link;
        session.job = null;
        session.state = 4;
        return session.message = "已提交到原生 AE 合成 CPU；产物将进入公共 ME 网络";
    }

    public static String status(EntityPlayerMP player) {
        if (player == null) return PublicMeNetwork.status();
        Session session = SESSIONS.get(player.getUniqueID());
        if (session == null) return "没有本次登录会话的公共合成任务；既有任务可在原生 AE 合成状态终端查看";
        updateLink(session);
        return session.message;
    }

    public static String cancel(EntityPlayerMP player) {
        Session session = player == null ? null : SESSIONS.get(player.getUniqueID());
        return cancel(player, session == null ? null : session.planId);
    }

    public static String cancel(EntityPlayerMP player, String expectedPlanId) {
        if (player == null) return "该操作需要玩家身份";
        Session session = SESSIONS.get(player.getUniqueID());
        if (session == null) return "没有可取消的合成任务";
        if (!session.planId.equals(expectedPlanId)) return "合成计划已更新，请刷新后再取消";
        if (session.future != null) session.future.cancel(false);
        if (session.link != null && !session.link.isDone()) session.link.cancel();
        session.future = null;
        session.job = null;
        session.state = 6;
        return session.message = "已取消；已投入材料由 AE 合成 CPU 按原生规则退回";
    }

    public static NBTTagCompound snapshot(EntityPlayerMP player) {
        NBTTagCompound tag = new NBTTagCompound();
        Session session = player == null ? null : SESSIONS.get(player.getUniqueID());
        tag.setBoolean("online", PublicMeNetwork.isOnline());
        tag.setString("message", status(player));
        if (session == null) return tag;
        tag.setString("planId", session.planId);
        tag.setInteger("state", session.state);
        tag.setLong("bytes", session.bytes);
        tag.setLong("amount", session.output.getStackSize());
        Object key = keyOf(session.output);
        if (key != null) tag.setTag("output", SupplyTracker.writeKey(key));
        tag.setTag("rows", session.rows.copy());
        if (session.link != null) tag.setString("jobId", session.link.getCraftingID());
        return tag;
    }

    public static void tick() {
        ticks++;
        Iterator<Session> sessions = SESSIONS.values()
            .iterator();
        while (sessions.hasNext()) {
            Session session = sessions.next();
            if (session.future != null && session.future.isDone()) {
                try {
                    session.job = session.future.get();
                    session.bytes = session.job.getByteTotal();
                    session.rows = describePlan(session);
                    session.state = session.job.isSimulation() ? 3 : 2;
                    session.message = session.state == 2 ? "计划就绪，需要 " + session.bytes + " 字节 CPU 存储；确认后提交"
                        : "计划缺少材料或合成路径：" + safeMessage(session.job.getErrorMessage());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    session.state = 7;
                    session.message = "合成计算被中断";
                } catch (ExecutionException | RuntimeException failure) {
                    session.state = 7;
                    session.message = "合成计划计算失败：" + safeMessage(failure.getMessage());
                }
                session.future = null;
                session.created = ticks;
                if (!session.player.playerNetServerHandler.netManager.isChannelOpen()) continue;
                session.player.addChatMessage(new ChatComponentText(session.message));
            }
            updateLink(session);
            if (ticks - session.created > PLAN_TTL && session.state != 4) {
                if (session.future != null) session.future.cancel(false);
                sessions.remove();
            }
        }
    }

    private static NBTTagList describePlan(Session session) {
        NBTTagList rows = new NBTTagList();
        IItemList<IAEStack<?>> plan = AEApi.instance()
            .storage()
            .createAEStackList();
        session.job.populatePlan(plan);
        for (IAEStack<?> planned : plan) {
            if (rows.tagCount() >= 256) break;
            Object key = keyOf(planned);
            if (key == null) continue;
            NBTTagCompound row = SupplyTracker.writeKey(key);
            long required = Math.max(0L, planned.getStackSize());
            long stored = required;
            if (session.job.isSimulation() && required > 0) {
                IAEStack<?> request = planned.copy();
                request.reset();
                request.setStackSize(required);
                IAEStack<?> available = session.job.getStorageAtBeginning()
                    .extractItems(
                        request,
                        Actionable.SIMULATE,
                        new PlayerSource(session.player, PublicMeNetwork.core()));
                stored = available == null ? 0 : Math.min(required, Math.max(0L, available.getStackSize()));
            }
            row.setLong("stored", stored);
            row.setLong("craft", Math.max(0L, planned.getCountRequestable()));
            row.setLong("missing", required - stored);
            rows.appendTag(row);
        }
        return rows;
    }

    private static Object keyOf(IAEStack<?> stack) {
        return stack instanceof IAEItemStack item ? ItemKey.of(item.getItemStack())
            : stack instanceof IAEFluidStack fluid ? FluidKey.of(fluid.getFluidStack()) : null;
    }

    private static String denial(EntityPlayerMP player) {
        if (player == null) return "该操作需要玩家身份";
        if (!PublicMeNetwork.isOnline()) return PublicMeNetwork.status();
        ISecurityGrid security = PublicMeNetwork.grid()
            .getCache(ISecurityGrid.class);
        return security != null && !security.hasPermission(player, SecurityPermissions.CRAFT) ? "没有该网络的合成权限" : null;
    }

    private static void updateLink(Session session) {
        if (session.link == null || session.state != 4) return;
        if (session.link.isDone()) {
            session.state = 5;
            session.created = ticks;
            session.message = "AE 合成任务已完成";
        } else if (session.link.isCanceled()) {
            session.state = 6;
            session.created = ticks;
            session.message = "AE 合成任务已取消";
        }
    }

    private static String safeMessage(String message) {
        if (message == null || message.isEmpty()) return "请查看 AE 合成计划";
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    public static void clear() {
        for (Session session : SESSIONS.values()) {
            if (session.future != null) session.future.cancel(false);
        }
        // Submitted standalone jobs are persisted and recovered by AE's CPU, not canceled on shutdown.
        SESSIONS.clear();
        ticks = 0;
    }
}
