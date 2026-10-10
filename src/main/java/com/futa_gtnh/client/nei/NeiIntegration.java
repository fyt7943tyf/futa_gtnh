package com.futa_gtnh.client.nei;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.client.MouseTweaksCompat;
import com.futa_gtnh.client.NeiSearchBridge;

import codechicken.nei.LayoutManager;
import codechicken.nei.SearchField;
import codechicken.nei.api.API;
import codechicken.nei.api.GuiInfo;

/**
 * NEI 联动的注册入口。
 *
 * <p>
 * <b>这个类（连同本包里其它类）只有在 NEI 真的装了时才会被加载</b> ——
 * {@code ClientProxy.postInit} 用 {@code Loader.isModLoaded("NotEnoughItems")}
 * 守卫后才调用 {@link #register()}。NEI 是可选联动，缺席时这里的一切都不存在。
 *
 * <p>
 * 注册四样东西：
 * <ol>
 * <li>终端界面的配方转移 overlay（「材料直接从共享存储取」，见
 * {@link SharedTerminalOverlayHandler}）；</li>
 * <li>2×2 配方的「幽灵材料指引」叠层对齐到终端右侧的合成栏；</li>
 * <li><b>统一界面适配器</b> {@link FutaNeiGuiHandler}：所有实现了
 * {@code NeiAwareGui} 的界面在这里一处生效（NEI 面板可见性、遮罩区），
 * 不再逐个界面写 handler —— 界面默认显示 NEI 面板，收起与否交给
 * {@code Config#terminalNeiPanel}；</li>
 * <li>搜索推送桥的实现：把共享背包搜索框的输入实时推给 NEI 搜索条
 * （{@code Config#terminalSearchMode} 的 NEI_SYNC* 模式），见 {@link NeiSearchBridge}。</li>
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

        // 关键补登记：真正被打开的是 MouseTweaks 那个<b>子类</b>
        // （{@code MouseTweaksCompat.Gui extends GuiSharedTerminal}），而 NEI 判定
        // 「这个界面认不认这个 overlay」是按 {@code gui.getClass()} 查的
        // （{@code RecipeInfo.hasOverlayHandler(gui, "crafting")}）—— 只登记父类等于没登记，
        // 「+」按钮于是显示 Mismatch Crafting Grid。匠魂合成站没这问题，因为它的界面就是它登记的那个类。
        //
        // 这坑我们自己的注释早就警告过（MouseTweaksCompat 里写着「那边要把这个子类也登记一遍」），
        // 只是当初那句话落实在了 GuiInfo.customSlotGuis 上，overlay 和 handler 这两处漏了。
        Class<? extends GuiContainer> actualGui = MouseTweaksCompat.guiClass();
        if (actualGui != GuiSharedTerminal.class) {
            API.registerGuiOverlay(
                actualGui,
                "crafting",
                SharedTerminalOverlayHandler.OVERLAY_OFFSET_X,
                SharedTerminalOverlayHandler.OVERLAY_OFFSET_Y);
            API.registerGuiOverlayHandler(actualGui, handler, "crafting");
            API.registerGuiOverlay(
                actualGui,
                "crafting2x2",
                SharedTerminalOverlayHandler.OVERLAY_OFFSET_X,
                SharedTerminalOverlayHandler.OVERLAY_OFFSET_Y);
            API.registerGuiOverlayHandler(actualGui, handler, "crafting2x2");
            com.futa_gtnh.FutaGtnhMod.LOG.info("共享存储：NEI 合成栏处理器已登记到实际界面类 {}", actualGui.getSimpleName());
        }

        // 这里<b>不再</b>注册 ""/null 之类的兜底标识了。
        // 试过（空标识、null、LoadComplete 之后再注册一遍、IConfigureNEI 插件类），「+」按钮
        // 始终显示 Mismatch Crafting Grid；埋点证明 NEI 根本没把问题问到我们这个处理器上。
        // 反汇编的结果是：有序/无序合成处理器判断「这个界面有没有 overlay」用的是
        // RecipeInfo.hasOverlayHandler(gui, "crafting") —— 正是上面注册的这个键，
        // 也就是说问题不在注册这一侧，再堆注册方式只是把代码搞乱。详见 README。

        // 统一界面适配器：一个 handler 管所有实现了 NeiAwareGui 的界面
        // （可见性 + 面板格遮罩）。默认显示 NEI 面板，收不收由用户配置决定。
        // 它取代了原来只管共享终端的 TerminalGuiHandler（PR #4）。
        API.registerNEIGuiHandler(FutaNeiGuiHandler.INSTANCE);
        TerminalSearchInput.register();

        // 共享背包搜索框 → NEI 搜索条的推送实现。本体代码只认桥（NeiSearchBridge），
        // 不认 codechicken 类；这里装上实现之后，界面的 NEI_SYNC 模式才真正有东西可推。
        NeiSearchBridge.install(new NeiSearchBridge.Impl() {

            @Override
            public boolean searchFieldExists() {
                return LayoutManager.searchField != null;
            }

            @Override
            public void pushSearchText(String text) {
                SearchField field = LayoutManager.searchField;
                // 相等判断防回环/防重复：setText 会触发 NEI 自己的过滤重启，
                // 对同一个词没必要跑两遍
                if (field != null && !field.text()
                    .equals(text)) {
                    field.setText(text);
                }
            }
        });

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
}
