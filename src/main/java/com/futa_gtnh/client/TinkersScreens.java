package com.futa_gtnh.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;

import com.futa_gtnh.station.SharedStorageInventory;
import com.futa_gtnh.station.StationRef;
import com.futa_gtnh.station.StationViews;
import com.futa_gtnh.tinkers.TinkersAutoFill;

/**
 * 「这个界面是不是匠魂的工作站」——客户端专用。
 *
 * <p>
 * 判定要引用 tconstruct 的 GUI 类，所以只能放在客户端侧的类里（服务端没有
 * {@code net.minecraft.client.*}），并且先问 {@link TinkersAutoFill#isAvailable()}
 * 再碰那些类：没装匠魂时这些类根本不存在，方法体里的引用是惰性解析的，
 * 探测没过就不会走到。
 */
public final class TinkersScreens {

    private TinkersScreens() {}

    public static boolean isWorkstation(GuiScreen screen) {
        if (screen == null || !TinkersAutoFill.isAvailable()) return false;
        try {
            return screen instanceof tconstruct.tools.gui.CraftingStationGui
                || screen instanceof tconstruct.tools.gui.PartCrafterGui
                || screen instanceof tconstruct.tools.gui.ToolStationGui
                || screen instanceof tconstruct.tools.gui.ToolForgeGui
                || screen instanceof tconstruct.smeltery.gui.SmelteryGui;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 「这个界面是<b>挂着共享存储</b>的匠魂合成站吗」。
     *
     * <p>
     * 是的话返回坐标 + 那份客户端镜像，否则返回 null。共享存储区要显示什么内容
     * 由客户端算（排序/搜索的代码都在这边），算完得知道该把这一页推给哪个方块，
     * 坐标就是从容器里读出来的。
     *
     * <p>
     * 只有合成站有「旁边的容器」这套机制，工匠工作站 / 部件加工台 / 冶炼炉都没有，
     * 所以这里只认 {@code CraftingStationContainer}。
     */
    public static StationRef sharedChestStation(GuiScreen screen) {
        if (screen == null || !TinkersAutoFill.isAvailable()) return null;
        try {
            if (!(screen instanceof GuiContainer)) return null;
            Container container = ((GuiContainer) screen).inventorySlots;
            if (!(container instanceof tconstruct.tools.inventory.CraftingStationContainer)) return null;

            tconstruct.tools.logic.CraftingStationLogic logic = ((tconstruct.tools.inventory.CraftingStationContainer) container).logic;
            if (logic == null || logic.getWorldObj() == null) return null;

            // 没挂上（旁边有真箱子、或者挂载失败）就不是我们的事
            SharedStorageInventory inventory = StationViews.existing(logic);
            if (inventory == null || !inventory.isRemote()) return null;

            return new StationRef(
                logic.getWorldObj().provider.dimensionId,
                logic.xCoord,
                logic.yCoord,
                logic.zCoord,
                inventory);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 鼠标是不是在「共享存储区」那一块上。
     *
     * <p>
     * 滚轮翻页只在存储区上接管：NEI 的物品面板、界面的其它地方照旧走各自的滚轮逻辑。
     * 匠魂的 {@code isMouseInChest} 就是干这个的（公开方法），而且它内部用的是
     * 布局算出来的真实区域，跟着界面缩放走，我们自己算反而容易错。
     */
    public static boolean isMouseOverSharedChest(GuiScreen screen, int mouseX, int mouseY) {
        if (screen == null || !TinkersAutoFill.isAvailable()) return false;
        try {
            if (!(screen instanceof tconstruct.tools.gui.CraftingStationGui)) return false;
            tconstruct.tools.gui.CraftingStationGui gui = (tconstruct.tools.gui.CraftingStationGui) screen;
            return gui.hasChest() && gui.isMouseInChest(mouseX, mouseY);
        } catch (Throwable t) {
            return false;
        }
    }
}
