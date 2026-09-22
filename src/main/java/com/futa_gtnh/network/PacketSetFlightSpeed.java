package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.item.ItemFlightCharm;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：把手里那个飞行护符的倍率改成某个值。
 *
 * <p>
 * 客户端在 GUI 里已经先改了自己那份（为了让界面立刻响应），这个包是把权威副本
 * 交给服务端去写。两条校验：
 *
 * <ul>
 * <li><b>必须是护符</b>。客户端能指定倍率但<b>指定不了改哪个物品</b> ——
 * 服务端只认「你当前手持的那个」，所以伪造包最多只能改自己手里这件，
 * 动不了背包里别的物品。</li>
 * <li><b>夹取在服务端做一次</b>。客户端那份夹取只是为了让界面好看，
 * 真正说了算的是这里：{@code setMultiplier} 内部会走
 * {@link ItemFlightCharm#clampMultiplier}，浮点数传 NaN、传 1e30 都会被收拾。</li>
 * </ul>
 */
public class PacketSetFlightSpeed implements IMessage {

    private float multiplier;

    public PacketSetFlightSpeed() {}

    public PacketSetFlightSpeed(float multiplier) {
        this.multiplier = multiplier;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        multiplier = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeFloat(multiplier);
    }

    public static class Handler implements IMessageHandler<PacketSetFlightSpeed, IMessage> {

        @Override
        public IMessage onMessage(PacketSetFlightSpeed message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || !(held.getItem() instanceof ItemFlightCharm)) return null;

            ItemFlightCharm.setMultiplier(held, message.multiplier);
            return null;
        }
    }
}
