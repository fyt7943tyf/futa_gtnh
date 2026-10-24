package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.item.ItemReactorLayoutTool;
import com.futa_gtnh.reactor.Ic2ReactorCompat;
import com.futa_gtnh.reactor.ReactorDeployer;
import com.futa_gtnh.reactor.ReactorLayoutManager;
import com.futa_gtnh.reactor.ShellBuilder;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：布局浏览器的全部动作（拉列表 / 预览 / 改名 / 删除 /
 * 保存当前 / 部署 / 建外壳）。
 *
 * <p>
 * 七个动作合成一个包，靠 {@link #action} 区分（与小游戏助手的动作包同一套）。
 * 与所有动作包同一个「不信客户端」原则：<b>玩家必须手持布局工具</b>，否则这个包
 * 没有任何意义，直接丢掉。凡是要碰世界坐标的动作（保存当前 / 部署 / 建外壳）
 * 还要过距离校验 —— 界面是纯客户端的，服务端只认「玩家真的够得着那台反应堆」。
 */
public class PacketReactorLayoutAction implements IMessage {

    public static final byte SYNC = 0;
    public static final byte PREVIEW = 1;
    public static final byte RENAME = 2;
    public static final byte DELETE = 3;
    /** 记录坐标处的反应堆到档案（界面上的「保存当前布局」按钮）。 */
    public static final byte SAVE_CURRENT = 4;
    public static final byte DEPLOY = 5;
    public static final byte BUILD_SHELL = 6;
    /** 界面关闭：取消 viewer 登记（不再收列表重推）。 */
    public static final byte SYNC_LEAVE = 7;

    /** 改名允许的最大长度（字符）。名字会随列表广播，防灌包。 */
    private static final int MAX_NAME_CHARS = 40;

    private byte action;
    private long layoutId;
    private int x;
    private int y;
    private int z;
    private boolean looseNbt;
    private String name;

    public PacketReactorLayoutAction() {}

    public PacketReactorLayoutAction(byte action) {
        this.action = action;
    }

    public static PacketReactorLayoutAction preview(long layoutId, boolean looseNbt) {
        PacketReactorLayoutAction packet = new PacketReactorLayoutAction(PREVIEW);
        packet.layoutId = layoutId;
        packet.looseNbt = looseNbt;
        return packet;
    }

    public static PacketReactorLayoutAction rename(long layoutId, String name) {
        PacketReactorLayoutAction packet = new PacketReactorLayoutAction(RENAME);
        packet.layoutId = layoutId;
        packet.name = name;
        return packet;
    }

    public static PacketReactorLayoutAction delete(long layoutId) {
        PacketReactorLayoutAction packet = new PacketReactorLayoutAction(DELETE);
        packet.layoutId = layoutId;
        return packet;
    }

    public static PacketReactorLayoutAction withTarget(byte action, int x, int y, int z, long layoutId,
        boolean looseNbt) {
        PacketReactorLayoutAction packet = new PacketReactorLayoutAction(action);
        packet.x = x;
        packet.y = y;
        packet.z = z;
        packet.layoutId = layoutId;
        packet.looseNbt = looseNbt;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        layoutId = buf.readLong();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        looseNbt = buf.readBoolean();
        name = buf.readBoolean()
            ? Utf8Text.limit(cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf), MAX_NAME_CHARS * 4)
            : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeLong(layoutId);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(looseNbt);
        buf.writeBoolean(name != null);
        if (name != null) {
            cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, Utf8Text.limitLength(name, MAX_NAME_CHARS));
        }
    }

    public static class Handler implements IMessageHandler<PacketReactorLayoutAction, IMessage> {

        @Override
        public IMessage onMessage(PacketReactorLayoutAction message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null || !Config.enableReactorTool) return null;

            // 手持校验：不是拿着布局工具就别想动档案
            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || !(held.getItem() instanceof ItemReactorLayoutTool)) return null;

            switch (message.action) {
                case SYNC:
                    ReactorLayoutManager.addViewer(player.getUniqueID());
                    ReactorLayoutManager.sendListTo(player);
                    break;
                case SYNC_LEAVE:
                    ReactorLayoutManager.removeViewer(player.getUniqueID());
                    break;
                case PREVIEW: {
                    com.futa_gtnh.reactor.ReactorLayout layout = ReactorLayoutManager.copyOf(message.layoutId);
                    if (layout != null) {
                        ReactorLayoutManager.sendPreviewTo(player, layout, message.looseNbt);
                    }
                    break;
                }
                case RENAME:
                    if (message.name != null && ReactorLayoutManager.rename(message.layoutId, message.name)) {
                        player.addChatMessage(
                            new ChatComponentTranslation("futa_gtnh.reactor.msg.renamed", message.name));
                    }
                    break;
                case DELETE:
                    if (ReactorLayoutManager.delete(message.layoutId)) {
                        player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.deleted"));
                    }
                    break;
                case SAVE_CURRENT: {
                    Ic2ReactorCompat.ReactorRef ref = resolveNearby(player, message);
                    if (ref != null) {
                        ReactorLayoutManager.recordLayout(player, ref);
                    }
                    break;
                }
                case DEPLOY: {
                    Ic2ReactorCompat.ReactorRef ref = resolveNearby(player, message);
                    if (ref == null) break;
                    com.futa_gtnh.reactor.ReactorLayout layout = ReactorLayoutManager.copyOf(message.layoutId);
                    if (layout == null) break;
                    ReactorDeployer.deploy(player, layout, ref, message.looseNbt);
                    // 部署动了共享背包：把这份布局的清单重发一遍，界面上的可用量立即可见
                    ReactorLayoutManager.sendPreviewTo(player, layout, message.looseNbt);
                    break;
                }
                case BUILD_SHELL: {
                    Ic2ReactorCompat.ReactorRef ref = resolveNearby(player, message);
                    if (ref != null) {
                        ShellBuilder.build(player, ref);
                    }
                    break;
                }
                default:
                    break;
            }
            return null;
        }

        /** 解析动作包里的目标反应堆：存在 + 距离 ≤ 8 格。失败时给一句能行动的提示。 */
        private Ic2ReactorCompat.ReactorRef resolveNearby(EntityPlayerMP player, PacketReactorLayoutAction message) {
            World world = player.worldObj;
            if (world.getTileEntity(message.x, message.y, message.z) == null) {
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.no_reactor"));
                return null;
            }
            if (player.getDistanceSq(message.x + 0.5D, message.y + 0.5D, message.z + 0.5D) > 64.0D) {
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.too_far"));
                return null;
            }
            Ic2ReactorCompat.ReactorRef ref = Ic2ReactorCompat.resolveReactor(world, message.x, message.y, message.z);
            if (ref == null) {
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.no_reactor"));
            }
            return ref;
        }
    }
}
