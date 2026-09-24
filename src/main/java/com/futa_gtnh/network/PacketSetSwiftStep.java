package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.item.ItemSwiftStep;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：改手里那个迅步的速度倍率。
 *
 * <p>
 * 一次改一项：不想动的那一项传 {@link #UNCHANGED}。两项都用绝对值传也不会错，
 * 但那样「调飞行」的包会顺带把移动那一项也重写一遍 —— 万一两个客户端窗口
 * 或者连点，容易把另一项覆盖成过期的值。
 *
 * <p>
 * 服务端两条校验：
 *
 * <ul>
 * <li><b>必须是迅步</b>。客户端能指定倍率但<b>指定不了改哪个物品</b> ——
 * 服务端只认「你当前手持的那个」，所以伪造包最多只能改自己手里这件，
 * 动不了背包里别的物品。</li>
 * <li><b>夹取在服务端做一次</b>。客户端那份夹取只是为了让界面好看，
 * 真正说了算的是这里：{@code setXxxMultiplier} 内部会走
 * {@link ItemSwiftStep#clampMultiplier}，浮点数传 NaN、传 1e30 都会被收拾。</li>
 * </ul>
 */
public class PacketSetSwiftStep implements IMessage {

    /** 表示「这一项别动」。取值必须在合法倍率区间之外，用负数最直观。 */
    public static final float UNCHANGED = -1.0F;

    /** 照明那一项的「别动」标记（合法亮度是 0~15）。 */
    public static final int LIGHT_UNCHANGED = -1;

    private float flightMultiplier;
    private float walkMultiplier;
    private int lightLevel;

    public PacketSetSwiftStep() {}

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier) {
        this(flightMultiplier, walkMultiplier, LIGHT_UNCHANGED);
    }

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier, int lightLevel) {
        this.flightMultiplier = flightMultiplier;
        this.walkMultiplier = walkMultiplier;
        this.lightLevel = lightLevel;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        flightMultiplier = buf.readFloat();
        walkMultiplier = buf.readFloat();
        lightLevel = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeFloat(flightMultiplier);
        buf.writeFloat(walkMultiplier);
        buf.writeInt(lightLevel);
    }

    public static class Handler implements IMessageHandler<PacketSetSwiftStep, IMessage> {

        @Override
        public IMessage onMessage(PacketSetSwiftStep message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || !(held.getItem() instanceof ItemSwiftStep)) return null;

            if (message.flightMultiplier != UNCHANGED) {
                ItemSwiftStep.setFlightMultiplier(held, message.flightMultiplier);
            }
            if (message.walkMultiplier != UNCHANGED) {
                ItemSwiftStep.setWalkMultiplier(held, message.walkMultiplier);
            }
            if (message.lightLevel != LIGHT_UNCHANGED) {
                ItemSwiftStep.setLightLevel(held, message.lightLevel);
            }
            return null;
        }
    }
}
