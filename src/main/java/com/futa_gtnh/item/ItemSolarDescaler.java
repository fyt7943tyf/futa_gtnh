package com.futa_gtnh.item;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.tileentities.boilers.MTEBoilerSolar;

/**
 * 太阳能除钙剂：右键蒸汽太阳能锅炉（青铜/钢两款都算），把它的钙化进度清零。
 *
 * <p>
 * GT5 的太阳能锅炉用一个私有计数器 {@code MTEBoilerSolar.mRunTimeTicks} 累计产出蒸汽的
 * tick 数，超过 {@code calcificationTicks} 后产出线性衰减（也就是「钙化」）。
 * 原版唯一的除钙方式是「挖掉重放」。这里直接把计数器写回 0 并让方块实体重新同步
 * —— 纹理和 Waila 显示的产出都会立刻恢复满值。
 *
 * <p>
 * <b>为什么用 {@code onItemUseFirst} 而不是 {@code onItemUse}：</b>
 * GT 机器的 {@code onBlockActivated} 会打开自己的 GUI 并返回 true，普通右键处理
 * （{@code onItemUse}）根本轮不到执行。{@code onItemUseFirst} 在方块激活<b>之前</b>
 * 被调用，在这里返回 true 才能抢下这次点击。
 *
 * <p>
 * 字段访问用反射（缓存 {@link Field}）：{@code mRunTimeTicks} 是私有字段且没有
 * setter。它是 GT 自己的字段、不属于 MC 混淆名单，开发/生产环境名字一致，
 * 反射按名取一次即可。这是永久工具，不消耗、无耐久。
 */
public class ItemSolarDescaler extends Item {

    public static final String NAME = "solar_descaler";

    /** 缓存的 {@code MTEBoilerSolar.mRunTimeTicks} 字段。解析失败时为 null（只记一次日志）。 */
    private static Field runTimeField;
    private static boolean runTimeFieldBroken;

    public ItemSolarDescaler() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (world == null) return false;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGregTechTileEntity)) return false;

        Object metaTileEntity = ((IGregTechTileEntity) tile).getMetaTileEntity();
        // 钢制太阳能锅炉继承自 MTEBoilerSolar，一个 instanceof 两款都覆盖
        if (!(metaTileEntity instanceof MTEBoilerSolar)) return false;

        MTEBoilerSolar boiler = (MTEBoilerSolar) metaTileEntity;

        if (!world.isRemote) descale(player, world, boiler);

        // 两端都返回 true：客户端也要取消后续的方块激活（否则会先闪一下 GT 的 GUI）
        return true;
    }

    private void descale(EntityPlayer player, World world, MTEBoilerSolar boiler) {
        Field field = runTimeField();
        if (field == null) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.solar_descaler.msg.broken"));
            FutaGtnhMod.LOG.error("太阳能除钙剂：拿不到 MTEBoilerSolar.mRunTimeTicks 字段，功能不可用");
            return;
        }

        try {
            int runTime = field.getInt(boiler);
            if (runTime <= 0) {
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.solar_descaler.msg.clean"));
                return;
            }

            int before = boiler.getProductionPerSecond();
            field.setInt(boiler, 0);
            int after = boiler.getProductionPerSecond();

            // 让客户端重新读方块实体：纹理（钙化贴图）和 Waila 的产出显示都会刷新
            boiler.getBaseMetaTileEntity()
                .issueTileUpdate();
            world.playSoundAtEntity(player, "random.fizz", 0.6F, 1.1F);
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.solar_descaler.msg.descaled", before, after));
        } catch (IllegalAccessException t) {
            FutaGtnhMod.LOG.error("太阳能除钙剂：写入 mRunTimeTicks 失败", t);
        }
    }

    private static Field runTimeField() {
        if (runTimeField != null || runTimeFieldBroken) return runTimeField;
        try {
            runTimeField = MTEBoilerSolar.class.getDeclaredField("mRunTimeTicks");
            runTimeField.setAccessible(true);
        } catch (ReflectiveOperationException t) {
            runTimeFieldBroken = true;
            FutaGtnhMod.LOG.error("太阳能除钙剂：反射解析 MTEBoilerSolar.mRunTimeTicks 失败", t);
        }
        return runTimeField;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.solar_descaler.tip.use"));
    }
}
