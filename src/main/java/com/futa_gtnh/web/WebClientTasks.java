package com.futa_gtnh.web;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.client.Minecraft;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 把「必须在客户端主线程做的事」从 HTTP 线程搬过去。
 *
 * <p>
 * 浏览器的请求跑在 HTTP 自己的线程池里，而它们要读的东西（玩家背包、共享背包缓存、
 * 资源包里的贴图字节）全都属于客户端的单线程世界：共享背包缓存是普通
 * {@code HashMap}，随时可能被同步包改写；资源管理器在资源重载期间会换实例。
 * 直接从 HTTP 线程摸这些，迟早会在某次「手机上点得快、游戏里正好在切维度」时炸掉。
 *
 * <p>
 * 所以这里是一条单向通道：HTTP 线程 {@link #call} 提交一个任务并阻塞等结果，
 * 客户端 tick 里 {@link #tick} 按预算执行。超时就抛 {@link TimeoutException}，
 * 由调用方翻译成「游戏没在跑 / 已经暂停」的提示 —— 单人游戏按 Esc 暂停时
 * 客户端 tick 会停，这是预期行为，不是 bug。
 */
public final class WebClientTasks {

    private WebClientTasks() {}

    /** 每 tick 最多执行几个任务（免得一个手机刷页面把这一帧拖爆）。 */
    private static final int MAX_TASKS_PER_TICK = 4;

    /** 每 tick 最多占用多少毫秒。 */
    private static final long MAX_MILLIS_PER_TICK = 12L;

    private static final ConcurrentLinkedQueue<Task> QUEUE = new ConcurrentLinkedQueue<>();

    /** 最近一次在客户端线程上跑任务失败的原因（只用于日志去重）。 */
    private static volatile String lastError;

    private static final class Task implements Runnable {

        private final Callable<?> callable;
        private final CompletableFuture<Object> future = new CompletableFuture<>();

        Task(Callable<?> callable) {
            this.callable = callable;
        }

        @Override
        public void run() {
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }

    /**
     * 在客户端主线程上执行 {@code callable} 并等它返回。
     *
     * @throws TimeoutException 客户端在超时前没有 tick（未进游戏 / 暂停 / 正在加载）
     */
    @SuppressWarnings("unchecked")
    public static <T> T call(Callable<T> callable, long timeoutMillis)
        throws TimeoutException, InterruptedException, ExecutionException {
        Task task = new Task(callable);
        QUEUE.add(task);
        try {
            return (T) task.future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时之后把任务摘掉：留在队列里的话，玩家几秒后解除暂停时它会突然执行，
            // 而那边等结果的 HTTP 线程早就走了，白跑一趟还可能干扰后续状态
            QUEUE.remove(task);
            throw e;
        }
    }

    /** 在客户端 tick 里调用。 */
    public static void tick() {
        if (QUEUE.isEmpty()) return;

        long deadline = System.currentTimeMillis() + MAX_MILLIS_PER_TICK;
        for (int i = 0; i < MAX_TASKS_PER_TICK; i++) {
            Task task = QUEUE.poll();
            if (task == null) return;

            task.run();
            if (task.future.isCompletedExceptionally()) {
                task.future.whenComplete((value, error) -> report(error));
            }

            if (System.currentTimeMillis() >= deadline) return;
        }
    }

    private static void report(Throwable t) {
        String message = String.valueOf(t);
        if (!message.equals(lastError)) {
            lastError = message;
            FutaGtnhMod.LOG.warn("网页配方：客户端任务执行失败", t);
        }
    }

    /** 当前是否在游戏里（没进世界时背包/共享缓存都是空的，调用方据此给出更准确的提示）。 */
    public static boolean inGame() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft != null && minecraft.thePlayer != null;
    }
}
