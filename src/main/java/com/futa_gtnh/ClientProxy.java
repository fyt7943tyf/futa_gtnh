package com.futa_gtnh;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.futa_gtnh.client.GuiLocatorWand;
import com.futa_gtnh.client.GuiMinigameHelper;
import com.futa_gtnh.client.GuiSwiftStep;
import com.futa_gtnh.client.KeyHandler;
import com.futa_gtnh.client.LocatorBeamRenderer;
import com.futa_gtnh.client.LocatorState;
import com.futa_gtnh.client.RoguelikeMapClient;
import com.futa_gtnh.client.SwiftStepClientHandler;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLocatorAction;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 客户端专属逻辑。继承 {@link CommonProxy}，只在需要覆写客户端行为时才写方法。
 * 注意：如果覆写了父类方法，记得按需调用 {@code super.xxx(event)}。
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void onSupplySnapshot(net.minecraft.network.INetHandler source,
        com.futa_gtnh.network.PacketSupplySnapshot packet) {
        com.futa_gtnh.client.ClientSupplyState.receive(source, packet);
    }

    @Override
    public void onPublicMeSnapshot(net.minecraft.network.INetHandler source,
        com.futa_gtnh.network.PacketPublicMeSnapshot packet) {
        com.futa_gtnh.client.ClientPublicMeState.receive(source, packet);
    }

    @Override
    public void onProductionSnapshot(net.minecraft.network.INetHandler source,
        com.futa_gtnh.network.PacketProductionSnapshot packet) {
        com.futa_gtnh.client.ClientProductionState.receive(source, packet);
    }

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        com.futa_gtnh.client.ClientSupplyState.register();
        com.futa_gtnh.cosmetic.client.CosmeticArmorClient.register();
        RoguelikeMapClient.register();
        KeyHandler.register();
        SwiftStepClientHandler.register();

        // 客户端功能设置界面的背包按钮（镜像 EZMiner 的做法：Forge 事件往原版
        // 背包/创造模式界面的 buttonList 里注入一枚图标按钮，无 mixin 无第三方依赖）
        com.futa_gtnh.client.ClientFeatureButtonOverlay.register();

        // IO 节点方块的 ISBRH（小中心核 + 连接臂 + 按模式着色的端帽）。
        // renderId 是个静态字段：方块注册在两端都跑，但只有客户端赋得上渲染器；
        // 服务端永远不渲染这个方块，字段保持 0 无所谓。
        com.futa_gtnh.block.BlockIoNode.renderId = RenderingRegistry.getNextAvailableRenderId();
        RenderingRegistry.registerBlockHandler(new com.futa_gtnh.client.IoNodeRenderer());

        // 存储总线薄板：一个格子里按面掩码画最多六块 AE 风格阶梯面板。
        com.futa_gtnh.storagebus.BlockStorageBus.renderId = RenderingRegistry.getNextAvailableRenderId();
        RenderingRegistry.registerBlockHandler(new com.futa_gtnh.client.StorageBusRenderer());

        // 手持存储总线时的白色放置预览（AE PartPlacement 同语义）
        MinecraftForge.EVENT_BUS.register(new com.futa_gtnh.client.StorageBusPlacementPreview());

        // 世界渲染事件在 MinecraftForge.EVENT_BUS 上，不在 FML 的那条总线上。
        // 挂错了不会报错，只会永远收不到事件 —— 光渲染器会安静地什么都不画。
        MinecraftForge.EVENT_BUS.register(new LocatorBeamRenderer());
        MinecraftForge.EVENT_BUS.register(new com.futa_gtnh.client.SupplyHighlightRenderer());
        MinecraftForge.EVENT_BUS.register(new com.futa_gtnh.rts.client.RtsOverlayRenderer());

        // 俯瞰模式的客户端 tick（相机推进/守卫）。TickEvent 在 FML 总线上，
        // 和 KeyHandler 的 Listener 同一条，见 RtsClientTickHandler 的类注释
        com.futa_gtnh.rts.client.RtsClientTickHandler.register();

        // 俯瞰模式下隐藏第一人称手臂（GTNH Forge 反向移植的 RenderHandEvent，
        // Forge 总线事件，注册器内部自己挂对了总线）
        com.futa_gtnh.rts.client.RtsHandHider.register();

        // 手机网页版合成向导：开一个只读的 HTTP 服务，服务端口/口令都在配置里。
        // 没装 NEI 时它自己什么都不做（配方数据全部来自 NEI）。
        com.futa_gtnh.web.WebRecipeService.register();
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        // 共享存储面板：匠魂工作站界面开着时画在它下面（画用 Forge 总线，
        // 点击/键盘走 NEI 的输入钩子，见 StoragePanelEvents 的注释）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new com.futa_gtnh.client.StoragePanelEvents());
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);

        // NEChar 的拼音检索桥在所有模组就位之后探测一次，结果缓存住。
        com.futa_gtnh.client.NecharBridge.init();

        // NEI 联动（配方转移从共享存储取料 + 界面适配）。NEI 是可选联动：
        // NeiIntegration 里全是 NEI 的类型，必须先确认它在场再碰那个类，
        // 否则没装 NEI 的环境会在类加载时直接 NoClassDefFoundError。
        if (cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems")) {
            com.futa_gtnh.client.nei.NeiIntegration.register();
        }
    }

    /**
     * 所有模组都加载完之后再补一次 NEI 联动。
     *
     * <p>
     * NEI 是在 {@code LoadComplete} 阶段才加载各模组插件的，所以「盖过匠魂注册的
     * NEI 配方转移 handler」这件事必须在 postInit 之后再注册一次，
     * 否则会被匠魂那边覆盖掉（详见 {@code NeiIntegration#installStationOverlay}）。
     */
    @Override
    public void lateInit() {
        if (cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems")) {
            if (Config.enableDisassembler) com.futa_gtnh.client.nei.DisassemblerRecipeHandler.register();
            com.futa_gtnh.client.nei.NeiIntegration.installStationOverlay();
        }
        // 这时候（LoadComplete）NEI 刚把各模组的配方处理器注册完，
        // 是开始建网页配方索引的最早时机（更早读到的是一张空表）
        com.futa_gtnh.web.WebRecipeService.lateInit();
        // Optional development-only harness; its classes are never included in the mod jar.
        if (Boolean.getBoolean("futa.uiSmoke")) {
            try {
                Class.forName("com.futa_gtnh.client.UiSmokeCheck")
                    .getMethod("register")
                    .invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot start UI smoke checks", e);
            }
        }
    }

    /**
     * 迅步的调整界面。
     *
     * <p>
     * 覆盖 {@link CommonProxy#openSwiftStepGui}。之所以绕这一道，
     * 是为了让 {@code ItemSwiftStep}（公共类）不用引用 {@code net.minecraft.client.*}。
     */
    @Override
    public void openSwiftStepGui(ItemStack charm) {
        // 客户端功能开关：迅步的客户端部分（界面/照明/速度手感）被停用时，界面也不再打开
        if (!Config.clientEnableSwiftStepClient) return;
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiSwiftStep(charm));
    }

    /**
     * 寻物魔杖的选择界面。
     *
     * <p>
     * 覆盖 {@link CommonProxy#openLocatorGui}。这个界面没有服务端容器，
     * 所以直接 {@code displayGuiScreen} 就行 —— 选中方块、传送、取消
     * 三个动作各自发一个包，服务端再校验。
     */
    @Override
    public void openLocatorGui() {
        // 客户端功能开关：界面不开，扫描/传送也就永远不会被请求（服务端零开销）
        if (!Config.clientEnableLocatorUi) return;
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiLocatorWand());
    }

    /**
     * 小游戏助手的清单界面。
     *
     * <p>
     * 覆盖 {@link CommonProxy#openMinigameHelperGui}。纯客户端 GuiScreen：
     * 打开时会自己向服务端要全量快照，之后的增量由包 handler 推进
     * {@code ClientLootassistCache}，界面每 tick 从缓存重绘。
     */
    @Override
    public void openMinigameHelperGui() {
        // 客户端功能开关：界面不开就不会向服务端要快照（服务端零开销）
        if (!Config.clientEnableMinigameHelper) return;
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiMinigameHelper());
    }

    /**
     * 反应堆布局档案浏览器。
     *
     * <p>
     * 覆盖 {@link CommonProxy#openReactorLayoutGui}。纯客户端 GuiScreen：
     * 打开时会自己向服务端要布局列表；{@code hasTarget} 表示这次打开带着
     * 「右键点中的那台反应堆」作为部署目标（部署 / 建外壳按钮可用）。
     */
    @Override
    public void openReactorLayoutGui(boolean hasTarget, int x, int y, int z) {
        if (!Config.enableReactorTool) return;
        Minecraft.getMinecraft()
            .displayGuiScreen(new com.futa_gtnh.client.GuiReactorLayoutBrowser(hasTarget, x, y, z));
    }

    /**
     * 清掉追踪：本地状态 + 通知服务端。
     *
     * <p>
     * 两件事都必须由客户端发起 —— 光束是客户端画的，而「取消」这个包
     * 服务端自己发给自己没有任何意义。这也正是这个方法要放在代理里的原因。
     */
    @Override
    public void clearLocatorTracking() {
        LocatorState.clear();
        NetworkHandler.INSTANCE.sendToServer(new PacketLocatorAction(PacketLocatorAction.CANCEL));
    }

    /**
     * 服务端拒绝了俯瞰模式的开启请求：把客户端退回正常状态并提示原因。
     *
     * <p>
     * 由 {@code PacketRtsToggleAck} 的 handler 调用。放在代理里是本项目的
     * 老规矩：公共的网络包类不能直接引用 {@code net.minecraft.client.*}。
     *
     * @param reasonKey 语言键，null 表示服务端没给原因
     */
    @Override
    public void onRtsToggleRejected(String reasonKey) {
        com.futa_gtnh.rts.client.RtsClientState.onServerRejected(reasonKey);
    }

    @Override
    public void onCosmeticArmorIdentity(net.minecraft.network.INetHandler source, java.util.UUID playerId) {
        com.futa_gtnh.cosmetic.client.CosmeticArmorClient.receiveIdentity(source, playerId);
    }
}
