package com.futa_gtnh.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 寻物魔杖：右键选一个方块，告诉你最近的它在哪，还能传送过去。
 *
 * <p>
 * 这个物品类本身很薄 —— 它只是个「触发入口」。真正的逻辑分在几个地方，
 * 因为它们跑在不同的端上：
 *
 * <ul>
 * <li>{@code client.BlockIndex}：客户端把所有方块编号成一个可搜索的列表
 * （几万条，分帧构建）；</li>
 * <li>{@code client.GuiLocatorWand}：选择界面；</li>
 * <li>{@code locator.LocatorScan} + {@code locator.LocatorManager}：<b>服务端</b>
 * 的增量扫描，客户端只负责发「我要找这个」；</li>
 * <li>{@code locator.TeleportHelper}：服务端的安全落点搜索；</li>
 * <li>{@code client.LocatorBeamRenderer}：客户端的世界渲染，画出那道光束。</li>
 * </ul>
 *
 * <p>
 * 为什么扫描非要放服务端：客户端只有自己视野附近的区块，想找 64 格外的方块
 * 根本看不到。而且「最近的在哪」这件事必须由权威方回答，否则改个客户端
 * 就能让魔杖指向一个不存在的坐标，然后传送到虚空里去。
 */
public class ItemLocatorWand extends Item {

    public static final String NAME = "locator_wand";

    public ItemLocatorWand() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            // 潜行右键 = 清掉当前追踪，不用特地去开界面。
            //
            // 这件事<b>整个交给客户端</b>：要清的本地状态（光束）在客户端，
            // 要让服务端停下来的取消包也只能由客户端发（服务端 sendToServer 是没意义的）。
            // 所以走代理，客户端做两件事，服务端这里什么都不做 ——
            // 服务端那侧的状态会在取消包到达时同步清掉。
            if (world.isRemote) {
                FutaGtnhMod.proxy.clearLocatorTracking();
            }
            return stack;
        }

        if (world.isRemote) {
            // 走代理而不是直接 new 客户端界面类，见 CommonProxy#openLocatorGui 的说明
            FutaGtnhMod.proxy.openLocatorGui();
        }
        return stack;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        tooltip
            .add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.locator_wand.tip.gui"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.locator_wand.tip.clear"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.locator_wand.tip.bauble"));
        tooltip
            .add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.locator_wand.tip.worn"));
    }

    /**
     * 玩家的饰品栏里有没有这根魔杖。
     *
     * <p>
     * 服务端用它决定「追踪的方块被挖掉之后，要不要自动把人送到下一处」
     * （见 {@code LocatorManager}）。那条自动行为<b>只在戴着的时候</b>生效：
     * 戴着是一个明确的「我现在就在用它」的表态，而手里拿着魔杖挖矿是常态 ——
     * 那种情况下自动传送会变成惊吓。
     *
     * <p>
     * Baubles 是软依赖，没装的时候这个方法恒为 false。探测实现放在
     * {@link WornCheck} 这个<b>单独的类</b>里，只有确认 Baubles 在场才会被加载：
     * 直接写在方法体里的话，没装 Baubles 时本类一加载就会因为找不到
     * {@code baubles.api} 而炸（和 {@code client.TinkersScreens} 同一个套路）。
     */
    public static boolean isWornBy(EntityPlayer player) {
        if (player == null || !baublesLoaded()) return false;
        try {
            return WornCheck.check(player);
        } catch (Throwable t) {
            // Baubles 版本对不上之类的意外：当作没戴，绝不能影响扫描主流程
            return false;
        }
    }

    private static boolean baublesLoaded() {
        if (baublesChecked == null) {
            baublesChecked = Boolean.valueOf(
                cpw.mods.fml.common.Loader.isModLoaded("Baubles|Expanded")
                    || cpw.mods.fml.common.Loader.isModLoaded("Baubles"));
        }
        return baublesChecked.booleanValue();
    }

    /** 三态缓存：null = 还没探过。和 {@code CommonProxy} 注册饰品时用的是同一套判断。 */
    private static Boolean baublesChecked;

    /** Baubles 在场时才会被加载的实现。 */
    private static final class WornCheck {

        static boolean check(EntityPlayer player) {
            // 局部变量别叫 baubles：那会遮住同名的包名，baubles.api.* 就解析不到了
            net.minecraft.inventory.IInventory worn = baubles.api.BaublesApi.getBaubles(player);
            if (worn == null) return false;

            for (int slot = 0; slot < worn.getSizeInventory(); slot++) {
                ItemStack stack = worn.getStackInSlot(slot);
                // 用 instanceof 而不是 == 比较物品实例：可佩戴的那份是子类
                // （ItemLocatorWandBauble），而注册进去的到底是哪一个取决于是不是装了 Baubles
                if (stack != null && stack.getItem() instanceof ItemLocatorWand) return true;
            }
            return false;
        }
    }
}
