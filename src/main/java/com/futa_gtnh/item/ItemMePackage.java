package com.futa_gtnh.item;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketMePackageUse;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * ME 封装物品：胶带封好的 AE 设备。NBT 里存着设备的完整数据，
 * 右键把它放回世界（方块设备放点击面外侧；部件设备贴到线缆总线上，
 * 没有总线时会在目标位置新建一个）。
 *
 * <p>
 * 数据结构（{@code futa_me_package} 标签）由 {@code aetape.MeTapeOps} 定义：
 * 方块设备 = 方块注册名 + 完整方块实体 NBT；部件设备 = 部件物品栈 def +
 * 部件 NBT extra（和 AE2 CableBusContainer 的 def:/extra: 惯例一致）。
 * 这个类只做 NBT 的读取展示和右键转发，不引用任何 AE 类型。
 *
 * <p>
 * 没有合成配方、不进创造物品栏：只能由胶带封装产生。
 */
public class ItemMePackage extends Item {

    public static final String NAME = "me_package";

    /** 物品栈 NBT 里挂封装数据的键。 */
    public static final String TAG_DATA = "futa_me_package";

    public ItemMePackage() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
    }

    /** @return 物品栈上的封装数据标签；不是封装物品 / 没有数据时为 null。 */
    public static NBTTagCompound dataTag(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemMePackage)) return null;
        if (stack.stackTagCompound == null) return null;
        return stack.stackTagCompound.hasKey(TAG_DATA) ? stack.stackTagCompound.getCompoundTag(TAG_DATA) : null;
    }

    /** tooltip：里面是哪台设备（不解析 AE 类型，只读字符串 / 原版 ItemStack）。 */
    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        NBTTagCompound dataTag = dataTag(stack);
        if (dataTag == null) {
            tooltip.add(StatCollector.translateToLocal("futa_gtnh.me_package.tip.empty"));
            return;
        }
        tooltip.add(StatCollector.translateToLocalFormatted("futa_gtnh.me_package.tip.device", deviceName(dataTag)));
        tooltip.add(StatCollector.translateToLocal("futa_gtnh.me_package.tip.hint"));
    }

    /** 数据标签 → 设备显示名（tooltip / 消息共用）。不触碰 AE 类。 */
    public static String deviceName(NBTTagCompound dataTag) {
        if (dataTag.getBoolean("isPart")) {
            ItemStack def = ItemStack.loadItemStackFromNBT(dataTag.getCompoundTag("def"));
            if (def != null) return def.getDisplayName();
            return "?";
        }
        String name = dataTag.getString("block");
        int separator = name.indexOf(':');
        if (separator > 0) {
            Block block = GameRegistry.findBlock(name.substring(0, separator), name.substring(separator + 1));
            if (block != null) return block.getLocalizedName();
        }
        return name;
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (player == null || world == null) return false;
        if (dataTag(stack) == null) return false;

        if (world.isRemote) {
            NetworkHandler.INSTANCE.sendToServer(new PacketMePackageUse(x, y, z, side));
            return true;
        }

        // 兜底路径：服务端被直接调用时就地执行
        if (!com.futa_gtnh.aetape.MeTapeOps.isAvailable()) return false;
        return com.futa_gtnh.aetape.MeTapeOps.place(player, world, x, y, z, side, stack);
    }
}
