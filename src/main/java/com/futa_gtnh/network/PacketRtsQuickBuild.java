package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.rts.server.RtsActionGuard;
import com.futa_gtnh.rts.server.RtsBatchEngine;
import com.futa_gtnh.rts.shape.ShapeFill;
import com.futa_gtnh.rts.shape.ShapeGenerator;
import com.futa_gtnh.rts.shape.ShapeType;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：提交一个批量任务（形状建造 / 区域破坏）。
 *
 * <p>
 * <b>只传参数不传坐标</b>：形状、填充、两个角点。坐标由服务端用与客户端
 * 幽灵预览<b>同一份</b> {@link ShapeGenerator} 重新生成 —— 预览见到的就是
 * 实际建造的，客户端也无法伪造目标列表。
 *
 * <p>
 * 校验链：会话+限频 → 两个角点都在操作范围内（形状被角点包围盒夹住，
 * 角点合法则整形合法）→ 各轴跨度 ≤ 形状上限 → 生成后体积 ≤ 体积上限 →
 * 建造时手持必须是 ItemBlock。
 */
public class PacketRtsQuickBuild implements IMessage {

    private byte action;
    private byte shape;
    private byte fill;
    private int ax, ay, az;
    private int bx, by, bz;

    public PacketRtsQuickBuild() {}

    public static PacketRtsQuickBuild of(byte action, ShapeType shape, ShapeFill fill, int ax, int ay, int az, int bx,
        int by, int bz) {
        PacketRtsQuickBuild packet = new PacketRtsQuickBuild();
        packet.action = action;
        packet.shape = (byte) shape.ordinal();
        packet.fill = (byte) fill.ordinal();
        packet.ax = ax;
        packet.ay = ay;
        packet.az = az;
        packet.bx = bx;
        packet.by = by;
        packet.bz = bz;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        shape = buf.readByte();
        fill = buf.readByte();
        ax = buf.readInt();
        ay = buf.readInt();
        az = buf.readInt();
        bx = buf.readInt();
        by = buf.readInt();
        bz = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeByte(shape);
        buf.writeByte(fill);
        buf.writeInt(ax);
        buf.writeInt(ay);
        buf.writeInt(az);
        buf.writeInt(bx);
        buf.writeInt(by);
        buf.writeInt(bz);
    }

    public static class Handler implements IMessageHandler<PacketRtsQuickBuild, IMessage> {

        @Override
        public IMessage onMessage(PacketRtsQuickBuild message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            if (!RtsActionGuard.tryConsume(player)) return null;

            ShapeType shape;
            ShapeFill fill;
            try {
                shape = ShapeType.values()[message.shape];
                fill = ShapeFill.values()[message.fill];
            } catch (ArrayIndexOutOfBoundsException e) {
                return null;
            }

            // 角点都在操作范围内（形状被角点包围盒夹住）
            if (!RtsActionGuard.isWithinRange(player, message.ax + 0.5D, message.ay + 0.5D, message.az + 0.5D)
                || !RtsActionGuard.isWithinRange(player, message.bx + 0.5D, message.by + 0.5D, message.bz + 0.5D)) {
                return null;
            }

            // 各轴跨度上限（角点重合也得有 1 格）
            if (ShapeGenerator.extentX(message.ax, message.bx) > Config.rtsMaxShapeDimension
                || ShapeGenerator.extentY(message.ay, message.by) > Config.rtsMaxShapeDimension
                || ShapeGenerator.extentZ(message.az, message.bz) > Config.rtsMaxShapeDimension) {
                player.addChatMessage(
                    new net.minecraft.util.ChatComponentTranslation(
                        "futa_gtnh.rts.msg.too_large_axis",
                        Config.rtsMaxShapeDimension));
                return null;
            }

            java.util.List<int[]> positions = ShapeGenerator
                .generate(shape, fill, message.ax, message.ay, message.az, message.bx, message.by, message.bz);
            if (positions.isEmpty()) return null;
            if (positions.size() > Config.rtsMaxSelectionVolume) {
                player.addChatMessage(
                    new net.minecraft.util.ChatComponentTranslation(
                        "futa_gtnh.rts.msg.too_large_volume",
                        Config.rtsMaxSelectionVolume));
                return null;
            }

            if (message.action == RtsBatchEngine.ACTION_BUILD) {
                ItemStack held = player.getCurrentEquippedItem();
                if (held == null || !(held.getItem() instanceof ItemBlock)) {
                    player.addChatMessage(
                        new net.minecraft.util.ChatComponentTranslation("futa_gtnh.rts.msg.need_itemblock"));
                    return null;
                }
                RtsBatchEngine.submitBuild(player, positions, held);
            } else {
                RtsBatchEngine.submitDestroy(player, positions);
            }
            return null;
        }
    }
}
