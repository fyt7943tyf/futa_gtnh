package com.futa_gtnh.network;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.futa_gtnh.rts.server.RtsActionGuard;
import com.futa_gtnh.rts.server.RtsInteractionService;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：俯瞰模式下的远程互动（右键方块 / 右键实体 / 左键实体）。
 *
 * <p>
 * 载荷里带客户端射线的<b>原点和方向</b>：服务端不信任它们用于任何权限判断
 * （方向只用来反推虚拟眼位），距离校验一律以服务端自己算的「玩家 ↔ 目标」
 * 为准。校验链：会话门控 + 每 tick 限频（{@link RtsActionGuard}）→ 范围
 * 校验 → 执行（{@link RtsInteractionService}）。
 */
public class PacketRtsInteract implements IMessage {

    public static final byte MODE_USE_BLOCK = 0;
    public static final byte MODE_INTERACT_ENTITY = 1;
    public static final byte MODE_ATTACK_ENTITY = 2;
    /**
     * 互动模式的「只互动、不放置」右键：只跑方块的 onBlockActivated（开 GUI /
     * 互动），未消费且手持<b>非方块物品</b>时才落到物品使用（骨粉/桶/GT 扳手）。
     * 方块物品在此路径下永不放置 —— 1.3.1 上机反馈互动模式还会放方块。
     */
    public static final byte MODE_USE_BLOCK_INTERACT_ONLY = 3;

    private byte mode;
    // 方块目标（MODE_USE_BLOCK）
    private int x, y, z;
    private byte side;
    private float hitX, hitY, hitZ;
    // 实体目标（MODE_INTERACT_ENTITY / MODE_ATTACK_ENTITY）
    private int entityId;
    // 客户端射线（服务端反推虚拟眼位用，不做权限判断）
    private double dirX, dirY, dirZ;
    /** 点击瞬间是否按着 Shift（原版「潜行右键」语义：跳过方块互动直接用物品）。 */
    private boolean sneak;

    public PacketRtsInteract() {}

    public static PacketRtsInteract useBlock(int x, int y, int z, int side, float hitX, float hitY, float hitZ,
        double dirX, double dirY, double dirZ, boolean sneak) {
        return useBlockInternal(MODE_USE_BLOCK, x, y, z, side, hitX, hitY, hitZ, dirX, dirY, dirZ, sneak);
    }

    /** 互动模式的右键：只互动不放置（见 {@link #MODE_USE_BLOCK_INTERACT_ONLY}）。 */
    public static PacketRtsInteract useBlockInteractOnly(int x, int y, int z, int side, float hitX, float hitY,
        float hitZ, double dirX, double dirY, double dirZ, boolean sneak) {
        return useBlockInternal(MODE_USE_BLOCK_INTERACT_ONLY, x, y, z, side, hitX, hitY, hitZ, dirX, dirY, dirZ, sneak);
    }

    private static PacketRtsInteract useBlockInternal(byte mode, int x, int y, int z, int side, float hitX, float hitY,
        float hitZ, double dirX, double dirY, double dirZ, boolean sneak) {
        PacketRtsInteract packet = new PacketRtsInteract();
        packet.mode = mode;
        packet.x = x;
        packet.y = y;
        packet.z = z;
        packet.side = (byte) side;
        packet.hitX = hitX;
        packet.hitY = hitY;
        packet.hitZ = hitZ;
        packet.dirX = dirX;
        packet.dirY = dirY;
        packet.dirZ = dirZ;
        packet.sneak = sneak;
        return packet;
    }

    public static PacketRtsInteract entity(byte mode, int entityId, double dirX, double dirY, double dirZ) {
        PacketRtsInteract packet = new PacketRtsInteract();
        packet.mode = mode;
        packet.entityId = entityId;
        packet.dirX = dirX;
        packet.dirY = dirY;
        packet.dirZ = dirZ;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        mode = buf.readByte();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        side = buf.readByte();
        hitX = buf.readFloat();
        hitY = buf.readFloat();
        hitZ = buf.readFloat();
        entityId = buf.readInt();
        dirX = buf.readDouble();
        dirY = buf.readDouble();
        dirZ = buf.readDouble();
        sneak = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(mode);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(side);
        buf.writeFloat(hitX);
        buf.writeFloat(hitY);
        buf.writeFloat(hitZ);
        buf.writeInt(entityId);
        buf.writeDouble(dirX);
        buf.writeDouble(dirY);
        buf.writeDouble(dirZ);
        buf.writeBoolean(sneak);
    }

    public static class Handler implements IMessageHandler<PacketRtsInteract, IMessage> {

        @Override
        public IMessage onMessage(PacketRtsInteract message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            if (!RtsActionGuard.tryConsume(player)) return null;

            switch (message.mode) {
                case MODE_USE_BLOCK:
                case MODE_USE_BLOCK_INTERACT_ONLY: {
                    // 范围校验以方块中心为准（服务端权威）
                    if (!RtsActionGuard.isWithinRange(player, message.x + 0.5D, message.y + 0.5D, message.z + 0.5D)) {
                        RtsActionGuard.notifyRejected(player, "futa_gtnh.rts.msg.out_of_range");
                        return null;
                    }
                    boolean interactOnly = message.mode == MODE_USE_BLOCK_INTERACT_ONLY;
                    RtsInteractionService.handleUseBlock(
                        player,
                        message.x,
                        message.y,
                        message.z,
                        message.side,
                        message.hitX,
                        message.hitY,
                        message.hitZ,
                        message.dirX,
                        message.dirY,
                        message.dirZ,
                        message.sneak,
                        interactOnly);
                    break;
                }
                case MODE_INTERACT_ENTITY:
                case MODE_ATTACK_ENTITY: {
                    World world = player.worldObj;
                    Entity target = world != null ? world.getEntityByID(message.entityId) : null;
                    if (target == null) return null;
                    if (!RtsActionGuard.isWithinRange(player, target.posX, target.posY, target.posZ)) {
                        RtsActionGuard.notifyRejected(player, "futa_gtnh.rts.msg.out_of_range");
                        return null;
                    }

                    if (message.mode == MODE_INTERACT_ENTITY) {
                        RtsInteractionService
                            .handleInteractEntity(player, target, message.dirX, message.dirY, message.dirZ);
                    } else {
                        RtsInteractionService.handleAttackEntity(player, target);
                    }
                    break;
                }
                default:
                    break;
            }
            return null;
        }
    }
}
