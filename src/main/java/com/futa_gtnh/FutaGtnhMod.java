package com.futa_gtnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.futa_gtnh.block.BlockSharedTerminal;
import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.shared.SharedStorageManager;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * FutaGTNH 模组主类（Minecraft 1.7.10 / GTNH）
 *
 * <p>
 * 本模组目前提供的是一个<b>全服共享的无限背包</b>：
 * 物品和流体共用一个服务端权威的存储池，单条目数量上限是 {@code long}
 * （约 9.22e18，实际等于无限），支持搜索、分页、和玩家个人背包双向交换。
 *
 * <p>
 * 生命周期：
 * preInit : 注册方块/物品/网络通道/GUI handler
 * init : 注册配方、事件监听
 * postInit : 依赖其他模组数据之后的工作
 *
 * <p>
 * 版本号来自 version.txt，由构建时生成 Tags.VERSION，
 * 不要在这里再硬编码一份，否则会出现两处版本号不一致的问题。
 */
@Mod(
    modid = FutaGtnhMod.MODID,
    name = FutaGtnhMod.NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    // 存储层的键、流体显示物品、流体灌装都直接用 GT 的 API（GTUtility），
    // 所以 GT 是硬依赖；NEI 只是可选联动。
    // lwjgl3ify 也是硬依赖：本模组的目标运行时是「GTNH（LWJGL3 + 新 Java）」，
    // 搜索框的中文输入法支持建立它的 GuiTextField mixin 之上（能力探测见
    // client/ImeCompat.java，老版本会自动退回旧路径，不会崩）。
    dependencies = "required-after:gregtech;required-after:lwjgl3ify;after:NotEnoughItems")
public class FutaGtnhMod {

    public static final String MODID = "futa_gtnh";
    public static final String NAME = "FutaGTNH";

    public static final Logger LOG = LogManager.getLogger(MODID);

    /** FML 注入的模组实例，注册 GUI handler 时需要它。 */
    @Mod.Instance(MODID)
    public static FutaGtnhMod instance;

    /** 客户端/服务端分离逻辑的代理（渲染器等只在客户端注册的东西写进 ClientProxy） */
    @SidedProxy(clientSide = "com.futa_gtnh.ClientProxy", serverSide = "com.futa_gtnh.CommonProxy")
    public static CommonProxy proxy;

    /** 共享终端方块。在 {@code CommonProxy#preInit} 里创建并注册。 */
    public static BlockSharedTerminal blockSharedTerminal;

    /** 迅步。没装 Baubles 时为 null。 */
    public static ItemSwiftStep swiftStep;

    /** 寻物魔杖。 */
    public static com.futa_gtnh.item.ItemLocatorWand locatorWand;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOG.info("{} preInit (version {})", NAME, Tags.VERSION);
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOG.info("{} init", NAME);
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOG.info("{} postInit", NAME);
        proxy.postInit(event);
        // 匠魂联动的探测提前做掉：混入是在类加载那一刻生效的，早一点加载
        // 就能让「已启用」那条日志（以及 mixin 应用失败时的报错）出现在启动阶段，
        // 而不是等到第一个 tick。匠魂缺席时它会自己安静返回
        try {
            com.futa_gtnh.tinkers.TinkersAutoFill.prewarm();
        } catch (Throwable t) {
            LOG.warn("匠魂联动预热失败（功能会按缺席处理）", t);
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    /**
     * 服务端与全部维度都就绪之后再读共享存储。
     *
     * <p>
     * 用 {@code ServerStarted} 而不是 {@code ServerStarting}：存档目录要等世界
     * 真正加载完才拿得到，早一步会读到 null，那就变成「内存模式」静默不落盘了。
     */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        SharedStorageManager.onServerStarted(net.minecraft.server.MinecraftServer.getServer());
    }

    /**
     * 所有模组都加载完之后，预热一次矿脉目录。
     *
     * <p>
     * 放在这里而不是 preInit：GT 是把自己的矿脉列表在 postload 阶段填进
     * {@code WorldgenGTOreLayer.sList} 的，早一步读会读到空表。
     * 本模组声明了 {@code required-after:gregtech}，所以这个钩子一定跑在 GT 之后。
     *
     * <p>
     * 就算时机还是不对也不会出事：{@code OreVeinCatalog} 对空表会自己重试
     * （见那里的说明）。这里调一下主要是为了在日志里留下一行可以核对的数字。
     */
    @Mod.EventHandler
    public void loadComplete(cpw.mods.fml.common.event.FMLLoadCompleteEvent event) {
        com.futa_gtnh.locator.OreVeinCatalog.isAvailable();
    }

    /** 服务端停止前落盘。此时各维度还没被卸载，写文件是安全的。 */
    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        SharedStorageManager.onServerStopping();
    }
}
