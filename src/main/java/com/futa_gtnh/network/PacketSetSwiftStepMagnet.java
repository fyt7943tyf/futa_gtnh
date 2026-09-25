package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.item.ItemSwiftStep;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 客户端到服务端：设置当前手持迅步的掉落物吸附开关与范围。 */
public class PacketSetSwiftStepMagnet implements IMessage {

    private boolean enabled;
    private int radius;

    public PacketSetSwiftStepMagnet() {}

    public PacketSetSwiftStepMagnet(boolean enabled, int radius) {
        this.enabled = enabled;
        this.radius = radius;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        enabled = buf.readBoolean();
        radius = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeInt(radius);
    }

    public static class Handler implements IMessageHandler<PacketSetSwiftStepMagnet, IMessage> {

        @Override
        public IMessage onMessage(PacketSetSwiftStepMagnet message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || !(held.getItem() instanceof ItemSwiftStep)) return null;

            ItemSwiftStep.setItemMagnetEnabled(held, message.enabled);
            ItemSwiftStep.setItemMagnetRadius(held, message.radius);
            return null;
        }
    }
}
