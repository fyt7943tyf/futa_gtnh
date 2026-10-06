package com.futa_gtnh.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import com.futa_gtnh.FutaGtnhMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 给手机看的那套 HTTP 服务。
 *
 * <p>
 * 用的是 JDK 自带的 {@code com.sun.net.httpserver}：本模组不想为了一个局域网小页面
 * 引入 Jetty/NanoHTTPD 之类的依赖（1.7.10 的依赖树本来就够乱了），
 * 而这个内置服务器在 GTNH 的新 Java 运行时里是现成的。
 *
 * <p>
 * <b>它只读不写</b>：所有接口都是 GET，唯一的副作用是「把库存快照读出来」和
 * 「让客户端线程去读一次贴图字节」。没有登录、没有改档、没有发包，
 * 就算同网段有人扫到了端口，最多也就是看到你的配方表。
 * 想要更严实一点可以在配置里设一个访问口令（{@code webRecipeToken}）。
 *
 * <p>
 * 线程模型：请求跑在这个类自己的线程池里，需要游戏状态的少数几件事
 * （库存快照、贴图字节）通过 {@link WebClientTasks} 转交客户端主线程，
 * 配方数据本身是 {@link WebRecipeIndex} 里那张只读的表，随便并发读。
 */
public final class WebRecipeServer {

    private WebRecipeServer() {}

    /** HTTP 工作线程数。手机上一个页面同时也就十来个请求。 */
    private static final int THREADS = 6;

    /** 单个物品最多回多少条配方（回收类物品能有上万条，全回手机打不开）。 */
    private static final int MAX_RECIPES_PER_ITEM = 80;

    /**
     * 前端可以主动要更多（「加载更多配方」按钮）。仍然有上限：几万条一次性塞进浏览器，
     * 手机只会卡死 —— 分几次点，每次翻三倍，够用了。
     */
    private static final int MAX_RECIPES_CEILING = 2000;

    /** 一次最多同时规划几件东西（「合成 A 64 个 + B 3 个」）。 */
    private static final int MAX_TARGETS = 12;

    /** 静态资源在 jar 里的位置。 */
    private static final String RESOURCE_ROOT = "/assets/futa_gtnh/web/";

    private static final Set<String> STATIC_FILES = new HashSet<>(
        java.util.Arrays.asList("index.html", "app.css", "app.js", "favicon.ico"));

    private static final Map<String, byte[]> STATIC_CACHE = new HashMap<>();

    /**
     * 本次启动的随机标记。图标 URL 会带上它，这样换一次游戏（物品 id 会重新编号）
     * 浏览器就不会拿着上一局的图标继续用。
     */
    private static final int BOOT_ID = new Random().nextInt(0x7FFFFFFF);

    private static volatile HttpServer server;
    private static volatile ExecutorService executor;
    private static volatile boolean running;
    private static volatile int boundPort;
    private static volatile String lastError;
    private static volatile String token = "";

    public static boolean isRunning() {
        return running;
    }

    public static int boundPort() {
        return boundPort;
    }

    public static String error() {
        return lastError;
    }

    public static synchronized boolean start(String bindAddress, int port, String accessToken) {
        if (running) return true;

        token = accessToken == null ? "" : accessToken.trim();
        try {
            InetSocketAddress address = bindAddress == null || bindAddress.isEmpty() ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);
            HttpServer created = HttpServer.create(address, 16);
            created.createContext("/", new Router());
            ExecutorService pool = Executors.newFixedThreadPool(THREADS, runnable -> {
                Thread thread = new Thread(runnable, "futa-web-http");
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(pool);
            created.start();

            server = created;
            executor = pool;
            boundPort = created.getAddress()
                .getPort();
            running = true;
            lastError = null;
            FutaGtnhMod.LOG.info("网页配方：服务已启动，监听 {}:{}", address.getAddress(), boundPort);
            for (String url : urls()) {
                FutaGtnhMod.LOG.info("网页配方：手机浏览器打开 {}", url);
            }
            return true;
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage() == null ? t : t.getMessage());
            FutaGtnhMod.LOG.error("网页配方：启动 HTTP 服务失败（端口 {} 被占用？）", port, t);
            return false;
        }
    }

    public static synchronized void stop() {
        if (!running) return;
        try {
            server.stop(0);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：关闭 HTTP 服务时出错（忽略）", t);
        }
        try {
            if (executor != null) executor.shutdownNow();
        } catch (Throwable ignored) {
            // 关不掉线程池不影响别的
        }
        server = null;
        executor = null;
        running = false;
        FutaGtnhMod.LOG.info("网页配方：服务已停止");
    }

    /** 供聊天栏/日志显示的访问地址（带口令）。 */
    public static List<String> urls() {
        if (!running) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        String suffix = token.isEmpty() ? "" : "/?k=" + token;
        for (String host : lanAddresses()) {
            out.add("http://" + host + ":" + boundPort + suffix);
        }
        if (out.isEmpty()) out.add("http://127.0.0.1:" + boundPort + suffix);
        return out;
    }

    private static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) continue;
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    out.add(address.getHostAddress());
                }
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：枚举本机网卡失败（只显示 127.0.0.1）", t);
        }
        return out;
    }

    // ==================================================================
    // 路由
    // ==================================================================

    private static final class Router implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                route(exchange);
            } catch (Throwable t) {
                // 浏览器主动断开是<b>常态</b>：翻页、刷新、图标还没下完就不要了，
                // 都会在服务端这边抛「连接被中止 / Connection reset / Broken pipe」。
                // 这些打完整堆栈只会把日志刷满，让人以为出了故障 —— 降成 debug。
                if (isClientGone(t)) {
                    FutaGtnhMod.LOG.debug("网页配方：客户端提前断开 {}", exchange.getRequestURI());
                } else {
                    FutaGtnhMod.LOG.warn("网页配方：处理请求 {} 失败", exchange.getRequestURI(), t);
                    try {
                        sendJson(exchange, 500, errorJson("服务器内部错误：" + t));
                    } catch (Throwable ignored) {
                        // 连接已经断了就没什么可做的
                    }
                }
            } finally {
                exchange.close();
            }
        }

        private void route(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI()
                .getPath();
            Map<String, String> query = parseQuery(
                exchange.getRequestURI()
                    .getRawQuery());

            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorJson("只支持 GET"));
                return;
            }

            if (!authorized(exchange, query)) {
                sendHtml(exchange, 401, unauthorizedPage());
                return;
            }

            if (path.equals("/") || path.equals("/index.html")) {
                serveStatic(exchange, "index.html");
                return;
            }
            if (path.startsWith("/api/")) {
                api(exchange, path.substring(5), query);
                return;
            }
            if (path.startsWith("/") && STATIC_FILES.contains(path.substring(1))) {
                serveStatic(exchange, path.substring(1));
                return;
            }
            sendJson(exchange, 404, errorJson("没有这个地址"));
        }

        private void api(HttpExchange exchange, String endpoint, Map<String, String> query) throws IOException {
            if (endpoint.equals("status")) {
                sendJson(exchange, 200, statusJson());
                return;
            }
            if (endpoint.equals("search")) {
                sendJson(exchange, 200, searchJson(query));
                return;
            }
            if (endpoint.equals("item")) {
                sendJson(exchange, 200, itemJson(query));
                return;
            }
            if (endpoint.equals("plan")) {
                sendJson(exchange, 200, planJson(query));
                return;
            }
            if (endpoint.equals("stock")) {
                sendJson(exchange, 200, stockJson(query));
                return;
            }
            if (endpoint.startsWith("icon/")) {
                sendIcon(exchange, endpoint.substring(5));
                return;
            }
            sendJson(exchange, 404, errorJson("没有这个接口"));
        }
    }

    // ==================================================================
    // 接口实现
    // ==================================================================

    private static String statusJson() {
        WebJson json = WebJson.object();
        json.k("ok")
            .v(true)
            .k("stockReady")
            // 客户端这份共享存储快照还没到时分不清「真的没有」和「还没同步」——
            // 界面要据此提示，而不是拿一个自己都不知道的答案去说「还缺」
            .v(WebStore.stockReady())
            .k("game")
            .v("GTNH")
            .k("version")
            .v(com.futa_gtnh.Tags.VERSION);

        boolean catalogReady = WebStore.isReady();
        boolean indexReady = WebRecipeIndex.isReady();
        boolean building = !catalogReady || WebRecipeIndex.isBuilding();
        String phase = !catalogReady ? "正在建立物品目录" : WebRecipeIndex.phase();
        float progress = !catalogReady ? WebStore.progress() : WebRecipeIndex.progress();

        json.k("building")
            .v(building)
            .k("progress")
            .v((double) progress)
            .k("phase")
            .v(phase)
            .k("detail")
            .v(WebRecipeIndex.phase())
            .k("indexed")
            .v(indexReady)
            .k("handlers")
            .v(WebRecipeIndex.handlerCount())
            .k("recipes")
            .v(WebRecipeIndex.recipeCount())
            .k("items")
            .v(WebStore.size())
            .k("buildMillis")
            .v(WebRecipeIndex.buildMillis())
            .k("inGame")
            .v(WebClientTasks.inGame())
            // 下面几个是排查用的：目录建到哪了、NEI 到底注册了多少个处理器、
            // 这一轮目录一共要处理多少条。定位「进度一直不动」看这三个就够。
            .k("catalogReady")
            .v(catalogReady)
            .k("catalogTotal")
            .v(WebStore.sourceSize())
            .k("catalogProgress")
            .v((double) WebStore.progress())
            .k("neiHandlers")
            .v(WebRecipeIndex.registeredHandlerCount())
            .k("stockEntries")
            .v(WebStore.stockSize())
            // 「这份索引只有原版配方」——客户端还没进过世界时就是这样。页面据此提示玩家，
            // 免得他把「搜不到 GT 机器配方」当成搜索有问题
            .k("partial")
            .v(WebRecipeIndex.isPartial())
            .k("error")
            .v(WebRecipeIndex.error());
        return json.toString();
    }

    private static String searchJson(Map<String, String> query) {
        if (!WebStore.isReady()) return notReadyJson();
        WebStore.requestFreshStock();

        String text = query.containsKey("q") ? query.get("q") : "";
        int limit = clampInt(query.get("limit"), 40, 1, 200);
        int offset = clampInt(query.get("offset"), 0, 0, 1000000);

        List<Integer> ids = new ArrayList<>(limit);
        // 搜索本身要扫全表，顺手把总数也数出来，界面才能显示「共 N 个结果」
        int total = WebStore.search(text, limit, offset, ids);

        WebJson json = WebJson.object();
        json.k("ok")
            .v(true)
            .k("stockReady")
            // 客户端这份共享存储快照还没到时分不清「真的没有」和「还没同步」——
            // 界面要据此提示，而不是拿一个自己都不知道的答案去说「还缺」
            .v(WebStore.stockReady())
            .k("total")
            .v(total)
            .k("items")
            .arr();
        for (int i = 0; i < ids.size(); i++) {
            writeItemRef(json, ids.get(i), true);
        }
        json.end();
        return json.toString();
    }

    /**
     * 一个物品的配方详情。
     *
     * <p>
     * <b>配方要截断</b>：修好「产出记在其它槽位」那条之后，像「废料」这种回收产物的
     * 候选配方有上万条（NEI 里按 R 也是这样一屏一屏刷），全塞进一个响应里
     * 手机上根本打不开。所以只回前 {@link #MAX_RECIPES_PER_ITEM} 条，
     * 真实条数放在 {@code recipeTotal} 里，界面照实说明。
     */
    private static String itemJson(Map<String, String> query) {
        if (!WebRecipeIndex.isReady()) return notReadyJson();

        int id = clampInt(query.get("id"), -1, -1, Integer.MAX_VALUE);
        if (id < 0 || WebStore.stackOf(id) == null) return errorJson("这台客户端上没有这个物品（物品编号是每台客户端自己的，换客户端或存档后旧编号会失效）");
        // 身份重复的条目（同一种流体的另一套显示物品）指回正式那条：
        // 老计划、老书签里存的往往正是这些编号，不规整就会查到「0 条配方」那条
        id = WebStore.canonicalId(id);

        WebJson json = WebJson.object();
        json.k("ok")
            .v(true)
            .k("stockReady")
            // 客户端这份共享存储快照还没到时分不清「真的没有」和「还没同步」——
            // 界面要据此提示，而不是拿一个自己都不知道的答案去说「还缺」
            .v(WebStore.stockReady())
            .k("item");
        writeItemRef(json, id, true);

        // 一次回多少条：默认 80（手机上再多就打不开了），但允许前端要更多 ——
        // 「回收类」物品能有上万条配方，只给前 80 条而没有出路，等于那件东西查不了。
        int wanted = clampInt(query.get("rlimit"), MAX_RECIPES_PER_ITEM, 1, MAX_RECIPES_CEILING);

        json.k("recipes")
            .arr();
        int[] ordinals = WebRecipeIndex.recipesFor(id);
        int written = 0;
        for (int i = 0; i < ordinals.length && written < wanted; i++) {
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(ordinals[i]);
            if (view == null) continue;
            writeRecipe(json, view);
            written++;
        }
        json.end();

        // 用途（这个物品能拿去做成什么）不在本期范围内，先给空数组，
        // 界面按「暂无数据」显示，不要让它以为是自己解析挂了
        json.k("usedIn")
            .arr()
            .end();

        json.k("recipeTotal")
            .v(ordinals.length)
            .k("recipeShown")
            .v(written);
        return json.toString();
    }

    private static String planJson(Map<String, String> query) {
        if (!WebRecipeIndex.isReady()) return notReadyJson();
        // 规划最依赖库存（「还缺多少」全靠它），所以每次规划都顺手要一份最新的
        WebStore.requestFreshStock();

        int id = clampInt(query.get("id"), -1, -1, Integer.MAX_VALUE);
        if (id < 0 || WebStore.stackOf(id) == null) return errorJson("这台客户端上没有这个物品（物品编号是每台客户端自己的，换客户端或存档后旧编号会失效）");
        // 同上：规划的目标也要指回正式编号，否则「熔融焊锡」可能落在没有配方的那条重复条目上
        id = WebStore.canonicalId(id);

        WebPlanner.Request request = new WebPlanner.Request();
        request.itemId = id;
        request.count = clampLong(query.get("count"), 1L, 1L, 1000000L);
        // targets=3:64,7:3 —— 「合成 A 64 个 + B 3 个」。给了这个就以它为准，
        // id/count 只当兜底（老前端、老书签仍然能用）
        request.targets.addAll(parseTargets(query.get("targets")));
        request.useStock = !"0".equals(query.get("stock"));
        // targetstock=0：「目标产物不按库存扣，中间产物照旧扣」——
        // 我要 64 个 A、仓库里有 2 个，那还是要做 64 个（补货/交付），不是 62 个。
        // stock=0（全都不扣）时这个开关没有意义，自然被忽略（见 WebPlanner 的 usesStockFor）
        request.targetIgnoresStock = "0".equals(query.get("targetstock"));
        // debug=1：把「被掐断的物品各自选了什么配方」也写进警告里（排查「明明能做却报缺」用）
        request.debug = "1".equals(query.get("debug"));
        request.choices = parseChoices(query.get("choices"));
        // 多候选材料的指定：alts=主人的物品:格子的x:格子的y:选中的物品,...
        request.alts = parseAlts(query.get("alts"));
        request.raw = parseIntSet(query.get("raw"));
        // 非消耗品：catalyst 是「这个也算」（补自动判定漏的），
        // consumable 是「我就要按消耗算」（推翻自动判定）
        request.catalyst = parseIntSet(query.get("catalyst"));
        request.consumable = parseIntSet(query.get("consumable"));

        WebPlanner.Plan plan;
        try {
            plan = WebPlanner.plan(request);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：规划 {} 时出错", id, t);
            return errorJson("规划失败：" + t);
        }

        if (!plan.ok) return errorJson(plan.error == null ? "规划失败" : plan.error);

        WebJson json = WebJson.object();
        json.k("ok")
            .v(true)
            .k("stockReady")
            // 客户端这份共享存储快照还没到时分不清「真的没有」和「还没同步」——
            // 界面要据此提示，而不是拿一个自己都不知道的答案去说「还缺」
            .v(WebStore.stockReady())
            .k("warnings")
            .arr();
        for (int i = 0; i < plan.warnings.size(); i++) json.v(plan.warnings.get(i));
        json.end();

        json.k("target")
            .obj()
            .k("id")
            .v(plan.targetId)
            .k("name")
            .v(plan.targetName)
            .k("count")
            .v(plan.targetCount)
            .k("icon")
            .v(iconUrl(plan.targetId))
            .end();

        // 全部目标（「合成 A 64 个 + B 3 个」时不止一个）。
        // target 保留为第一个，老前端照旧能用
        json.k("targets")
            .arr();
        for (int i = 0; i < plan.targets.size(); i++) {
            WebPlanner.Target target = plan.targets.get(i);
            json.obj()
                .k("id")
                .v(target.itemId)
                .k("name")
                .v(WebStore.nameOf(target.itemId))
                .k("count")
                .v(target.count)
                .k("icon")
                .v(iconUrl(target.itemId))
                .end();
        }
        json.end();

        json.k("summary")
            .obj()
            .k("steps")
            .v(plan.steps.size())
            .k("materials")
            .v(plan.materials.size())
            .k("depth")
            .v(plan.depth)
            .k("truncated")
            .v(plan.truncated)
            .end();

        json.k("materials")
            .arr();
        for (int i = 0; i < plan.materials.size(); i++) {
            WebPlanner.Material material = plan.materials.get(i);
            json.obj()
                .k("id")
                .v(material.itemId)
                .k("name")
                .v(WebStore.nameOf(material.itemId))
                .k("icon")
                .v(iconUrl(material.itemId))
                .k("need")
                .v(material.need)
                .k("have")
                .v(material.have)
                // 流体的量是 mB，前端要按 mB 显示（写「64 个」会让人以为要 64 桶）
                .k("fluid")
                .v(WebStore.isFluidItem(material.itemId))
                .k("missing")
                .v(material.missing)
                .k("craftable")
                .v(WebRecipeIndex.hasRecipes(material.itemId))
                // 为什么它成了「要你自己准备」：没配方 / 你勾了原始材料 / 循环依赖 / 展开太深……
                .k("reason")
                .v(material.reason)
                .end();
        }
        json.end();

        json.k("steps")
            .arr();
        for (int i = 0; i < plan.steps.size(); i++) {
            WebPlanner.Step step = plan.steps.get(i);
            json.obj()
                .k("n")
                .v(step.n)
                .k("rid")
                // ★ 稳定编号，不是序号：序号随索引重建而变，而玩家选的配方是按它存的
                .v(String.valueOf(WebRecipeIndex.stableRid(step.ordinal)))
                .k("machine")
                .v(step.machine)
                .k("itemId")
                .v(step.itemId)
                .k("crafts")
                .v(step.crafts)
                // 这一步要等哪些步骤做完、以及属于第几批。
                // 界面据此算出「现在能并行做哪几步」——勾掉一步就自动往后放行
                .k("needs")
                .arr();
            for (int d = 0; d < step.needs.size(); d++) json.v(step.needs.get(d));
            json.end()
                .k("level")
                .v(step.level)
                .k("output")
                .obj()
                .k("id")
                .v(step.itemId)
                .k("name")
                .v(WebStore.nameOf(step.itemId))
                .k("icon")
                .v(iconUrl(step.itemId))
                .k("perCraft")
                .v(step.perCraft)
                .k("total")
                .v(step.total)
                .end()
                .k("inputs")
                .arr();
            writeIngredients(json, step.inputs);
            json.end()
                .k("extras")
                .arr();
            writeIngredients(json, step.extras);
            json.end()
                .k("note")
                .nul();

            // 把这条配方本身也带上（网格、材料槽、产出槽）。
            // 步骤里要「照着摆」，只有汇总过的材料清单是不够的 —— 清单里没有槽位坐标，
            // 玩家看不出该往哪一格放什么。
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(step.ordinal);
            json.k("recipe");
            if (view == null) {
                json.nul();
            } else {
                writeRecipe(json, view);
            }
            json.end();
        }
        json.end();
        return json.toString();
    }

    private static void writeIngredients(WebJson json, List<WebPlanner.Ingredient> ingredients) {
        for (int i = 0; i < ingredients.size(); i++) {
            WebPlanner.Ingredient ingredient = ingredients.get(i);
            json.obj()
                .k("id")
                .v(ingredient.itemId)
                .k("name")
                .v(WebStore.nameOf(ingredient.itemId))
                .k("icon")
                .v(iconUrl(ingredient.itemId))
                .k("perCraft")
                .v(ingredient.perCraft)
                .k("need")
                .v(ingredient.need)
                .k("have")
                .v(ingredient.have)
                .k("fluid")
                .v(WebStore.isFluidItem(ingredient.itemId))
                .k("missing")
                .v(ingredient.missing)
                .k("alternatives")
                .v(ingredient.alternatives)
                .k("craftable")
                .v(WebRecipeIndex.hasRecipes(ingredient.itemId));
            // 「任意一种都行」的槽位：把候选列出来。界面要显示「还可以用哪几种」，
            // 库存也是按整组算的（见 WebPlanner.stockOfAny）
            json.k("alts")
                .arr();
            if (ingredient.alts != null && ingredient.alts.length > 1) {
                for (int a = 0; a < ingredient.alts.length; a++) writeItemRef(json, ingredient.alts[a], false);
            }
            json.end()
                .end();
        }
    }

    /** 物品引用：{@code {id,name,mod,stock,craftable,icon}}。 */
    private static void writeItemRef(WebJson json, int itemId, boolean withStock) {
        // 重复条目的编号指回正式那条：界面上的名字、图标、配方数才和玩家点开的那个对得上
        itemId = WebStore.canonicalId(itemId);
        json.obj()
            .k("id")
            .v(itemId)
            .k("name")
            .v(WebStore.nameOf(itemId))
            .k("mod")
            .v(WebStore.modOf(itemId))
            .k("icon")
            .v(iconUrl(itemId))
            .k("craftable")
            .v(WebRecipeIndex.hasRecipes(itemId));
        if (withStock) {
            json.k("stock")
                .v(WebStore.stockOf(itemId));
        }
        json.end();
    }

    /**
     * 库存诊断（{@code /api/stock?q=过硫酸钠&id=53040}，不进界面，纯粹给排查用）。
     *
     * <p>
     * 「仓库里明明有 426k 过硫酸钠，规划却说缺 216500L」这类问题，光看计划页永远查不出来：
     * 缺的可能不是数量，而是两边的<b>身份</b>对不上（存储里存的是流体的本地化名 + 注册名，
     * 配方里用的是 GT 造的流体显示物品）。这个接口把两边的原始身份并排摆出来：
     * <ul>
     * <li>{@code fluids} —— 共享存储流体表：显示名 / 注册名 / 数量；</li>
     * <li>{@code items} —— 共享存储物品表：栈键 / 数量 / 目录 id（-1 = 目录里没有这个身份）；</li>
     * <li>{@code probe} —— 指定 id 的目录身份：目录里存的名字、身份键、栈键、
     * <b>现读</b>显示名、认出来的流体注册名、以及最终查到的库存。</li>
     * </ul>
     * 名字对不上时，{@code name} 和 {@code freshName} 这两个字段就会不一样 —— 一眼可见。
     */
    private static String stockJson(Map<String, String> query) {
        WebStore.requestFreshStock();

        WebJson json = WebJson.object();
        json.k("ok")
            .v(true)
            .k("stockReady")
            .v(WebStore.stockReady())
            .k("itemEntries")
            .v(WebStore.stockSize())
            .k("fluidEntries")
            .v(WebStore.fluidTableSize());

        int probe = clampInt(query.get("id"), -1, -1, Integer.MAX_VALUE);
        if (probe >= 0) {
            json.k("probe")
                .obj()
                .k("id")
                .v(probe)
                .k("name")
                .v(WebStore.nameOf(probe))
                // 目录里存的身份键（建索引时拿真栈算的）与栈键
                .k("key")
                .v(WebStore.keyOfId(probe))
                .k("stackKey")
                .v(WebStore.stackKeyOf(probe))
                // 缓存回读出来的栈没有 NBT，这里现读的名字可能和上面那个不一样
                .k("freshName")
                .v(WebStore.freshNameOf(probe))
                .k("fluidRegistry")
                .v(WebStore.fluidRegistryOfId(probe) == null ? "" : WebStore.fluidRegistryOfId(probe))
                .k("fluid")
                .v(WebStore.isFluidItem(probe))
                .k("stock")
                .v(WebStore.stockOf(probe))
                .end();
        }

        int limit = clampInt(query.get("limit"), 40, 1, 200);
        String q = query.containsKey("q") ? query.get("q") : "";

        json.k("fluids")
            .arr();
        for (String[] row : WebStore.fluidRows(q, limit)) {
            json.obj()
                .k("name")
                .v(row[0])
                .k("registry")
                .v(row[1])
                .k("amount")
                .v(row[2])
                .end();
        }
        json.end();

        json.k("items")
            .arr();
        for (String[] row : WebStore.itemRows(q, limit)) {
            json.obj()
                .k("key")
                .v(row[0])
                .k("amount")
                .v(row[1])
                .k("id")
                .v(Long.parseLong(row[2]))
                .k("name")
                .v(row[3])
                .end();
        }
        json.end();
        return json.toString();
    }

    private static void writeRecipe(WebJson json, WebRecipeIndex.RecipeView view) {
        json.obj()
            .k("rid")
            // ★ 稳定编号（见 WebRecipeIndex.stableRid）：玩家「选这个配方」存的就是它，
            // 用序号的话，升级一次模组/重建一次索引，存下来的选择就变成了同名的另一条配方
            .v(String.valueOf(WebRecipeIndex.stableRid(view.ordinal)))
            .k("machine")
            .v(view.machine)
            .k("handler")
            .v(view.handler)
            // 这一类配方在 NEI 里的代表图标（烧制 = 熔炉、组装机 = 组装机方块）。
            // 界面合并同类型配方之后，组标题上要有个图，认图比认字快
            .k("tabIcon")
            .v(WebRecipeIndex.iconIdFor(view.handler))
            .k("grid")
            .obj()
            .k("w")
            .v(view.gridW)
            .k("h")
            .v(view.gridH)
            .end()
            .k("resultId")
            .v(view.resultId)
            .k("resultAmount")
            .v(view.resultAmount)
            // GT 机器的耗电与耗时（非 GT 配方是 0）：排产要看「吃多少电、要多久」
            .k("euPerTick")
            .v(view.euPerTick)
            .k("durationTicks")
            .v(view.durationTicks)
            .k("inputs")
            .arr();
        for (int i = 0; i < view.inputs.length; i++) writeSlot(json, view.inputs[i]);
        json.end()
            .k("outputs")
            .arr();
        for (int i = 0; i < view.outputs.length; i++) writeSlot(json, view.outputs[i]);
        json.end()
            .k("extras")
            .arr();
        for (int i = 0; i < view.extras.length; i++) writeSlot(json, view.extras[i]);
        json.end()
            .end();
    }

    private static void writeSlot(WebJson json, WebRecipeIndex.Slot slot) {
        json.obj()
            .k("x")
            .v(slot.x)
            .k("y")
            .v(slot.y)
            .k("count")
            .v(slot.amount)
            // NEI 内部把概率放大了一百倍存（10000 = 100%），这里换回百分数
            .k("chance")
            .v(slot.chance / 100)
            .k("altTotal")
            .v(slot.altTotal)
            .k("primary");
        int slotPrimary = slot.alts.length > 0 ? slot.alts[0] : -1;
        writeItemRef(json, slotPrimary, true);
        // 格子也要带「这是流体」标记：右下角那个数字是 mB，不加 L 会被当成个数
        json.k("fluid")
            .v(WebStore.isFluidItem(slotPrimary));
        json.k("alts")
            .arr();
        int shown = Math.min(slot.alts.length, WebRecipeIndex.MAX_ALT_DISPLAY);
        for (int i = 0; i < shown; i++) {
            writeItemRef(json, slot.alts[i], false);
        }
        json.end()
            .end();
    }

    private static String iconUrl(int itemId) {
        if (itemId < 0) return "";
        return "/api/icon/" + itemId + ".png?v=" + BOOT_ID;
    }

    private static String notReadyJson() {
        WebJson json = WebJson.object();
        json.k("ok")
            .v(false)
            .k("building")
            .v(true)
            .k("progress")
            .v((double) WebRecipeIndex.progress())
            .k("error")
            .v("配方索引还在建立，稍等一下")
            .k("phase")
            .v(WebRecipeIndex.phase());
        return json.toString();
    }

    private static String errorJson(String message) {
        WebJson json = WebJson.object();
        json.k("ok")
            .v(false)
            .k("error")
            .v(message);
        return json.toString();
    }

    // ==================================================================
    // 参数解析
    // ==================================================================

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] pairs = raw.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            String key = eq < 0 ? pairs[i] : pairs[i].substring(0, eq);
            String value = eq < 0 ? "" : pairs[i].substring(eq + 1);
            try {
                key = URLDecoder.decode(key, "UTF-8");
                value = URLDecoder.decode(value, "UTF-8");
            } catch (Throwable ignored) {
                // 解不开就用原串，反正后面还有一层数值校验
            }
            if (!out.containsKey(key)) out.put(key, value);
        }
        return out;
    }

    private static int clampInt(String raw, int fallback, int min, int max) {
        if (raw == null) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            return (int) Math.max(min, Math.min(max, value));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static long clampLong(String raw, long fallback, long min, long max) {
        if (raw == null) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * {@code "42:3:1:100,42:5:1:200"} -> 「物品 42 的配方里，(3,1) 那格用 100，(5,1) 那格用 200」。
     *
     * <p>
     * 解析不了的整条跳过：少一条指定最多是「用了默认候选」，而报错会让整个页面打不开。
     * 真正的合法性（那个物品是否真的在这一格的候选里）由 WebPlanner.pickAlt 校验。
     */
    private static Map<String, Integer> parseAlts(String raw) {
        Map<String, Integer> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String[] fields = parts[i].trim()
                .split(":");
            if (fields.length != 4) continue;
            try {
                int owner = Integer.parseInt(fields[0].trim());
                int x = Integer.parseInt(fields[1].trim());
                int y = Integer.parseInt(fields[2].trim());
                int chosen = Integer.parseInt(fields[3].trim());
                out.put(WebStore.canonicalId(owner) + ":" + x + ":" + y, Integer.valueOf(WebStore.canonicalId(chosen)));
            } catch (NumberFormatException ignored) {
                // 这一条不要了
            }
        }
        return out;
    }

    /** {@code "42:123,77:456"} -> 物品 42 用配方 123、物品 77 用配方 456。 */
    private static Map<Integer, Integer> parseChoices(String raw) {
        Map<Integer, Integer> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] pairs = raw.split(",");
        for (int i = 0; i < pairs.length && out.size() < 512; i++) {
            int colon = pairs[i].indexOf(':');
            if (colon <= 0) continue;
            try {
                int itemId = Integer.parseInt(
                    pairs[i].substring(0, colon)
                        .trim());
                int ordinal = Integer.parseInt(
                    pairs[i].substring(colon + 1)
                        .trim());
                if (itemId >= 0 && ordinal >= 0) out.put(WebStore.canonicalId(itemId), ordinal);
            } catch (Throwable ignored) {
                // 单个坏值跳过，不影响其余的
            }
        }
        return out;
    }

    private static Set<Integer> parseIntSet(String raw) {
        Set<Integer> out = new HashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length && out.size() < 2048; i++) {
            try {
                int value = Integer.parseInt(parts[i].trim());
                if (value >= 0) out.add(WebStore.canonicalId(value));
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return out;
    }

    // ==================================================================
    // 输出
    // ==================================================================

    private static void serveStatic(HttpExchange exchange, String name) throws IOException {
        if (!STATIC_FILES.contains(name)) {
            sendJson(exchange, 404, errorJson("没有这个文件"));
            return;
        }
        byte[] data;
        synchronized (STATIC_CACHE) {
            data = STATIC_CACHE.get(name);
        }
        if (data == null) {
            data = readResource(name);
            if (data == null) {
                sendJson(exchange, 404, errorJson("模组包里缺少页面资源 " + name));
                return;
            }
            synchronized (STATIC_CACHE) {
                STATIC_CACHE.put(name, data);
            }
        }
        // 页面和脚本每次都要重新校验（改完 mod 直接刷新就能看到），
        // 这样调试时不用教玩家「清一下浏览器缓存」
        // 页面资源一律 no-store。
        //
        // 原来是 no-cache（用前先问一下），但这类「问一下」在手机上经常被跳过，
        // 结果是玩家升级了模组、界面却还是旧的 —— 排查时非常误导（改好的样式量出来还是旧值）。
        // 这是个本机小服务，重下一次 app.js 的代价可以忽略，宁可每次都拿新的。
        sendBytes(exchange, 200, contentTypeOf(name), data, name.endsWith(".ico") ? "max-age=86400" : "no-store");
    }

    private static byte[] readResource(String name) {
        InputStream stream = null;
        try {
            stream = WebRecipeServer.class.getResourceAsStream(RESOURCE_ROOT + name);
            if (stream == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：读取页面资源 {} 失败", name, t);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // 无所谓
                }
            }
        }
    }

    private static void sendIcon(HttpExchange exchange, String tail) throws IOException {
        String value = tail;
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);

        int itemId;
        try {
            itemId = Integer.parseInt(value);
        } catch (Throwable t) {
            sendJson(exchange, 404, errorJson("图标名不对"));
            return;
        }

        net.minecraft.item.ItemStack stack = WebStore.stackOf(itemId);
        WebIcons.Icon icon = stack == null ? null : WebIcons.iconOf(stack);
        if (icon == null || icon.png == null) {
            // 404 是正常的：GTNH 里有些物品的图标是运行时画出来的，没有对应的贴图文件，
            // 界面会用文字占位块兜底
            sendJson(exchange, 404, errorJson("没有图标"));
            return;
        }
        // 让「这张图是画出来的还是退回读贴图的」可观测：静默退回会伪装成一切正常
        exchange.getResponseHeaders()
            .set("X-Icon-Source", icon.rendered ? "render" : "texture");
        sendBytes(exchange, 200, "image/png", icon.png, "max-age=31536000, immutable");
    }

    /**
     * 解析 {@code targets=3:64,7:3} 这种「多个目标」参数。
     *
     * <p>
     * 格式和 {@code choices} 一致（id:数量），坏数据直接跳过 ——
     * 这里少一个目标顶多算得少，报错反而让玩家连页面都打不开。
     */
    private static List<WebPlanner.Target> parseTargets(String value) {
        List<WebPlanner.Target> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length && out.size() < MAX_TARGETS; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            int colon = part.indexOf(':');
            try {
                if (colon < 0) {
                    out.add(new WebPlanner.Target(WebStore.canonicalId(Integer.parseInt(part)), 1L));
                } else {
                    out.add(
                        new WebPlanner.Target(
                            WebStore.canonicalId(Integer.parseInt(part.substring(0, colon))),
                            Long.parseLong(part.substring(colon + 1))));
                }
            } catch (NumberFormatException ignored) {
                // 这一条不要了，别的照常
            }
        }
        return out;
    }

    private static String contentTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        sendBytes(exchange, code, "application/json; charset=utf-8", json.getBytes("UTF-8"), "no-store");
    }

    /**
     * 这个异常是不是「对面把连接关了」。
     *
     * <p>
     * 判据只能是消息文本：JDK 内置服务器在写响应体时抛的就是一个普通
     * {@code IOException}，而不同平台、不同语言下文案完全不同
     * （英文 {@code Connection reset} / {@code Broken pipe}，
     * 中文 Windows 是「你的主机中的软件中止了一个已建立的连接」）。
     * 所以中英关键词都认一遍，另外把两类明确的异常类型也认下来。
     */
    private static boolean isClientGone(Throwable t) {
        Throwable current = t;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof java.nio.channels.ClosedChannelException) return true;
            if (current instanceof java.net.SocketException) return true;

            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("aborted") || lower.contains("reset")
                    || lower.contains("broken pipe")
                    || lower.contains("connection closed")
                    || lower.contains("stream closed")
                    || message.contains("中止")
                    || message.contains("重置")
                    || message.contains("管道")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sendHtml(HttpExchange exchange, int code, String html) throws IOException {
        sendBytes(exchange, code, "text/html; charset=utf-8", html.getBytes("UTF-8"), "no-store");
    }

    private static void sendBytes(HttpExchange exchange, int code, String contentType, byte[] data, String cache)
        throws IOException {
        byte[] body = data;
        boolean gzip = false;
        String accept = exchange.getRequestHeaders()
            .getFirst("Accept-Encoding");
        if (data.length > 1024 && accept != null && accept.contains("gzip")) {
            byte[] compressed = gzip(data);
            if (compressed != null && compressed.length < data.length) {
                body = compressed;
                gzip = true;
            }
        }

        exchange.getResponseHeaders()
            .set("Content-Type", contentType);
        exchange.getResponseHeaders()
            .set("Cache-Control", cache);
        exchange.getResponseHeaders()
            .set("Access-Control-Allow-Origin", "*");
        if (gzip) {
            exchange.getResponseHeaders()
                .set("Content-Encoding", "gzip");
        }

        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(code, -1);
            return;
        }
        exchange.sendResponseHeaders(code, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.flush();
    }

    private static byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(data.length / 2 + 64);
            GZIPOutputStream gzip = new GZIPOutputStream(buffer);
            gzip.write(data);
            gzip.close();
            return buffer.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // 访问口令
    // ==================================================================

    private static boolean authorized(HttpExchange exchange, Map<String, String> query) {
        if (token.isEmpty()) return true;

        String provided = query.get("k");
        if (token.equals(provided)) {
            // 带上之后就发个 Cookie，省得手机地址栏里一直挂着口令
            exchange.getResponseHeaders()
                .add("Set-Cookie", "futaweb=" + token + "; Path=/; Max-Age=31536000; SameSite=Lax");
            return true;
        }

        String cookie = exchange.getRequestHeaders()
            .getFirst("Cookie");
        if (cookie != null) {
            String[] parts = cookie.split(";");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.startsWith("futaweb=") && token.equals(part.substring(8))) return true;
            }
        }
        return false;
    }

    private static String unauthorizedPage() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>需要访问口令</title></head><body style=\"font-family:sans-serif;background:#14161a;color:#e8eaed;"
            + "padding:32px;line-height:1.7\"><h2>需要访问口令</h2>"
            + "<p>这个页面在配置里设了访问口令。请用游戏里给的完整地址打开"
            + "（形如 <code>http://电脑IP:端口/?k=口令</code>）。</p></body></html>";
    }
}
