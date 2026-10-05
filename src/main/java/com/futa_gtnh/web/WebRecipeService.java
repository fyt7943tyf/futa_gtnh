package com.futa_gtnh.web;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 网页配方功能的客户端外壳：开服务、推进索引、刷新库存快照、按键与命令。
 *
 * <p>
 * <b>整个 web 包都是客户端专属的</b>，而且只有在装了 NEI 时才会被加载
 * （守卫在 {@code ClientProxy}，和 {@code client/nei} 那一包同样的做法）——
 * 配方数据本来就来自 NEI，服务端既没有它也不需要它。
 *
 * <p>
 * 索引构建要等 NEI 把物品清单和配方处理器都装载完（它在
 * {@code LoadComplete} 阶段才加载各模组插件）。所以这里不直接在 preInit 里开建，
 * 而是挂一个「待建」标记，由 tick 去轮询 NEI 的就绪状态 ——
 * 早一秒读到的是空表，那样缓存下来的就是一份没有配方的索引。
 */
public final class WebRecipeService {

    private WebRecipeService() {}

    /** 库存快照的刷新间隔（tick）。2 秒足够跟手，也不至于每 tick 都扫一遍背包。 */
    private static final int STOCK_REFRESH_INTERVAL = 40;

    /** 隔多久核对一次「NEI 的处理器是不是又多了」（tick）。10 秒一次，重建很便宜。 */
    private static final int REBUILD_CHECK_INTERVAL = 200;

    /**
     * 网页服务启动失败后的重试间隔（tick）。
     *
     * <p>
     * 最常见的失败原因是<b>端口被另一个客户端占着</b>（同时开了两个游戏实例，
     * 谁先起来谁拿到端口）。之前那种情况下输的那个会一直躺着不动 —— 先起来的那个
     * 一关，页面就再也打不开了，而玩家完全不知道发生了什么。所以这里定期重试。
     */
    private static final int START_RETRY_INTERVAL = 600;

    /** 最多重试多少次（10 分钟）。再久就没意义了，玩家多半已经不需要了。 */
    private static final int MAX_START_RETRIES = 20;

    private static boolean registered;
    private static int ticks;
    private static boolean pendingIndex;
    private static boolean startPending;
    private static int startRetries;

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 由 {@code ClientProxy.preInit} 调用。没装 NEI 时整条线都不启用。 */
    public static void register() {
        if (registered || !available()) return;
        registered = true;

        FMLCommonHandler.instance()
            .bus()
            .register(new Listener());

        // 客户端命令：/futaweb status|start|stop|rebuild
        try {
            net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new Command());
        } catch (Throwable t) {
            // 命令注册失败不影响按键那条路
            FutaGtnhMod.LOG.warn("网页配方：注册 /futaweb 命令失败", t);
        }

        // 物品图标走离屏渲染（画出来才和游戏里一致），它要挂在渲染线程上
        WebIconRenderer.register();

        if (Config.webRecipeEnable && Config.webRecipeAutoStart) {
            start();
        }
    }

    /**
     * 网页配方功能在当前环境里能不能用。
     *
     * <p>
     * 配方数据全部来自 NEI（它的物品清单和配方处理器），没装 NEI 就什么也查不到。
     * 这也正是「NEI 类不能在本包以外被碰到」的那条线的位置：本包只在
     * {@code available()} 为真时才会被调用。
     */
    public static boolean available() {
        return cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems");
    }

    /**
     * 由 {@code ClientProxy.lateInit}（{@code LoadComplete}）调用。
     *
     * <p>
     * 这时候 NEI 刚把各模组的配方处理器注册完，正好可以开始建索引。
     * 真正开跑还要等 tick 里那道人就绪检查。
     */
    public static void lateInit() {
        if (available() && Config.webRecipeEnable && Config.webRecipePrebuild) {
            pendingIndex = true;
        }
    }

    public static boolean isEnabled() {
        return Config.webRecipeEnable && available();
    }

    public static boolean start() {
        if (!available() || !Config.webRecipeEnable) return false;
        boolean ok = WebRecipeServer.start(Config.webRecipeBindAddress, Config.webRecipePort, Config.webRecipeToken);
        if (ok) {
            startPending = false;
            startRetries = 0;
            if (Config.webRecipePrebuild) pendingIndex = true;
        } else if (Config.webRecipeAutoStart) {
            // 起不来就挂个待办，交给 tick 定期重试（见 START_RETRY_INTERVAL 的说明）
            startPending = true;
        }
        return ok;
    }

    public static void stop() {
        WebRecipeServer.stop();
    }

    /** 索引缓存目录：跟着游戏目录走，和配置文件同一层。 */
    private static File cacheDir() {
        try {
            File gameDir = Minecraft.getMinecraft().mcDataDir;
            if (gameDir != null) return new File(new File(gameDir, "config"), "futa_gtnh");
        } catch (Throwable ignored) {
            // 拿不到游戏目录就不落盘，功能照常
        }
        return null;
    }

    // ==================================================================
    // 每 tick
    // ==================================================================

    /**
     * tick 监听器。
     *
     * <p>
     * <b>类和方法的可见性都必须是 public</b>：FML 的事件总线是拿 ASM 现生成一个
     * {@code ASMEventHandler_xxx} 类去反射调用它的，而那个生成类在另一个类加载器里。
     * 非 public 会让它抛 {@code IllegalAccessError}，而且是<b>每个 tick 都抛</b>，
     * 表现就是刚进游戏立刻崩在 {@code FMLCommonHandler.onPreClientTick}。
     * 仓库里别的监听器（{@code KeyHandler.Listener} 等）都是 public，原因就在这里。
     */
    public static final class Listener {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            // 必须最先做：HTTP 线程提交过来的活儿都排在队列里等着这一刻
            WebClientTasks.tick();
            WebStore.tick();

            // 网页问过库存、而本地这份共享背包数据可能已经过期时，补要一份全量快照
            WebStore.tickStockRequest();

            if (++ticks % STOCK_REFRESH_INTERVAL == 0) {
                WebStore.refreshStock();
            }

            if (pendingIndex && WebRecipeIndex.canBuild()
                && !WebRecipeIndex.isReady()
                && !WebRecipeIndex.isBuilding()
                && WebRecipeIndex.error() == null) {
                pendingIndex = false;
                WebRecipeIndex.start(cacheDir());
            }

            // 网页服务上次没起来（端口被别的客户端占着之类）就再试几次，
            // 这样「先关掉一个客户端」之后另一个能自己把页面补上
            if (startPending && ticks % START_RETRY_INTERVAL == 0 && startRetries < MAX_START_RETRIES) {
                startRetries++;
                FutaGtnhMod.LOG.info("网页配方：网页服务还没起来，第 {} 次重试（端口 {}）", startRetries, Config.webRecipePort);
                if (start() && startRetries > 0) {
                    FutaGtnhMod.LOG.info("网页配方：网页服务已补上，手机可以刷新了");
                }
            }

            // ★ NEI 注册各模组处理器不是一次性完成的：实测标题界面刚起来时只有十几个
            // 原版处理器（有序/无序合成、烧制……），GT 那一百多张配方表要更晚才进来。
            // 所以这里定期核对一次「现在有几个处理器」和「上次建索引时用了几个」。
            //
            // 只在「变多」时重建：从缓存里读出来的索引可能是上次进世界时用两百多个处理器
            // 建的，而此刻 NEI 才注册了十几个 —— 那份索引是完整且正确的，重建反而会把
            // 它换成一个残缺的版本（缓存里连处理器名字都存着，界面显示不受影响）。
            if (ticks % REBUILD_CHECK_INTERVAL == 0) {
                int current = WebRecipeIndex.registeredHandlerCount();
                int built = WebRecipeIndex.builtHandlerCount();
                if (current > built && built > 0 && !WebRecipeIndex.isBuilding()) {
                    FutaGtnhMod.LOG.info("网页配方：NEI 处理器从 {} 变成 {}，重建索引以补齐新出现的配方", built, current);
                    WebRecipeIndex.rebuild();
                }
            }
        }
    }

    // ==================================================================
    // 给玩家看的入口
    // ==================================================================

    /** 按键/命令共用的动作：保证服务开着，然后把地址贴到聊天栏。 */
    public static void announce() {
        if (!available()) {
            message(EnumChatFormatting.RED + "网页配方功能需要 NotEnoughItems（配方数据全部来自 NEI）。");
            return;
        }
        if (!Config.webRecipeEnable) {
            message(EnumChatFormatting.RED + "网页配方功能在配置里被关掉了（webRecipeEnable=false）。");
            return;
        }
        if (WebRecipeIndex.isBuilding()) {
            message(
                EnumChatFormatting.GRAY + "配方索引建立中："
                    + WebRecipeIndex.phase()
                    + "（"
                    + Math.round(WebRecipeIndex.progress() * 100)
                    + "%），建好之后手机刷新即可。");
        }

        if (!WebRecipeServer.isRunning() && !start()) {
            message(
                EnumChatFormatting.RED + "网页服务启动失败："
                    + (WebRecipeServer.error() == null ? "未知原因" : WebRecipeServer.error()));
            return;
        }

        message(EnumChatFormatting.GOLD + "合成向导（手机浏览器打开，要和电脑同一个局域网）：");
        List<String> urls = WebRecipeServer.urls();
        for (int i = 0; i < urls.size(); i++) {
            message(EnumChatFormatting.YELLOW + "  " + urls.get(i));
        }
        message(EnumChatFormatting.GRAY + "命令：/futaweb status|start|stop|rebuild");
    }

    private static void message(String text) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            player.addChatMessage(new ChatComponentText(text));
        }
        FutaGtnhMod.LOG.info("[网页配方] {}", EnumChatFormatting.getTextWithoutFormattingCodes(text));
    }

    // ==================================================================
    // /futaweb
    // ==================================================================

    /** 客户端命令。跑在客户端线程上，所以可以直接读游戏状态。 */
    public static final class Command implements ICommand {

        @Override
        public String getCommandName() {
            return "futaweb";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/futaweb [status|start|stop|rebuild]";
        }

        @Override
        @SuppressWarnings("rawtypes")
        public List getCommandAliases() {
            return new ArrayList<String>();
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            String action = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);

            if (action.equals("start")) {
                message(start() ? EnumChatFormatting.GREEN + "网页服务已启动。" : EnumChatFormatting.RED + "启动失败。");
                announce();
                return;
            }
            if (action.equals("stop")) {
                stop();
                message(EnumChatFormatting.YELLOW + "网页服务已停止。");
                return;
            }
            if (action.equals("rebuild")) {
                WebRecipeIndex.rebuild();
                pendingIndex = true;
                message(EnumChatFormatting.YELLOW + "开始重建配方索引，进度可以在网页上看到。");
                return;
            }

            message(
                EnumChatFormatting.GOLD + "合成向导："
                    + (WebRecipeServer.isRunning() ? EnumChatFormatting.GREEN + "运行中" : EnumChatFormatting.RED + "未运行")
                    + EnumChatFormatting.GOLD
                    + " / 配方索引："
                    + (WebRecipeIndex.isReady()
                        ? EnumChatFormatting.GREEN + "就绪"
                            + EnumChatFormatting.GOLD
                            + "（"
                            + WebRecipeIndex.recipeCount()
                            + " 条）"
                        : EnumChatFormatting.YELLOW + WebRecipeIndex.phase())
                    + EnumChatFormatting.GOLD
                    + " / 物品目录："
                    + (WebStore.isReady() ? EnumChatFormatting.GREEN + "" + WebStore.size() + " 个"
                        : EnumChatFormatting.YELLOW + "建立中"));
            announce();
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return true;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public List addTabCompletionOptions(ICommandSender sender, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>();
                options.add("status");
                options.add("start");
                options.add("stop");
                options.add("rebuild");
                return options;
            }
            return null;
        }

        @Override
        public boolean isUsernameIndex(String[] args, int index) {
            return false;
        }

        @Override
        public int compareTo(Object other) {
            return getCommandName().compareTo(((ICommand) other).getCommandName());
        }
    }
}
