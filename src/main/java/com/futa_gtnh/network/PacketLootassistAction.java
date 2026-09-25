package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.item.ItemMinigameHelper;
import com.futa_gtnh.lootassist.LootassistManager;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：小游戏助手的操作（打开同步 / 搜索附近 / 标记完成）。
 *
 * <p>
 * 三个动作合成一个包，靠 {@link #action} 区分。与寻物魔杖的包同一套
 * 「不信客户端」原则：<b>玩家必须手持小游戏助手</b>，否则这个包没有任何意义，
 * 直接丢掉 —— 免得有人拿「搜索」当免费的区块生成器刷。
 */
public class PacketLootassistAction implements IMessage {

    public static final byte SYNC = 0;
    public static final byte SEARCH = 1;
    public static final byte MARK = 2;

    private byte action;
    private String key;
    private boolean flag;

    public PacketLootassistAction() {}

    public PacketLootassistAction(byte action) {
        this.action = action;
    }

    public static PacketLootassistAction mark(String key, boolean completed) {
        PacketLootassistAction packet = new PacketLootassistAction(MARK);
        packet.key = key;
        packet.flag = completed;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        key = buf.readBoolean() ? cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf) : null;
        flag = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeBoolean(key != null);
        if (key != null) {
            cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, key);
        }
        buf.writeBoolean(flag);
    }

    public static class Handler implements IMessageHandler<PacketLootassistAction, IMessage> {

        @Override
        public IMessage onMessage(PacketLootassistAction message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            // 手持校验：不是拿着助手就别想用
            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || !(held.getItem() instanceof ItemMinigameHelper)) return null;

            switch (message.action) {
                case SYNC:
                    // 打开界面：登记为 viewer（之后的增量会推给他）+ 发全量快照
                    LootassistManager.addViewer(player.getUniqueID());
                    LootassistManager.sendSnapshotTo(player);
                    break;
                case SEARCH:
                    LootassistManager.addViewer(player.getUniqueID());
                    LootassistManager.sendSnapshotTo(player);
                    LootassistManager.startSearch(player);
                    break;
                case MARK:
                    if (message.key != null) {
                        LootassistManager.markCompleted(message.key, player.getCommandSenderName(), message.flag);
                    }
                    break;
                default:
                    break;
            }
            return null;
        }
    }
}
