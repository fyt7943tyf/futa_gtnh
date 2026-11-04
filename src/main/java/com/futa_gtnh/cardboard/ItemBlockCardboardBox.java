package com.futa_gtnh.cardboard;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketCardboardBoxUse;

/**
 * 纸箱的物品形态。meta 0 = 空箱，meta 1 = 已装箱（NBT 里带数据）。
 *
 * <p>
 * <b>装箱 = 拿空箱右键任意带方块实体的方块</b>（潜行时是普通放置，不装箱）。
 * 为什么在 {@code onItemUseFirst} 里做、客户端发自己的包：GT 机器、AE 设备的
 * {@code onBlockActivated} 会开自己的 GUI，普通 {@code onItemUse} 轮不到执行；
 * 而客户端 {@code onItemUseFirst} 返回 true 后 1.7.10 的原生 C08 点击包不再发送，
 * 服务端必须靠 {@link PacketCardboardBoxUse} 才知道这次右键 ——
 * 和太阳能除钙剂（{@code ItemSolarDescaler}）同一套管线。
 *
 * <p>
 * 客户端只做「看起来能不能装」的粗判（没方块实体就直接放行普通放置流程），
 * 真正的黑名单 / 硬度 / 权限校验全部在服务端 {@link CardboardBoxOps#pack} 重做。
 */
public class ItemBlockCardboardBox extends ItemBlock {

    /** 物品栈 NBT 里挂数据标签的键。 */
    private static final String TAG_DATA = "futa_cardboard_data";

    public ItemBlockCardboardBox(Block block) {
        super(block);
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(16);
    }

    /** 把数据标签挂到纸箱物品栈上（破坏掉落 / 放置转移时用）。 */
    public static void attachData(ItemStack stack, NBTTagCompound dataTag) {
        if (dataTag == null) return;
        if (stack.stackTagCompound == null) stack.stackTagCompound = new NBTTagCompound();
        stack.stackTagCompound.setTag(TAG_DATA, dataTag);
    }

    /** @return 物品栈上的数据标签；空箱为 null。 */
    public static NBTTagCompound dataTag(ItemStack stack) {
        if (stack == null || stack.stackTagCompound == null) return null;
        return stack.stackTagCompound.hasKey(TAG_DATA) ? stack.stackTagCompound.getCompoundTag(TAG_DATA) : null;
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        return super.getUnlocalizedName(stack) + (stack.getItemDamage() == 1 ? ".filled" : ".empty");
    }

    /** 创造物品栏 / NEI 只展示空箱（已装箱的数据各不相同，摆出来没有意义）。 */
    @Override
    public void getSubItems(net.minecraft.item.Item item, net.minecraft.creativetab.CreativeTabs tab,
        List<ItemStack> list) {
        list.add(new ItemStack(item, 1, 0));
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        NBTTagCompound dataTag = dataTag(stack);
        if (dataTag == null) {
            tooltip.add(StatCollector.translateToLocal("futa_gtnh.cardboard_box.tip.empty"));
            return;
        }
        CardboardBoxData data = CardboardBoxData.readInline(dataTag);
        tooltip
            .add(StatCollector.translateToLocalFormatted("futa_gtnh.cardboard_box.tip.block", data.blockDisplayName()));
        if (data.gtMetaTileId >= 0) {
            tooltip.add(StatCollector.translateToLocalFormatted("futa_gtnh.cardboard_box.tip.gt", data.gtMetaTileId));
        }
        tooltip.add(
            StatCollector.translateToLocal(
                CardboardBoxStore.isFileBacked(dataTag) ? "futa_gtnh.cardboard_box.tip.data_file"
                    : "futa_gtnh.cardboard_box.tip.data_inline"));
    }

    // ==================================================================
    // 装箱（右键目标方块）
    // ==================================================================

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (player == null || world == null) return false;
        // 已装箱不参与装箱（放置已装箱走普通放置流程）
        if (stack.getItemDamage() != 0) return false;
        // 潜行右键 = 把空箱放到目标方块旁边（vanilla 放置），不装箱
        if (player.isSneaking()) return false;
        if (world.getBlock(x, y, z) == FutaGtnhMod.blockCardboardBox) return false;

        if (world.isRemote) {
            // 粗判：只有看到方块实体才当成装箱目标；服务端会重新校验。
            // 误判的最坏结果只是服务端回一条「不能装」的提示。
            if (world.getTileEntity(x, y, z) == null) return false;
            NetworkHandler.INSTANCE.sendToServer(new PacketCardboardBoxUse(x, y, z));
            return true;
        }

        // 兜底路径：万一服务端被直接调用（自动化/别的模组），就地装箱。
        // 正常流程客户端返回 true 后 C08 不发，服务端只会收到上面的包。
        return CardboardBoxOps.pack(player, world, x, y, z, stack);
    }

    // ==================================================================
    // 放置（空箱普通放置 / 已装箱把数据搬进方块实体）
    // ==================================================================

    @Override
    public boolean placeBlockAt(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ, int metadata) {
        if (!super.placeBlockAt(stack, player, world, x, y, z, side, hitX, hitY, hitZ, metadata)) return false;

        // 已装箱：数据标签搬进方块实体（metadata 已经由物品 damage 带过去 = 1）
        NBTTagCompound dataTag = dataTag(stack);
        if (dataTag == null) return true;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityCardboardBox)) return true;
        ((TileEntityCardboardBox) tile).setStoredDataTag(dataTag);
        return true;
    }
}
