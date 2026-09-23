package com.futa_gtnh.client.nei;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.futa_gtnh.Config;
import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.client.MouseTweaksCompat;

import codechicken.nei.VisiblityData;
import codechicken.nei.api.API;
import codechicken.nei.api.GuiInfo;
import codechicken.nei.api.INEIGuiAdapter;

/**
 * NEI 联动的注册入口。
 *
 * <p>
 * <b>这个类（连同本包里其它类）只有在 NEI 真的装了时才会被加载</b> ——
 * {@code ClientProxy.postInit} 用 {@code Loader.isModLoaded("NotEnoughItems")}
 * 守卫后才调用 {@link #register()}。NEI 是可选联动，缺席时这里的一切都不存在。
 *
 * <p>
 * 注册三样东西：
 * <ol>
 * <li>终端界面的配方转移 overlay（「材料直接从共享存储取」，见
 * {@link SharedTerminalOverlayHandler}）；</li>
 * <li>2×2 配方的「幽灵材料指引」叠层对齐到终端右侧的合成栏；</li>
 * <li>终端界面打开时收起 NEI 的物品面板 —— 终端界面比原版容器宽
 * （232px，右侧还有合成栏），NEI 的面板会和它叠在一起。</li>
 * </ol>
 */
public final class NeiIntegration {

    private NeiIntegration() {}

    public static void register() {
        SharedTerminalOverlayHandler handler = new SharedTerminalOverlayHandler();

        // "crafting"：3×3 配方（工作台那一类）的「填入合成栏」按钮 + 幽灵材料指引。
        // 终端合成栏就是 3×3，所以这一类配方现在也能直接填。
        API.registerGuiOverlay(
            GuiSharedTerminal.class,
            "crafting",
            SharedTerminalOverlayHandler.OVERLAY_OFFSET_X,
            SharedTerminalOverlayHandler.OVERLAY_OFFSET_Y);
        API.registerGuiOverlayHandler(GuiSharedTerminal.class, handler, "crafting");

        // "crafting2x2"：2×2 配方（原版背包能做的那类）。它们会被摆在合成栏左上角
        // 2×2 —— 原版 ShapedRecipes.matches 本来就会在整个 3×3 里平移匹配，位置合法。
        // 注册 overlay（带坐标）之后 NEI 还会给终端界面画「幽灵材料指引」，
        // 坐标换算见 SharedTerminalOverlayHandler。
        API.registerGuiOverlay(
            GuiSharedTerminal.class,
            "crafting2x2",
            SharedTerminalOverlayHandler.OVERLAY_OFFSET_X,
            SharedTerminalOverlayHandler.OVERLAY_OFFSET_Y);
        API.registerGuiOverlayHandler(GuiSharedTerminal.class, handler, "crafting2x2");

        API.registerNEIGuiHandler(new TerminalGuiHandler());

        // 共享存储面板的点击 / 滚轮 / 键盘：1.7.10 只有 NEI 这条能「消费事件」的路，
        // 所以面板在没装 NEI 时不启用
        codechicken.nei.guihook.GuiContainerManager.addInputHandler(new StoragePanelInput());

        // ★ 关掉 NEI 的「滚轮转移物品」（配置项 inventory.disableMouseScrollTransfer 那一套）。
        //
        // NEIController.mouseScrolled 的第一道判断就是 GuiInfo.hasCustomSlots(gui)：
        // 滚轮滚过某一格时，NEI 会用 FastTransferManager 把物品在「容器 ↔ 玩家背包」之间
        // 搬一次（transferItem / retrieveItem）。对原版容器没问题，但共享存储的格子是
        // 虚拟格，在 ContainerSharedTerminal.slotClick 里的语义是「点一下 = 取 1 个」，
        // 于是玩家在终端里滚一下滚轮就会掉出来一个物品 —— 而滚轮本该只用来翻页。
        //
        // 登记进 customSlotGuis 之后 NEI 会直接跳过这个界面，别的功能不受影响
        // （hasCustomSlots 在整个 NEI 里只被 mouseScrolled 用到）。
        //
        // 注意它用的是 gui.getClass() 精确匹配（HashSet<Class>），子类不会自动继承 ——
        // 装了 MouseTweaks 时用的是兼容子类，所以要单独再登记一次。
        GuiInfo.customSlotGuis.add(GuiSharedTerminal.class);
        Class<? extends GuiContainer> mouseTweaksGui = MouseTweaksCompat.guiClass();
        if (mouseTweaksGui != null) {
            GuiInfo.customSlotGuis.add(mouseTweaksGui);
        }

        // ★ 匠魂合成站同理，而且这里还有第二个理由：合成站旁边那块存储区是虚拟格子
        // （见 mixins/MixinCraftingStationLogic），NEI 的滚轮搬运会在「容器 ↔ 背包」之间
        // 搬一个物品，落到虚拟格子上语义就乱了。登记之后滚轮空出来给「翻存储区那一页」用
        // （见 client/StoragePanel）。
        if (com.futa_gtnh.tinkers.TinkersAutoFill.isAvailable()) {
            GuiInfo.customSlotGuis.add(tconstruct.tools.gui.CraftingStationGui.class);
        }

        // 合成站的配方转移 handler：这里先注册一次，但真正算数的是晚一点的那次
        // （NEI 是 LoadComplete 阶段才加载各模组插件的，见 installStationOverlay）
        installStationOverlay();
    }

    /** 已经成功接管过一次（只用来少打一遍日志）。 */
    private static boolean stationOverlayInstalled;
    private static boolean stationOverlayFailed;

    /**
     * 让合成站的 NEI 配方转移由我们接管：材料从整个共享存储取，不受「当前第几页」限制。
     *
     * <p>
     * <b>为什么要单独抽一个方法、而且要很晚才调</b>：NEI 是在
     * {@code FMLLoadCompleteEvent}（{@code NEIModContainer.loadComplete} →
     * {@code ClientHandler.loadPluginsList}）才加载各模组的 {@code IConfigureNEI} 插件的。
     * 也就是说匠魂注册它那个「只认当前这一页」的 handler 发生在<b>我们的 postInit 之后</b>，
     * 会把我们先注册的覆盖掉（NEI 的 handler 表就是个 {@code HashMap.put}）。
     * 所以这里在 {@code loadComplete} 之后再注册一次；万一那个时机还不够晚，
     * 第一次打开「挂着共享存储的合成站」时还会再补一次
     * （见 {@code client/StoragePanel} 的 {@code hookNeiOnce}）—— 那时候 NEI 的插件
     * 早就加载完了，一定盖得住。
     *
     * <p>
     * 幂等：重复调用只是把同一份注册再 put 一遍。
     */
    public static void installStationOverlay() {
        if (!com.futa_gtnh.tinkers.TinkersAutoFill.isAvailable()) return;

        try {
            Class<? extends GuiContainer> station = tconstruct.tools.gui.CraftingStationGui.class;
            StationOverlayHandler handler = new StationOverlayHandler();

            // 只换 handler，不动匠魂注册的 CraftingStationStackPositioner（幽灵材料指引的坐标）
            API.registerGuiOverlayHandler(station, handler, "crafting");
            // 2×2 配方匠魂从没注册过，补一个同样偏移的 overlay（合成站的 3×3 和原版工作台同一位置）
            API.registerGuiOverlay(
                station,
                "crafting2x2",
                StationOverlayHandler.OFFSET_X,
                StationOverlayHandler.OFFSET_Y);
            API.registerGuiOverlayHandler(station, handler, "crafting2x2");

            if (!stationOverlayInstalled) {
                stationOverlayInstalled = true;
                com.futa_gtnh.FutaGtnhMod.LOG.info("合成站的 NEI 配方转移已接管：材料从整个共享存储取（含 2×2 配方）");
            }
        } catch (Throwable t) {
            // 匠魂版本对不上：NEI 那边保持原样（材料只能在当前这一页里找），别的功能不受影响
            if (!stationOverlayFailed) {
                stationOverlayFailed = true;
                com.futa_gtnh.FutaGtnhMod.LOG.warn("共享存储：注册合成站的 NEI 配方转移失败，材料仍然只能在当前这一页里找", t);
            }
        }
    }

    /**
     * 终端界面打开时收起 NEI 物品面板（可配，见 {@code hideNeiPanelInTerminalGui}）。
     *
     * <p>
     * 只动 {@code showItemPanel}：NEI 底部的搜索条和实用按钮留着 —— 玩家经常
     * 要一边开着终端一边查配方，全部藏掉反而更难用。
     */
    private static final class TerminalGuiHandler extends INEIGuiAdapter {

        @Override
        public VisiblityData modifyVisiblity(GuiContainer gui, VisiblityData currentVisibility) {
            if (Config.hideNeiPanelInTerminalGui && gui instanceof GuiSharedTerminal) {
                currentVisibility.showItemPanel = false;
            }
            return currentVisibility;
        }
    }
}
