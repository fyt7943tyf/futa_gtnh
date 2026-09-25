package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.rts.server.RtsActionGuard;
import com.futa_gtnh.rts.server.RtsHistoryManager;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：撤销 / 重做最近一次俯瞰操作（快捷键 Ctrl+Z / Ctrl+Y）。
 *
 * <p>
 * 历史在服务端（{@link RtsHistoryManager}），客户端只发意图。撤销/重做不
 * 回退材料（掉落物早就散了、材料的去向太复杂），只回滚方块 —— 和原版
 * RTSbuilding 的取舍一致。
 */
public class PacketRtsUndoRedo implements IMessage {

    public static final byte ACTION_UNDO = 0;
    public static final byte ACTION_REDO = 1;

    private byte action;

    public PacketRtsUndoRedo() {}

    public PacketRtsUndoRedo(byte action) {
        this.action = action;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
    }

    public static class Handler implements IMessageHandler<PacketRtsUndoRedo, IMessage> {

        @Override
        public IMessage onMessage(PacketRtsUndoRedo message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            if (!RtsActionGuard.tryConsume(player)) return null;

            if (message.action == ACTION_UNDO) {
                int count = RtsHistoryManager.undo(player);
                player.addChatMessage(
                    new ChatComponentTranslation(
                        count > 0 ? "futa_gtnh.rts.msg.undo" : "futa_gtnh.rts.msg.nothing_to_undo",
                        count));
            } else if (message.action == ACTION_REDO) {
                int count = RtsHistoryManager.redo(player);
                player.addChatMessage(
                    new ChatComponentTranslation(
                        count > 0 ? "futa_gtnh.rts.msg.redo" : "futa_gtnh.rts.msg.nothing_to_redo",
                        count));
            }
            return null;
        }
    }
}
