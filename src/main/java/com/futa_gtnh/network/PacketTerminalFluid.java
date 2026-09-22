package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 告诉客户端「当前打开的终端方块正在输出哪种流体」。
 *
 * <p>
 * 这是每个终端方块各自的状态（不是全服共享的），而且只在方块终端上有意义，
 * 所以没有塞进全量快照里 —— 快照是全服共用的同一份压缩字节，
 * 混进玩家各自的状态就没法共用了。
 */
public class PacketTerminalFluid implements IMessage {

    private NBTTagCompound fluidTag;

    public PacketTerminalFluid() {}

    public PacketTerminalFluid(FluidKey key) {
        this.fluidTag = key == null ? null : key.writeToNbt();
    }

    public FluidKey getFluidKey() {
        return fluidTag == null ? null : FluidKey.readFromNbt(fluidTag);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        fluidTag = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(fluidTag != null);
        if (fluidTag != null) {
            ByteBufUtils.writeTag(buf, fluidTag);
        }
    }

    public static class Handler implements IMessageHandler<PacketTerminalFluid, IMessage> {

        @Override
        public IMessage onMessage(PacketTerminalFluid message, MessageContext ctx) {
            try {
                com.futa_gtnh.client.ClientTerminalState.setOutputFluid(message.getFluidKey());
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("共享存储：处理终端流体同步包失败", t);
            }
            return null;
        }
    }
}
