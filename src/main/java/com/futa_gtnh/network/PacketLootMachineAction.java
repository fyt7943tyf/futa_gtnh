package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.inventory.ContainerLootMachine;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：抽奖机界面上的按钮动作（打开/刷新、领取、重置次数）。
 *
 * <p>
 * 和共享存储的操作包同一个原则：客户端只能「请求」。Handler 校验玩家当前
 * 打开的就是抽奖机容器（拿不到容器直接丢弃），能做什么、要不要扣东西，
 * 全部由 {@link ContainerLootMachine#handleAction} 之后的方块实体自己判。
 *
 * <p>
 * 「领取」还带一个轮次下标（玩家在历史里选的那一轮）；服务端只当它是个
 * 数字，越界与否由方块实体权威校验。
 */
public class PacketLootMachineAction implements IMessage {

    public static final byte ACTION_ROLL = 1;
    public static final byte ACTION_CLAIM = 2;
    public static final byte ACTION_RESET = 3;

    private byte action;
    private byte roundIndex;

    public PacketLootMachineAction() {}

    public PacketLootMachineAction(byte action) {
        this(action, (byte) 0);
    }

    public PacketLootMachineAction(byte action, byte roundIndex) {
        this.action = action;
        this.roundIndex = roundIndex;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        roundIndex = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeByte(roundIndex);
    }

    public static class Handler implements IMessageHandler<PacketLootMachineAction, IMessage> {

        @Override
        public IMessage onMessage(PacketLootMachineAction message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            if (player.openContainer instanceof ContainerLootMachine) {
                ((ContainerLootMachine) player.openContainer).handleAction(player, message.action, message.roundIndex);
            }
            return null;
        }
    }
}
