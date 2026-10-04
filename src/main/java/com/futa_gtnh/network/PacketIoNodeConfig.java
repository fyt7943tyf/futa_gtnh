package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TileEntityIoNode;
import com.futa_gtnh.inventory.ContainerIoNode;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：IO 节点面配置的整份上行（或向服务端要一份权威值）。
 *
 * <p>
 * 共享终端那条路是复用 {@code PacketStorageAction} 的 {@code SET_TERMINAL_IO}，
 * 但那个入口要求开着 {@code ContainerSharedTerminal} —— IO 节点开的是自己的容器，
 * 所以这里单独开一条通道。校验标准对齐 {@code StorageActionHandler}：
 * <ol>
 * <li>必须真的开着 IO 节点的界面（{@code openContainer} 是 {@link ContainerIoNode}）；</li>
 * <li>必须在方块旁边（8 格内），防止隔空改配置；</li>
 * <li>坐标上必须真的是 IO 节点；</li>
 * <li>数字不被信任：整份配置交给 {@code TerminalIoConfig.readFromNbt}，
 * 速率档位 / 模式枚举都夹回合法值。</li>
 * </ol>
 * 改动生效后立刻回推一份 {@link PacketIoNodeSync} 权威值，客户端本地怎么改都不会跑偏。
 */
public class PacketIoNodeConfig implements IMessage {

    /** 想要的交互距离上限（方块中心到玩家），平方后比较。 */
    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private int x;
    private int y;
    private int z;
    private NBTTagCompound config;
    /** true = 只是（重）要一份配置，不带改动。 */
    private boolean request;

    public PacketIoNodeConfig() {}

    /** 改动：整份配置发上去。 */
    public static PacketIoNodeConfig update(int x, int y, int z, NBTTagCompound config) {
        PacketIoNodeConfig packet = new PacketIoNodeConfig();
        packet.x = x;
        packet.y = y;
        packet.z = z;
        packet.config = config;
        return packet;
    }

    /** 请求：界面刚打开 / 同步丢了的时候要一份权威值。 */
    public static PacketIoNodeConfig request(int x, int y, int z) {
        PacketIoNodeConfig packet = new PacketIoNodeConfig();
        packet.x = x;
        packet.y = y;
        packet.z = z;
        packet.request = true;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        request = buf.readBoolean();
        config = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(request);
        buf.writeBoolean(config != null);
        if (config != null) {
            ByteBufUtils.writeTag(buf, config);
        }
    }

    public static class Handler implements IMessageHandler<PacketIoNodeConfig, IMessage> {

        @Override
        public IMessage onMessage(PacketIoNodeConfig message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
            if (player == null) return null;

            try {
                // 必须真的开着 IO 节点界面，否则视为伪造
                if (!(player.openContainer instanceof ContainerIoNode)) return null;

                TileEntity tile = player.worldObj.getTileEntity(message.x, message.y, message.z);
                if (!(tile instanceof TileEntityIoNode)) return null;
                TileEntityIoNode node = (TileEntityIoNode) tile;

                if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                    return null;
                }

                if (!message.request && message.config != null) {
                    // readFromNbt 会把速率吸附到档位、模式夹回合法枚举 —— 客户端的数字不作数
                    node.getIo()
                        .readFromNbt(message.config);
                    node.onIoChanged();
                }
                PacketIoNodeSync.send(player, node);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("IO 节点：处理玩家 {} 的配置上行失败", player.getCommandSenderName(), t);
            }
            return null;
        }
    }
}
