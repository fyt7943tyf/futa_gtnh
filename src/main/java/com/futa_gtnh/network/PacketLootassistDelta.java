package com.futa_gtnh.network;

import com.futa_gtnh.client.ClientLootassistCache;
import com.futa_gtnh.lootassist.LootassistEntry;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：单条地牢记录的增量更新（新增/修改/移除）。
 *
 * <p>
 * 条目很小（十几个字段），单条一条包足够 —— 验证、标记完成都是低频事件，
 * 不值得为它做批量协议。移除（搜索证伪了一个候选）用 {@code removed} 标记表示，
 * 此时后面只跟 key，没有字段数据。
 */
public class PacketLootassistDelta implements IMessage {

    private boolean removed;
    private String key;
    private LootassistEntry entry;

    public PacketLootassistDelta() {}

    public PacketLootassistDelta(LootassistEntry entry) {
        this.removed = false;
        this.key = entry.key();
        this.entry = entry;
    }

    public static PacketLootassistDelta removal(String key) {
        PacketLootassistDelta packet = new PacketLootassistDelta();
        packet.removed = true;
        packet.key = key;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        removed = buf.readBoolean();
        key = cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf);
        if (!removed) {
            entry = LootassistEntry.readFields(buf);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(removed);
        cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, key == null ? "" : key);
        if (!removed && entry != null) {
            entry.writeFields(buf);
        }
    }

    public static class Handler implements IMessageHandler<PacketLootassistDelta, IMessage> {

        @Override
        public IMessage onMessage(PacketLootassistDelta message, MessageContext ctx) {
            if (message.removed) {
                ClientLootassistCache.remove(message.key);
            } else if (message.entry != null) {
                ClientLootassistCache.upsert(message.entry);
            }
            return null;
        }
    }
}
