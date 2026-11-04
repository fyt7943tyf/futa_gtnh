package com.futa_gtnh.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketMeTapeUse;

/**
 * ME 封装胶带：右键白名单里的 AE 设备（ME 驱动器 / 接口方块 / 接口部件 /
 * ME 箱子 / IO 传输总线），设备变成一个保留全部数据的「封装物品」
 * （{@link ItemMePackage}），再右键放回原样。
 *
 * <p>
 * 玩法原型是现代版 ExtendedAE 的 me_packing_tape（1.20+），这里按 1.7.10 的
 * AE2（GTNH fork rv3）从零实现。胶带 64 点耐久，每封一台设备耗 1 点。
 *
 * <p>
 * <b>这个类不引用任何 AE 类型</b>（软依赖隔离惯例，见 {@code ae2.ItemSharedStorageCell}），
 * 全部 AE 逻辑在 {@code aetape.MeTapeOps} 里，注册由 CommonProxy 在 AE2 在场时守卫。
 * 右键管线和纸箱/太阳能除钙剂同构：{@code onItemUseFirst} 抢在设备 GUI 之前，
 * 客户端发 {@link PacketMeTapeUse}（原生 C08 被吞），服务端重校验后执行。
 */
public class ItemMePackingTape extends Item {

    public static final String NAME = "me_packing_tape";

    public ItemMePackingTape() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
        setMaxDamage(64);
        setNoRepair();
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (player == null || world == null) return false;

        if (world.isRemote) {
            // 粗判：只对「看着像可封装目标」的方块发包并吞掉这次右键；
            // 其它方块放行原版流程（开机器 GUI 等）。精判在服务端重做。
            if (!com.futa_gtnh.aetape.MeTapeOps.isRoughTarget(world.getBlock(x, y, z))) return false;
            NetworkHandler.INSTANCE.sendToServer(new PacketMeTapeUse(x, y, z, side, hitX, hitY, hitZ));
            return true;
        }

        // 兜底路径：服务端被直接调用时就地执行（正常流程客户端只发包）。
        // AE2 缺席时 registerMePackingTape 根本不会注册这个物品，这里是双保险。
        if (!com.futa_gtnh.aetape.MeTapeOps.isAvailable()) return false;
        return com.futa_gtnh.aetape.MeTapeOps.pack(player, world, x, y, z, side, hitX, hitY, hitZ, stack);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        // 空点右键不做事（避免顺手把方块放下/开界面）
        return stack;
    }
}
