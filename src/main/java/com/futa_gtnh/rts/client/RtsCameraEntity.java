package com.futa_gtnh.rts.client;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * 俯瞰模式的相机实体（纯客户端）。
 *
 * <p>
 * 1.7.10 的 {@code Minecraft.renderViewEntity} 字段类型是
 * {@code EntityLivingBase}，所以相机必须是一个 {@code EntityLivingBase}
 * 而不是更轻的 {@code Entity}。这个类因此要保持「尽可能笨」：
 *
 * <ul>
 * <li><b>从不加入世界</b>（不 spawn、不 tick），只设 {@code worldObj} ——
 * 原版渲染器从 {@code renderViewEntity} 上读位置 / 朝向 / 维度，
 * 这些字段手工填就够了，很多 1.7.10 freecam 都是这么做的。</li>
 * <li>{@link #getEyeHeight} 返回 0：相机位置就取实体位置，省一层偏移换算。</li>
 * <li>{@link #isEntityAlive} 恒真：{@code EntityLivingBase} 默认按血量判定，
 * 哪天血量被别的东西改成 0，渲染器会把相机当死实体处理。</li>
 * <li>姿态（含 prev 姿态）由 {@link RtsCameraController} 每 tick 写入，
 * 原版渲染用 prev + partialTicks 插值，平滑是白拿的。</li>
 * </ul>
 */
public class RtsCameraEntity extends EntityLivingBase {

    public RtsCameraEntity(World world) {
        super(world);
        // 尺寸无所谓（不会被渲染、不会被碰撞），设小一点图个心安
        this.setSize(0.1F, 0.1F);
    }

    @Override
    public float getEyeHeight() {
        return 0.0F;
    }

    @Override
    public boolean isEntityAlive() {
        return true;
    }

    /**
     * 原版 {@code EntityLivingBase} 的抽象方法（渲染器拿它画手里/身上的东西）。
     * 相机什么都不拿，返回空数组即可。
     */
    @Override
    public ItemStack[] getLastActiveItems() {
        return new ItemStack[0];
    }

    /** 同上：原版抽象方法，相机没有任何装备槽，空实现。 */
    @Override
    public void setCurrentItemOrArmor(int slotIn, ItemStack stack) {}

    /** 同上：原版抽象方法，相机没有任何装备，恒空。 */
    @Override
    public ItemStack getEquipmentInSlot(int slotIn) {
        return null;
    }

    /** 同上：原版抽象方法，相机不持物，恒空。 */
    @Override
    public ItemStack getHeldItem() {
        return null;
    }

    /** 防御性兜底：就算被谁误加进世界去 tick，也什么都不会发生。 */
    @Override
    public void onUpdate() {}
}
