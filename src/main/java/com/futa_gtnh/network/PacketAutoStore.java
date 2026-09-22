package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.exchange.AutoStore;
import com.futa_gtnh.inventory.ContainerSharedTerminal;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：把「拾取自动入库」设成某个值。
 *
 * <p>
 * 请求和同步刻意做成<b>两个消息类</b>而不是一个类注册两次。
 * {@code SimpleIndexedCodec} 内部是用「消息类 -&gt; discriminator」的正向表来编码的，
 * 同一个类注册两次会把那张表覆盖掉，剩下哪个 discriminator 取决于注册顺序 ——
 * 能跑，但属于靠巧合。拆开之后没有任何歧义。
 *
 * <p>
 * 状态是<b>服务端权威</b>的：这里只负责把玩家的意图报上去，
 * 真正的值由服务端写进玩家存档，然后回一份 {@link PacketAutoStoreSync}。
 */
public class PacketAutoStore implements IMessage {

    private boolean enabled;

    public PacketAutoStore() {}

    public PacketAutoStore(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        enabled = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static class Handler implements IMessageHandler<PacketAutoStore, IMessage> {

        @Override
        public IMessage onMessage(PacketAutoStore message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            // 开着共享存储界面才受理，和别的操作保持同一条校验口径
            if (!(player.openContainer instanceof ContainerSharedTerminal)) return null;

            AutoStore.setEnabled(player, message.enabled);

            // 回权威值。客户端点按钮时只是本地预测，以这份为准。
            // 用显式发送而不是 IMessageHandler 的返回值回复机制 —— 后者依赖
            // OutboundTarget.REPLY 那条旁路，没必要为省一行代码去赌它。
            NetworkHandler.INSTANCE.sendTo(new PacketAutoStoreSync(AutoStore.isEnabled(player)), player);
            return null;
        }
    }
}
