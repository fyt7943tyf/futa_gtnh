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
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketSolarDescalerUse;

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
 * <b>为什么用 {@code onItemUseFirst} 且客户端要发自己的包：</b>
 * GT 机器的 {@code onBlockActivated} 会打开自己的 GUI，普通右键处理
 * （{@code onItemUse}）轮不到执行，所以要抢在前面的 {@code onItemUseFirst}。
 * 但 1.7.10 的 {@code onItemUseFirst} 是个「客户端预测门」：客户端返回 true
 * 就直接消费这次点击、<b>原生的 C08 点击包不再发送</b>（可从
 * {@code PlayerControllerMP.onPlayerRightClick} 的字节码证实：C08 的创建在
 * onItemUseFirst 的分支之后），服务端永远看不到这次右键。
 * 因此客户端检测到目标后发 {@link com.futa_gtnh.network.PacketSolarDescalerUse}
 * 让服务端执行，服务端动作绝不依赖原生点击管线。
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

        // 识别目标：两端各判一遍，判定标准一致
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGregTechTileEntity)) return false;

        Object metaTileEntity = ((IGregTechTileEntity) tile).getMetaTileEntity();
        // 钢制太阳能锅炉继承自 MTEBoilerSolar，一个 instanceof 两款都覆盖
        if (!(metaTileEntity instanceof MTEBoilerSolar)) return false;

        if (world.isRemote) {
            // 客户端：onItemUseFirst 返回 true 会吞掉 C08 点击包（见类注释），
            // 服务端只能靠这个包知道「玩家想除钙」。返回 true 同时拦下 GT 的 GUI。
            NetworkHandler.INSTANCE.sendToServer(new PacketSolarDescalerUse(x, y, z));
        } else {
            // 兜底路径：万一这个方法在服务端被直接调用（别的模组/未来的流程改动），
            // 就地执行。正常流程走上面的包，客户端只会走其中一条，不会叠加。
            descaleServer(player, world, (MTEBoilerSolar) metaTileEntity);
        }

        // 两端都返回 true：客户端也要取消后续的方块激活（否则会先闪一下 GT 的 GUI）
        return true;
    }

    /** 服务端：执行除钙并发提示。由 {@code PacketSolarDescalerUse} 处理器调用。 */
    public void descaleServer(EntityPlayer player, World world, MTEBoilerSolar boiler) {
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
