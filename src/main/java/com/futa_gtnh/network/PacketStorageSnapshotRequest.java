package com.futa_gtnh.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.shared.SharedStorageManager;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：请给我一份共享存储全量快照（空包，没有任何参数）。
 *
 * <p>
 * 为什么需要单独一个包：全量快照原本只在<b>打开共享终端界面</b>时下发
 * （见 {@code common/GuiHandler}），之后的增量也只发给「正开着界面的人」
 * （{@code SharedStorageManager.viewers()}）。而手机网页要在<b>没开界面</b>的时候
 * 算「你已经有多少、还缺多少」，拿不到那份快照就只能算成「什么都没有」。
 *
 * <p>
 * 不复用 {@code PacketStorageAction} 的原因：那边的第一道校验是
 * 「玩家必须真的开着终端界面」，那是它的信任边界（防伪造包改存储）。
 * 这里只是一个只读的「把库存告诉我」，没必要也不应该去松动那道校验 ——
 * 单独开一个包，语义清楚，边界不动。
 *
 * <p>
 * 服务端做了频率限制：快照是一整份压缩过的全量数据（几千条目、几十 KB），
 * 正常客户端几秒才问一次，被人拿去刷就是白白占带宽。
 */
public class PacketStorageSnapshotRequest implements IMessage {

    /** 同一个玩家两次请求之间的最小间隔（tick）。 */
    private static final int MIN_INTERVAL_TICKS = 100;

    /** 玩家 UUID -> 上次响应时的 ticksExisted。 */
    private static final Map<UUID, Integer> LAST_SERVED = new HashMap<>();

    public PacketStorageSnapshotRequest() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // 空包，没有载荷
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 空包，没有载荷
    }

    public static class Handler implements IMessageHandler<PacketStorageSnapshotRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketStorageSnapshotRequest message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            UUID id = player.getUniqueID();
            int now = player.ticksExisted;
            synchronized (LAST_SERVED) {
                Integer last = LAST_SERVED.get(id);
                if (last != null && now - last < MIN_INTERVAL_TICKS) return null;
                // 长期运行时不留下无限增长的键：人数不多，超了就整体丢掉重来
                if (LAST_SERVED.size() > 256) LAST_SERVED.clear();
                LAST_SERVED.put(id, now);
            }

            SharedStorageManager.sendSnapshotTo(player);
            return null;
        }
    }
}
