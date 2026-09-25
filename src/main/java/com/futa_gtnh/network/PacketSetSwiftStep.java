package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.item.ItemSwiftStep;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：改手里那个迅步的配置。
 *
 * <p>
 * 一次只改指定的项目：不想动的倍率传 {@link #UNCHANGED}，光照和生长光环配置则传各自的
 * unchanged 标记。这样连点调整一个项目时，不会把其它项目覆盖成过期的值。
 *
 * <p>
 * 服务端两条校验：
 *
 * <ul>
 * <li><b>必须是迅步</b>。客户端能指定设置但<b>指定不了改哪个物品</b> ——
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

    /** 生长光环设置不变的标记。 */
    public static final int GROWTH_AURA_UNCHANGED = -1;
    /** 恢复设置不变的标记。 */
    public static final int RECOVERY_UNCHANGED = -1;

    private float flightMultiplier;
    private float walkMultiplier;
    private int lightLevel;
    private int growthAuraEnabled;
    private int growthAuraRadius;
    private int growthAuraSpeed;
    private int animalAuraEnabled;
    private int healthRecoveryEnabled;
    private int foodRecoveryEnabled;
    private int recoverySpeed;

    public PacketSetSwiftStep() {}

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier) {
        this(flightMultiplier, walkMultiplier, LIGHT_UNCHANGED);
    }

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier, int lightLevel) {
        this(flightMultiplier, walkMultiplier, lightLevel, GROWTH_AURA_UNCHANGED, GROWTH_AURA_UNCHANGED);
    }

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier, int lightLevel, int growthAuraEnabled,
        int growthAuraRadius) {
        this(flightMultiplier, walkMultiplier, lightLevel, growthAuraEnabled, growthAuraRadius, GROWTH_AURA_UNCHANGED);
    }

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier, int lightLevel, int growthAuraEnabled,
        int growthAuraRadius, int growthAuraSpeed) {
        this(
            flightMultiplier,
            walkMultiplier,
            lightLevel,
            growthAuraEnabled,
            growthAuraRadius,
            growthAuraSpeed,
            GROWTH_AURA_UNCHANGED);
    }

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier, int lightLevel, int growthAuraEnabled,
        int growthAuraRadius, int growthAuraSpeed, int animalAuraEnabled) {
        this(
            flightMultiplier,
            walkMultiplier,
            lightLevel,
            growthAuraEnabled,
            growthAuraRadius,
            growthAuraSpeed,
            animalAuraEnabled,
            RECOVERY_UNCHANGED,
            RECOVERY_UNCHANGED,
            RECOVERY_UNCHANGED);
    }

    public PacketSetSwiftStep(float flightMultiplier, float walkMultiplier, int lightLevel, int growthAuraEnabled,
        int growthAuraRadius, int growthAuraSpeed, int animalAuraEnabled, int healthRecoveryEnabled,
        int foodRecoveryEnabled, int recoverySpeed) {
        this.flightMultiplier = flightMultiplier;
        this.walkMultiplier = walkMultiplier;
        this.lightLevel = lightLevel;
        this.growthAuraEnabled = growthAuraEnabled;
        this.growthAuraRadius = growthAuraRadius;
        this.growthAuraSpeed = growthAuraSpeed;
        this.animalAuraEnabled = animalAuraEnabled;
        this.healthRecoveryEnabled = healthRecoveryEnabled;
        this.foodRecoveryEnabled = foodRecoveryEnabled;
        this.recoverySpeed = recoverySpeed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        flightMultiplier = buf.readFloat();
        walkMultiplier = buf.readFloat();
        lightLevel = buf.readInt();
        growthAuraEnabled = buf.readInt();
        growthAuraRadius = buf.readInt();
        growthAuraSpeed = buf.readInt();
        animalAuraEnabled = buf.readInt();
        healthRecoveryEnabled = buf.readInt();
        foodRecoveryEnabled = buf.readInt();
        recoverySpeed = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeFloat(flightMultiplier);
        buf.writeFloat(walkMultiplier);
        buf.writeInt(lightLevel);
        buf.writeInt(growthAuraEnabled);
        buf.writeInt(growthAuraRadius);
        buf.writeInt(growthAuraSpeed);
        buf.writeInt(animalAuraEnabled);
        buf.writeInt(healthRecoveryEnabled);
        buf.writeInt(foodRecoveryEnabled);
        buf.writeInt(recoverySpeed);
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
            if (message.growthAuraEnabled != GROWTH_AURA_UNCHANGED) {
                ItemSwiftStep.setGrowthAuraEnabled(held, message.growthAuraEnabled == 1);
            }
            if (message.growthAuraRadius != GROWTH_AURA_UNCHANGED) {
                ItemSwiftStep.setGrowthAuraRadius(held, message.growthAuraRadius);
            }
            if (message.growthAuraSpeed != GROWTH_AURA_UNCHANGED) {
                ItemSwiftStep.setGrowthAuraSpeed(held, message.growthAuraSpeed);
            }
            if (message.animalAuraEnabled != GROWTH_AURA_UNCHANGED) {
                ItemSwiftStep.setAnimalAuraEnabled(held, message.animalAuraEnabled == 1);
            }
            if (message.healthRecoveryEnabled != RECOVERY_UNCHANGED) {
                ItemSwiftStep.setHealthRecoveryEnabled(held, message.healthRecoveryEnabled == 1);
            }
            if (message.foodRecoveryEnabled != RECOVERY_UNCHANGED) {
                ItemSwiftStep.setFoodRecoveryEnabled(held, message.foodRecoveryEnabled == 1);
            }
            if (message.recoverySpeed != RECOVERY_UNCHANGED) {
                ItemSwiftStep.setRecoverySpeed(held, message.recoverySpeed);
            }
            return null;
        }
    }
}
