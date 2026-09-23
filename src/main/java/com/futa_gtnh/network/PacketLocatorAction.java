package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemLocatorWand;
import com.futa_gtnh.locator.LocatorManager;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：寻物魔杖的操作。
 *
 * <p>
 * 四个动作合成一个包，靠 {@link #action} 区分。几条校验，都是「不信客户端」那条原则：
 *
 * <ul>
 * <li>玩家必须<b>手持寻物魔杖</b> —— 否则发这个包没有任何意义，
 * 直接丢掉，免得有人拿它当免费的区块扫描器刷。</li>
 * <li>{@link #START} 的目标方块由客户端传物品栈，但<b>服务端自己解析</b>成
 * Block + 元数据，不信任客户端给的方块 id。</li>
 * <li>{@link #START_VEIN} 只传矿脉的<b>名字</b>，服务端自己去 GT 的目录里查。
 * 客户端连「这条脉是什么材料」都说不上话，更别说伪造一条不存在的脉。</li>
 * <li>{@link #TELEPORT} 用的是服务端<b>自己记下的</b>上次结果，
 * 客户端连坐标都传不了 —— 所以伪造包最多只能传送到自己刚扫出来的地方。</li>
 * </ul>
 */
public class PacketLocatorAction implements IMessage {

    public static final byte START = 0;
    public static final byte TELEPORT = 1;
    public static final byte CANCEL = 2;
    public static final byte START_VEIN = 3;

    private byte action;
    private ItemStack target;
    private String veinKey;

    public PacketLocatorAction() {}

    public PacketLocatorAction(byte action) {
        this.action = action;
    }

    /** 搜一个具体的方块。 */
    public static PacketLocatorAction start(ItemStack target) {
        PacketLocatorAction packet = new PacketLocatorAction(START);
        packet.target = target;
        return packet;
    }

    /** 搜一条矿脉。 */
    public static PacketLocatorAction startVein(String veinKey) {
        PacketLocatorAction packet = new PacketLocatorAction(START_VEIN);
        packet.veinKey = veinKey;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        target = buf.readBoolean() ? ByteBufUtils.readItemStack(buf) : null;
        veinKey = buf.readBoolean() ? ByteBufUtils.readUTF8String(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeBoolean(target != null);
        if (target != null) {
            ByteBufUtils.writeItemStack(buf, target);
        }
        buf.writeBoolean(veinKey != null);
        if (veinKey != null) {
            ByteBufUtils.writeUTF8String(buf, veinKey);
        }
    }

    public static class Handler implements IMessageHandler<PacketLocatorAction, IMessage> {

        @Override
        public IMessage onMessage(PacketLocatorAction message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            // 手持校验：不是拿着魔杖就别想用
            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || !(held.getItem() instanceof ItemLocatorWand)) return null;

            switch (message.action) {
                case START:
                    if (message.target == null) return null;
                    LocatorManager.start(player, message.target);
                    break;
                case START_VEIN:
                    if (message.veinKey == null) return null;
                    LocatorManager.startVein(player, message.veinKey);
                    break;
                case TELEPORT:
                    handleTeleport(player);
                    break;
                case CANCEL:
                    LocatorManager.cancel(player);
                    break;
                default:
                    break;
            }
            return null;
        }

        private void handleTeleport(EntityPlayerMP player) {
            LocatorManager.TeleportResult result = LocatorManager.teleport(player);
            switch (result) {
                case OK:
                    // 成功后清掉结果：同一次扫描只允许传送一次，
                    // 否则「找到 → 传送 → 再传送」会变成廉价的地图跳跃
                    LocatorManager.cancel(player);
                    break;
                case OK_CARVED:
                    // 目标整个埋在实心方块里，落脚点是就地清出来的 —— 说一声，
                    // 免得玩家以为传送把自己塞进了墙里
                    LocatorManager.cancel(player);
                    FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.locator.msg.arrived_carved");
                    break;
                case NO_SAFE_SPOT:
                    FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.locator.msg.no_safe_spot");
                    break;
                case NO_SAFE_SPOT_PROTECTED:
                    // 附近本来能开洞，但那两格里是矿石这类不该动的方块 ——
                    // 说清楚是「不肯挖」而不是「找不到」，否则玩家会以为魔杖坏了
                    FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.locator.msg.no_safe_spot_protected");
                    break;
                case NO_RESULT:
                default:
                    FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.locator.msg.no_result");
                    break;
            }
        }
    }
}
