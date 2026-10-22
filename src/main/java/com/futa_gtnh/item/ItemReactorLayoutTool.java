package com.futa_gtnh.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketReactorToolUse;
import com.futa_gtnh.reactor.Ic2ReactorCompat;

/**
 * IC2 反应堆布局工具：记录 / 部署核反应堆的内部摆放配置。
 *
 * <p>
 * 交互约定（tooltip 里都写了）：
 * <ul>
 * <li><b>Shift + 右键反应堆</b>（核心或反应堆仓）：把它的网格快照存进全服共享
 * 档案。服务端权威执行 —— 客户端只报坐标，读网格以服务端看到的那台为准；</li>
 * <li><b>右键反应堆</b>：打开布局档案浏览器，带着「这台反应堆」作为部署目标
 * （部署 / 建外壳按钮可用）；</li>
 * <li><b>右键空气</b>：打开纯管理界面（浏览 / 改名 / 删除 / 预览）。</li>
 * </ul>
 *
 * <p>
 * <b>为什么用 {@code onItemUseFirst} 且客户端要发自己的包：</b>反应堆核心右键
 * 本来要打开 IC2 的 GUI，普通右键处理轮不到执行，要抢在前面的
 * {@code onItemUseFirst}。而 1.7.10 的 {@code onItemUseFirst} 是个「客户端预测门」：
 * 客户端返回 true 就直接消费这次点击、原生的 C08 点击包不再发送
 * （太阳能除钙剂 {@link ItemSolarDescaler} 的类注释里有字节码级的考证）。
 * 所以：Shift 记录走自有包（{@link PacketReactorToolUse}），打开界面是纯客户端
 * GuiScreen（与寻物魔杖同一套，服务端不需要参与开界面）。
 *
 * <p>
 * 这个物品<b>不引用任何 IC2 类型</b>：目标识别走
 * {@link Ic2ReactorCompat#resolveReactor}，IC2 缺席时物品仍然注册，
 * 只是点上去没有反应（注册处有可用性日志）。
 */
public class ItemReactorLayoutTool extends Item {

    public static final String NAME = "reactor_layout_tool";

    public ItemReactorLayoutTool() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (world == null) return false;
        if (!Ic2ReactorCompat.isAvailable()) return false;

        // 识别目标：两端各判一遍，判定标准一致（tile 在客户端也有同步副本）
        Ic2ReactorCompat.ReactorRef ref = Ic2ReactorCompat.resolveReactor(world, x, y, z);
        if (ref == null) return false;

        boolean sneaking = player != null && player.isSneaking();
        if (world.isRemote) {
            if (sneaking) {
                // Shift 记录：服务端动作。onItemUseFirst 返回 true 会吞掉 C08 点击包
                // （见类注释），所以必须自己带包过去。
                NetworkHandler.INSTANCE.sendToServer(new PacketReactorToolUse(x, y, z));
            } else {
                // 普通右键：打开带部署目标的浏览器（纯客户端界面）
                FutaGtnhMod.proxy.openReactorLayoutGui(true, x, y, z);
            }
        } else {
            // 兜底路径：万一这个方法在服务端被直接调用，就地记录。
            // 正常流程走上面的包，两端只会走其中一条，不会叠加。
            if (sneaking && player instanceof net.minecraft.entity.player.EntityPlayerMP) {
                com.futa_gtnh.reactor.ReactorLayoutManager
                    .recordLayout((net.minecraft.entity.player.EntityPlayerMP) player, ref);
            }
        }

        // 两端都返回 true：客户端也要拦下后续的方块激活（否则会先闪一下 IC2 的 GUI）
        return true;
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world != null && world.isRemote) {
            // 空中右键：纯管理模式（浏览 / 改名 / 删除 / 预览，没有部署目标）
            FutaGtnhMod.proxy.openReactorLayoutGui(false, 0, 0, 0);
        }
        return stack;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        String gray = net.minecraft.util.EnumChatFormatting.DARK_GRAY.toString();
        tooltip.add(gray + StatCollector.translateToLocal("item.futa_gtnh.reactor_layout_tool.tip.shift"));
        tooltip.add(gray + StatCollector.translateToLocal("item.futa_gtnh.reactor_layout_tool.tip.open"));
        tooltip.add(gray + StatCollector.translateToLocal("item.futa_gtnh.reactor_layout_tool.tip.manage"));
    }
}
