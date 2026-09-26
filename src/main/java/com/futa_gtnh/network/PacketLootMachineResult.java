package com.futa_gtnh.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TileEntityLootMachine;
import com.futa_gtnh.client.ClientLootMachineState;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：抽奖机的全量状态（历史轮次 + 剩余次数 + 代币余额）。
 *
 * <p>
 * 两个时机下发：开界面时（{@code GuiHandler} 里），以及每个按钮动作之后
 * （作为权威回执）。界面上那片「模拟出货」不是真槽位，全靠这份包画；
 * 掉率数组与堆一一对应，负数表示算不出。
 */
public class PacketLootMachineResult implements IMessage {

    /** 客户端视图里的一轮结果（堆 + 掉率）。 */
    public static final class RoundView {

        public final ItemStack[] stacks;
        public final double[] chances;

        RoundView(ItemStack[] stacks, double[] chances) {
            this.stacks = stacks;
            this.chances = chances;
        }
    }

    private byte rollsMax;
    private List<RoundView> rounds = new ArrayList<>();
    private int tokenBalance;
    private boolean tokenAvailable;

    public PacketLootMachineResult() {}

    public PacketLootMachineResult(TileEntityLootMachine machine, int balance, boolean tokenAvailable) {
        this.rollsMax = (byte) TileEntityLootMachine.ROLLS_PER_BAG;
        for (TileEntityLootMachine.LootRound round : machine.getRounds()) {
            ItemStack[] stacks = round.stacks;
            double[] chances = round.chances;
            this.rounds.add(new RoundView(stacks, chances));
        }
        this.tokenBalance = balance;
        this.tokenAvailable = tokenAvailable;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        rollsMax = buf.readByte();
        int roundCount = buf.readUnsignedByte();
        rounds = new ArrayList<>(roundCount);
        for (int r = 0; r < roundCount; r++) {
            int count = buf.readShort();
            ItemStack[] stacks = new ItemStack[count];
            double[] chances = new double[count];
            for (int i = 0; i < count; i++) {
                stacks[i] = readItemStack(buf);
                chances[i] = buf.readDouble();
            }
            rounds.add(new RoundView(stacks, chances));
        }
        tokenBalance = buf.readInt();
        tokenAvailable = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(rollsMax);
        buf.writeByte(rounds.size());
        for (RoundView round : rounds) {
            buf.writeShort(round.stacks.length);
            for (int i = 0; i < round.stacks.length; i++) {
                writeItemStack(buf, round.stacks[i]);
                buf.writeDouble(round.chances == null || i >= round.chances.length ? -1.0D : round.chances[i]);
            }
        }
        buf.writeInt(tokenBalance);
        buf.writeBoolean(tokenAvailable);
    }

    private static void writeItemStack(ByteBuf buf, ItemStack stack) {
        buf.writeBoolean(stack != null);
        if (stack != null) {
            ByteBufUtils.writeItemStack(buf, stack);
        }
    }

    private static ItemStack readItemStack(ByteBuf buf) {
        if (!buf.readBoolean()) return null;
        return ByteBufUtils.readItemStack(buf);
    }

    public static class Handler implements IMessageHandler<PacketLootMachineResult, IMessage> {

        @Override
        public IMessage onMessage(PacketLootMachineResult message, MessageContext ctx) {
            try {
                ClientLootMachineState
                    .apply(message.rollsMax, message.rounds, message.tokenBalance, message.tokenAvailable);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("抽奖机：处理状态同步包失败", t);
            }
            return null;
        }
    }
}
