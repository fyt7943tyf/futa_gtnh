package com.futa_gtnh.network;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.common.GuiHandler;
import com.futa_gtnh.item.ItemProductionTerminal;
import com.futa_gtnh.supply.ProductionTarget;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Consume the block click on the client; resolve its physical target on the server thread. */
public final class PacketProductionTerminalUse implements IMessage {

    private static final Map<EntityPlayerMP, Long> LAST = new WeakHashMap<>();
    private int x, y, z, side;
    private float hitX, hitY, hitZ;
    private boolean binding, valid = true;

    public PacketProductionTerminalUse() {}

    public PacketProductionTerminalUse(int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        binding = true;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() != 26) {
            valid = false;
            return;
        }
        binding = buffer.readBoolean();
        x = buffer.readInt();
        y = buffer.readInt();
        z = buffer.readInt();
        side = buffer.readUnsignedByte();
        hitX = buffer.readFloat();
        hitY = buffer.readFloat();
        hitZ = buffer.readFloat();
        valid = Math.abs((long) x) < 29_999_856L && Math.abs((long) z) < 29_999_856L
            && y >= 0
            && y < 4096
            && side < 6
            && hitX >= 0
            && hitX <= 1
            && hitY >= 0
            && hitY <= 1
            && hitZ >= 0
            && hitZ <= 1;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(binding)
            .writeInt(x)
            .writeInt(y)
            .writeInt(z)
            .writeByte(side)
            .writeFloat(hitX)
            .writeFloat(hitY)
            .writeFloat(hitZ);
    }

    public static class Handler implements IMessageHandler<PacketProductionTerminalUse, IMessage> {

        @Override
        public IMessage onMessage(PacketProductionTerminalUse message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player == null || !message.valid) return null;
            ServerActions.enqueue(() -> {
                ItemStack held = player.getCurrentEquippedItem();
                if (held == null || !(held.getItem() instanceof ItemProductionTerminal)
                    || !SupplyClients.supports(player.getUniqueID(), player.playerNetServerHandler)) return;
                long now = System.nanoTime();
                Long last = LAST.get(player);
                if (last != null && now - last < 250_000_000L) return;
                LAST.put(player, now);
                if (!message.binding) {
                    player.openGui(FutaGtnhMod.instance, GuiHandler.GUI_PRODUCTION_TERMINAL, player.worldObj, 0, 0, 0);
                    return;
                }
                if (!player.isSneaking()
                    || player.getDistanceSq(message.x + 0.5D, message.y + 0.5D, message.z + 0.5D) > 64D
                    || !player.worldObj.blockExists(message.x, message.y, message.z)) return;
                ProductionTarget target = ProductionTarget.hit(
                    player.worldObj,
                    message.x,
                    message.y,
                    message.z,
                    message.side,
                    message.hitX,
                    message.hitY,
                    message.hitZ,
                    player);
                if (target == null || !target.source.hasPosition()) {
                    player.addChatMessage(new ChatComponentTranslation("futa_gtnh.productionTerminal.invalid"));
                    return;
                }
                player.openGui(
                    FutaGtnhMod.instance,
                    GuiHandler.GUI_PRODUCTION_BIND_BASE + target.face,
                    player.worldObj,
                    target.source.x,
                    target.source.y,
                    target.source.z);
            });
            return null;
        }
    }
}
