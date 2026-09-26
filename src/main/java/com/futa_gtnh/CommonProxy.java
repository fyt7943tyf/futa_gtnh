package com.futa_gtnh;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.oredict.ShapedOreRecipe;

import com.futa_gtnh.block.BlockLootMachine;
import com.futa_gtnh.block.BlockSharedTerminal;
import com.futa_gtnh.block.BlockSwiftLight;
import com.futa_gtnh.block.TileEntityLootMachine;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.command.CommandSharedStorage;
import com.futa_gtnh.common.ForgeEventHandler;
import com.futa_gtnh.common.GuiHandler;
import com.futa_gtnh.common.ModEventHandler;
import com.futa_gtnh.item.ItemLocatorWand;
import com.futa_gtnh.item.ItemMinigameHelper;
import com.futa_gtnh.item.ItemSolarDescaler;
import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.shared.SharedStorageManager;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * 服务端 + 客户端共用的逻辑放在这里。
 * 只在客户端做的事（渲染器、按键绑定、GUI 等）写进 {@link ClientProxy}。
 */
public class CommonProxy {

    /** 共享终端方块。在 {@code CommonProxy#preInit} 里创建并注册。 */
    public static BlockSharedTerminal blockSharedTerminal;

    /** 自选抽奖机方块。没装 Enhanced LootBags 时为 null。 */
    public static BlockLootMachine blockLootMachine;

    /** 迅步。没装 Baubles 时为 null。 */
    public static ItemSwiftStep swiftStep;

    /** 寻物魔杖。 */
    public static ItemLocatorWand locatorWand;

    /** 小游戏助手。 */
    public static ItemMinigameHelper minigameHelper;

    /** 太阳能除钙剂。 */
    public static ItemSolarDescaler solarDescaler;

    /**
     * 迅步照明用的隐形光源方块。
     *
     * <p>
     * 它只会被<b>客户端</b>放进世界里（见 {@code client/SwiftStepLight}），
     * 但注册必须在两端都做：方块要有稳定的 id，客户端 {@code setBlock} 才能把它
     * 写进区块的方块数据里（没注册的方块 id 是 0，等于写了空气，光照不会变）。
     */
    public static BlockSwiftLight swiftLight;

    public void preInit(FMLPreInitializationEvent event) {
        // 读取配置文件（config/futa_gtnh.cfg）
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        NetworkHandler.init();
        // 两个事件处理器分别挂在两条不同的总线上，见 ForgeEventHandler 的类注释
        ModEventHandler.register();
        ForgeEventHandler.register();

        registerBlocks();
        registerSwiftStep();
        registerLocatorWand();
        registerMinigameHelper();
        registerLootMachine();
        registerSolarDescaler();
        registerRecipes();

        // lootgames 联动的服务端行为覆写（重试上限等）。
        // 必须在它的配置加载之后：本模组声明了 after:lootgames，preInit 一定排在后面。
        com.futa_gtnh.lootassist.LootgamesCompat.applyServerTweaks();

        NetworkRegistry.INSTANCE.registerGuiHandler(FutaGtnhMod.instance, new GuiHandler());
    }

    public void init(FMLInitializationEvent event) {
        // 注册配方、事件监听
    }

    public void postInit(FMLPostInitializationEvent event) {
        // 处理与其他模组（例如 GregTech / NEI）的联动
    }

    /**
     * 所有模组都加载完之后再补一次的联动（由 {@code FMLLoadCompleteEvent} 转发）。
     *
     * <p>
     * 存在的理由：NEI 是在 {@code LoadComplete} 阶段才加载各模组插件的
     * （{@code NEIModContainer.loadComplete} → {@code ClientHandler.loadPluginsList}），
     * 所以「要盖过别的模组注册的 NEI handler」这种事必须等到这之后再做。
     * 具体见 {@code client/nei/NeiIntegration#installStationOverlay}。
     */
    public void lateInit() {
        // 服务端没有 NEI 联动要做
    }

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSharedStorage());
    }

    // ==================================================================
    // 注册
    // ==================================================================

    private void registerBlocks() {
        blockSharedTerminal = new BlockSharedTerminal();
        GameRegistry.registerBlock(blockSharedTerminal, BlockSharedTerminal.NAME);
        GameRegistry
            .registerTileEntity(TileEntitySharedTerminal.class, FutaGtnhMod.MODID + ":" + BlockSharedTerminal.NAME);
        FutaGtnhMod.blockSharedTerminal = blockSharedTerminal;

        // 迅步的隐形光源：注册但<b>不给 ItemBlock</b>（itemclass 传 null），
        // 这样它不会出现在创造模式物品栏 / NEI 物品列表里 —— 玩家拿不到它，
        // 它只是客户端自己放的一个「发光标记」
        swiftLight = new BlockSwiftLight();
        GameRegistry.registerBlock(swiftLight, null, BlockSwiftLight.NAME);
    }

    /**
     * 注册迅步。
     *
     * <p>
     * <b>用 {@code Loader.isModLoaded} 守卫，而不是在 {@code @Mod} 里写依赖声明。</b>
     * Baubles-Expanded 的 modid 是 {@code "Baubles|Expanded"} —— 真的带一个竖线，
     * 而竖线正是 FML 依赖串里的「或」分隔符，写成
     * {@code required-after:Baubles|Expanded} 会被解析成「Baubles 或 Expanded」，
     * 语义完全不是我们要的。
     *
     * <p>
     * 两个候选 id 都试一下是因为 FML 的已加载列表里
     * {@code Baubles} 和 {@code Baubles|Expanded} 都出现过，不确定哪个是权威 id。
     *
     * <p>
     * 没装 Baubles 时只是不注册这个物品，模组本身照常工作 ——
     * {@link ItemSwiftStep} 实现了 {@code IBauble}，不守卫的话类加载就会炸。
     */
    private void registerSwiftStep() {
        if (!Config.enableSwiftStep) return;

        if (!Loader.isModLoaded("Baubles|Expanded") && !Loader.isModLoaded("Baubles")) {
            FutaGtnhMod.LOG.info("没有检测到 Baubles，跳过迅步的注册");
            return;
        }

        swiftStep = new ItemSwiftStep();
        GameRegistry.registerItem(swiftStep, ItemSwiftStep.NAME);
        FutaGtnhMod.swiftStep = swiftStep;
        FutaGtnhMod.LOG.info("已注册迅步饰品（Baubles 已加载）");
    }

    /**
     * 注册寻物魔杖。
     *
     * <p>
     * 这个物品没有任何前置依赖：扫描由服务端自己做，不需要任何别的模组的 API。
     */
    private void registerLocatorWand() {
        if (!Config.enableLocatorWand) return;

        if (Loader.isModLoaded("Baubles|Expanded") || Loader.isModLoaded("Baubles")) {
            locatorWand = new com.futa_gtnh.item.ItemLocatorWandBauble();
            FutaGtnhMod.LOG.info("检测到 Baubles，寻物魔杖可装备到饰品槽");
        } else {
            locatorWand = new ItemLocatorWand();
        }
        GameRegistry.registerItem(locatorWand, ItemLocatorWand.NAME);
        FutaGtnhMod.locatorWand = locatorWand;
        FutaGtnhMod.LOG.info("已注册寻物魔杖");
    }

    /**
     * 注册小游戏助手。
     *
     * <p>
     * 物品本身不依赖 lootgames（候选点推算、列表都在服务端按需探测），
     * 所以只受配置开关守卫；没装 LootGames 时打开界面会看到空态提示。
     */
    private void registerMinigameHelper() {
        if (!Config.enableMinigameHelper) return;

        minigameHelper = new ItemMinigameHelper();
        GameRegistry.registerItem(minigameHelper, ItemMinigameHelper.NAME);
        FutaGtnhMod.minigameHelper = minigameHelper;
        FutaGtnhMod.LOG.info("已注册小游戏助手");
    }

    /**
     * 注册自选抽奖机。
     *
     * <p>
     * 玩法完全建立在 Enhanced LootBags 的开袋算法上，没装 ELB 时不注册
     * （用 {@code Loader.isModLoaded} 守卫而不是 {@code @Mod} 依赖声明，
     * 和迅步/Baubles 同一个理由：保持软联动）。VendingMachine（技术员代币）
     * 只是「重置次数」按钮的可选付费途径，缺席时那个按钮禁用，其余照常。
     */
    private void registerLootMachine() {
        if (!Config.enableLootMachine) return;

        if (!com.futa_gtnh.lootbag.EnhancedLootBagsCompat.isAvailable()) {
            FutaGtnhMod.LOG.info("没有检测到 Enhanced LootBags，跳过自选抽奖机的注册");
            return;
        }

        blockLootMachine = new BlockLootMachine();
        GameRegistry.registerBlock(blockLootMachine, BlockLootMachine.NAME);
        GameRegistry.registerTileEntity(TileEntityLootMachine.class, FutaGtnhMod.MODID + ":" + BlockLootMachine.NAME);
        FutaGtnhMod.blockLootMachine = blockLootMachine;
        FutaGtnhMod.LOG.info("已注册自选抽奖机（Enhanced LootBags 已加载）");
    }

    /** 注册太阳能除钙剂。只依赖 GT（硬依赖），无额外守卫。 */
    private void registerSolarDescaler() {
        if (!Config.enableSolarDescaler) return;

        solarDescaler = new ItemSolarDescaler();
        GameRegistry.registerItem(solarDescaler, ItemSolarDescaler.NAME);
        FutaGtnhMod.solarDescaler = solarDescaler;
        FutaGtnhMod.LOG.info("已注册太阳能除钙剂");
    }

    /**
     * 共享终端的合成配方。
     *
     * <p>
     * 用矿物词典而不是具体物品，这样在装了其他模组、木桶/玻璃被统一成别的物品时
     * 配方仍然成立：
     *
     * <pre>
     *   玻璃  末影珍珠  玻璃
     *   末影珍珠  箱子  末影珍珠
     *   玻璃  末影珍珠  玻璃
     * </pre>
     *
     * 「末影珍珠」对应跨空间共享，「箱子」对应存储，和功能是自洽的。
     */
    private void registerRecipes() {
        if (!Config.enableRecipe) return;

        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(blockSharedTerminal, 1),
                new Object[] { "GEG", "ECE", "GEG", 'G', "blockGlass", 'E', "enderpearl", 'C', "chestWood" }));

        // 迅步：羽毛 + 金锭 + 钻石。全部走矿物词典，装了别的模组也成立
        if (swiftStep != null) {
            GameRegistry.addRecipe(
                new ShapedOreRecipe(
                    new ItemStack(swiftStep, 1),
                    new Object[] { "FGF", "GDG", "FGF", 'F', "feather", 'G', "ingotGold", 'D', "gemDiamond" }));
        }

        // 寻物魔杖：金锭 + 末影之眼 + 指南针。
        // 「眼睛」对应找，「指南针」对应指向。
        //
        // 这里末影之眼和指南针<b>故意用原版物品而不是矿物词典</b>：
        // ingotGold 有 GT 保证会注册，而 endereye / craftingCompass 这两个词典名
        // 在 1.7.10 里没人保证 —— 写成词典名的话，名字一旦不存在，
        // 配方会安安静静地变成「永远合不出来」，比直接引用原版物品难查得多。
        if (locatorWand != null) {
            GameRegistry.addRecipe(
                new ShapedOreRecipe(
                    new ItemStack(locatorWand, 1),
                    new Object[] { " G ", "ECE", " G ", 'G', "ingotGold", 'E', Items.ender_eye, 'C', Items.compass }));
        }

        // 小游戏助手：纸×8 + 指南针。
        // 「纸」对应清单/地图，「指南针」对应导航 —— 和功能是自洽的。
        // 指南针故意用原版物品而不是矿物词典，理由同寻物魔杖。
        if (minigameHelper != null) {
            GameRegistry.addRecipe(
                new ShapedOreRecipe(
                    new ItemStack(minigameHelper, 1),
                    new Object[] { "PPP", "PCP", "PPP", 'P', Items.paper, 'C', Items.compass }));
        }

        // 自选抽奖机：铁锭×3 + 金锭×2 + 发射器 + 漏斗×2 + 红石比较器。
        // 「发射器」对应随机出货，「漏斗」对应收袋，「比较器」对应挑结果。
        // 发射器/漏斗的方块物品走 Blocks（这套映射的 Items 里没有这两个字段）；
        // 比较器用原版物品（矿物词典名没人保证注册）。
        if (blockLootMachine != null) {
            GameRegistry.addRecipe(
                new ShapedOreRecipe(
                    new ItemStack(blockLootMachine, 1),
                    new Object[] { "III", "GDG", "HCH", 'I', "ingotIron", 'G', "ingotGold", 'D',
                        new ItemStack(Blocks.dispenser, 1), 'H', new ItemStack(Blocks.hopper, 1), 'C',
                        Items.comparator }));
        }

        // 太阳能除钙剂：骨粉×4 + 铁锭。
        // 「骨粉」对应除垢，「铁锭」对应工具本体。骨粉用 damage=15 的染料（原版没有词典名保证）。
        if (solarDescaler != null) {
            GameRegistry.addRecipe(
                new ShapedOreRecipe(
                    new ItemStack(solarDescaler, 1),
                    new Object[] { " B ", "BIB", " B ", 'B', new ItemStack(Items.dye, 1, 15), 'I', "ingotIron" }));
        }
    }

    /**
     * 打开迅步的调整界面。只有 {@link ClientProxy} 覆写了它。
     *
     * <p>
     * 做成代理方法而不是让 {@code ItemSwiftStep} 直接 new 客户端界面类：
     * 那个物品是公共类，引用了 {@code net.minecraft.client.*} 的话，
     * 服务端就得依赖 JVM 的惰性符号解析才不会炸。走代理可以把
     * 「只有客户端才有的实现」老老实实关在 ClientProxy 里。
     */
    public void openSwiftStepGui(ItemStack charm) {
        // 服务端不做任何事
    }

    /**
     * 打开寻物魔杖的选择界面。只有 {@link ClientProxy} 覆写了它。
     *
     * <p>
     * 和 {@link #openSwiftStepGui} 同一个理由：这个界面是纯客户端的
     * （没有 {@code Container}，一切操作都走显式网络包），
     * 所以不需要服务端配合开容器，也就不需要 {@code IGuiHandler} 那一套。
     */
    public void openLocatorGui() {
        // 服务端不做任何事
    }

    /**
     * 打开小游戏助手的清单界面。只有 {@link ClientProxy} 覆写了它。
     *
     * <p>
     * 和 {@link #openLocatorGui} 同一个理由：纯客户端 GuiScreen，没有 Container。
     * 界面打开时会自己向服务端要快照（{@code PacketLootassistAction.SYNC}），
     * 所以这里连坐标都不需要。
     */
    public void openMinigameHelperGui() {
        // 服务端不做任何事
    }

    /**
     * 清掉客户端的追踪状态（光束），并让服务端把扫描任务也停掉。
     *
     * <p>
     * 同样只有 {@link ClientProxy} 覆写了它。做成代理方法还有一个更硬的理由：
     * {@link ItemLocatorWand} 是<b>公共类</b>，服务端也要加载它。如果它直接引用
     * {@code client.LocatorState}，而那个类又引用 {@code net.minecraft.client.Minecraft}，
     * 就等于在专用服务端上埋了一颗 {@code NoClassDefFoundError} 的雷 ——
     * 虽然那一行在 {@code world.isRemote} 里、按现在的 JVM 惰性解析实际不会执行，
     * 但这种「靠不会执行来保证安全」的写法不值得赌。
     */
    public void clearLocatorTracking() {
        // 服务端不做任何事
    }

    /**
     * 服务端拒绝了俯瞰模式的开启请求。只有 {@link ClientProxy} 覆写了它
     * （客户端要退掉乐观进入的界面并提示原因），服务端这个方法什么都不做。
     *
     * @param reasonKey 语言键，null 表示服务端没给原因
     */
    public void onRtsToggleRejected(String reasonKey) {
        // 服务端不做任何事
    }

    /**
     * 给玩家发一条本地化的聊天提示。
     *
     * <p>
     * 用 {@link ChatComponentTranslation} 而不是先翻译成字符串再发：
     * 翻译是<b>在客户端做的</b>，服务端只发语言键。否则服务端会按自己的
     * 语言设置把文字烤死，装了不同语言的客户端就只能看到服务端的语言。
     *
     * @param langKey 语言键，例如 {@code futa_gtnh.locator.msg.no_safe_spot}
     */
    public void notifyPlayer(EntityPlayer player, String langKey) {
        if (player == null) return;
        player.addChatMessage(new ChatComponentTranslation(langKey));
    }

    // ==================================================================
    // 打开界面
    // ==================================================================

    /**
     * 让服务端为玩家打开共享存储界面。
     *
     * <p>
     * 必须由服务端调用：客户端的 {@code displayGuiScreen} 只会画个界面，
     * 服务端那边没有对应的 {@code Container}，任何操作都发不出去。
     *
     * @param terminal 方块终端；为 null 表示「按键远程打开」，此时没有终端输出功能
     */
    public static void openSharedStorage(EntityPlayer player, TileEntitySharedTerminal terminal) {
        if (player == null || player.worldObj == null) return;

        if (terminal != null) {
            player.openGui(
                FutaGtnhMod.instance,
                GuiHandler.GUI_SHARED_TERMINAL,
                player.worldObj,
                terminal.xCoord,
                terminal.yCoord,
                terminal.zCoord);
            return;
        }

        // 远程打开：坐标用玩家自己的位置占位。GuiHandler 会在那里找不到方块实体，
        // 于是 terminal 为 null —— 这正是我们想要的「没有终端」语义。
        World world = player.worldObj;
        player.openGui(
            FutaGtnhMod.instance,
            GuiHandler.GUI_SHARED_TERMINAL,
            world,
            (int) Math.floor(player.posX),
            (int) Math.floor(player.posY),
            (int) Math.floor(player.posZ));
    }

    // ==================================================================
    // 一个方便调试的辅助（被指令和日志用到）
    // ==================================================================

    /** @return 当前共享存储是否已经随存档载入 */
    public static boolean isStorageReady() {
        return SharedStorageManager.isLoaded();
    }
}
