package com.futa_gtnh.client.nei;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketCraftResult;
import com.futa_gtnh.network.PacketStorageAction;

/**
 * NEI 自动合成的客户端请求桥。
 *
 * <p>
 * NEI 的 {@code AutoCraftingManager} 在独立线程里调用 overlay handler，并把
 * {@code craft()} 的返回值当作「服务器已经完成」来推进合成链。共享存储的合成
 * 必须由服务端执行，所以这里让 NEI 工作线程等待当前这一个请求的回执；客户端
 * 主线程不等待，仍然可以处理窗口同步和回执包。
 */
public final class NeiCraftStep {

    /** 网络异常时最多等待这么久，不能让 NEI 的后台线程永久挂住。 */
    private static final long WAIT_TIMEOUT_SECONDS = 15L;

    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong(1L);
    private static final ConcurrentMap<Long, Pending> PENDING = new ConcurrentHashMap<>();

    private NeiCraftStep() {}

    /**
     * 请求服务端执行当前 NEI 配方步骤，并等待权威回执。
     *
     * @return 只有请求中的合成次数全部完成时才返回 true；部分完成也返回 false，
     *         防止 NEI 把部分结果误认为完整的一批继续计算。
     */
    public static boolean request(NBTTagCompound layout, int multiplier) {
        if (layout == null || multiplier <= 0) return false;

        long requestId = nextRequestId();
        Pending pending = new Pending();
        PENDING.put(requestId, pending);

        try {
            NetworkHandler.INSTANCE.sendToServer(
                PacketStorageAction.craft(PacketStorageAction.AUTOCRAFT, (NBTTagCompound) layout.copy(), multiplier)
                    .withRequestId(requestId));

            if (!pending.done.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                FutaGtnhMod.LOG.warn("NEI 自动合成步骤等待服务端回执超时（请求 {}，期望 {} 次）", requestId, multiplier);
                return false;
            }

            if (pending.status != PacketCraftResult.COMPLETED || pending.crafted < multiplier) {
                FutaGtnhMod.LOG.info(
                    "NEI 自动合成步骤未完整完成（请求 {}，期望 {} 次，实际 {} 次，状态 {}）",
                    requestId,
                    multiplier,
                    pending.crafted,
                    pending.status);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            FutaGtnhMod.LOG.warn("NEI 自动合成步骤等待被中断（请求 {}）", requestId);
            return false;
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("NEI 自动合成步骤发包失败（请求 {}）", requestId, t);
            return false;
        } finally {
            PENDING.remove(requestId);
        }
    }

    /** 由服务端回执包唤醒对应的 NEI 工作线程。 */
    public static void complete(long requestId, int crafted, byte status) {
        if (requestId == 0L) return;

        Pending pending = PENDING.get(requestId);
        if (pending == null) return;

        pending.crafted = Math.max(0, crafted);
        pending.status = status;
        pending.done.countDown();
    }

    private static long nextRequestId() {
        long requestId = NEXT_REQUEST_ID.getAndIncrement();
        if (requestId == 0L) {
            requestId = NEXT_REQUEST_ID.getAndIncrement();
        }
        return requestId;
    }

    private static final class Pending {

        private final CountDownLatch done = new CountDownLatch(1);
        private volatile int crafted;
        private volatile byte status = PacketCraftResult.FAILED;

        private Pending() {}
    }
}
