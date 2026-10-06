package com.futa_gtnh.web;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;

/**
 * 全量配方索引：把 NEI 里所有「产出物品」的配方拍平成一张列式表。
 *
 * <p>
 * <b>为什么要一次性全量拍平，而不是「查某个物品时再去问 NEI」</b>：
 * NEI 的每一次查询都会 {@code newInstance()} 出一个处理器实例、把它那一整张
 * 配方表重新加载一遍（GT 的每张机器配方表都是几千到几万条）。单个物品查询还能忍，
 * 但「合成 64 个 XX，一步步怎么做」这件事本身要沿材料树往下展开几十上百个节点，
 * 每个节点都要问一次 —— 那就是几十秒起步，手机上完全没法用。
 *
 * <p>
 * 所以这里换个方向：启动后在后台把全部配方读一遍，压成一张紧凑的表，
 * 之后所有查询（配方列表、材料树、分步指导）都只查这张表，一次都不再碰 NEI。
 * 代价是第一次要跑一会儿（有进度显示），而且这份表会存到
 * {@code config/futa_gtnh/web_recipes.dat}：只要模组列表没变，下次开游戏是秒开的。
 *
 * <p>
 * <b>存储布局是「列式」的</b>：不建对象，全是平行数组（{@code int[]}/{@code short[]}），
 * 配方之间用 offset 数组切分。二十万条配方连原料加起来也就几十兆，
 * 换成「每条配方一个对象 + 一个 ArrayList」会直接吃掉几个 G。
 *
 * <p>
 * <b>配方 id 就是它在表里的序号</b>（十进制字符串）。序号由处理器的确定性排序决定，
 * 所以同一套模组下重开游戏是稳定的 —— 玩家在手机上选过的配方下次还能用。
 * 万一模组变动导致错位，规划时会核对「这条配方产出的确实是那个物品」，
 * 对不上就当作没选过，退回默认配方。
 */
public final class WebRecipeIndex {

    private WebRecipeIndex() {}

    /** 缓存文件格式版本；字段变了就 +1，老文件会被直接丢掉重建。 */
    // 6：流体的「一次用多少」改成从流体 NBT 里读（原来一律读 stackSize，
    // 于是「每次 144 L」被当成 1）。用量是写进缓存的数据，所以必须让旧缓存作废 ——
    // 否则改完代码看着毫无变化，因为读的还是那份旧索引。
    private static final int FILE_VERSION = 14;

    private static final int MAGIC = 0x46574542; // "FWEB"

    /** 一个槽位最多展示几个候选（矿物词典替代品）。 */
    public static final int MAX_ALT_DISPLAY = 8;

    // ==================================================================
    // 状态（HTTP 线程随时在读，全部 volatile）
    // ==================================================================

    private static volatile boolean building;
    private static volatile boolean ready;
    private static volatile boolean failed;
    private static volatile String phase = "未启动";
    private static volatile String lastError;
    private static volatile int handlersDone;
    private static volatile int handlersTotal;
    private static volatile long buildMillis;
    private static volatile Snapshot snapshot;

    /**
     * 每条配方的<b>稳定编号</b>（跨会话、跨索引重建都不变），按需算、算过就缓存。
     *
     * <p>
     * 为什么不能用序号：序号是「这次索引里第几条配方」，处理器数量、索引顺序一变它就变了。
     * 而玩家选的配方、施工进度都是按这个编号存在浏览器里的 ——
     * 升级一次模组，玩家「选的配方」就悄悄变成了同名的另一条。
     * 实测：给熔融焊锡选的提取机配方变成了另一条回收配方，规划于是绕回自己报缺料。
     */
    private static volatile int[] stableRids;
    private static volatile List<String> handlerNames = Collections.emptyList();
    private static volatile List<String> handlerTags = Collections.emptyList();
    /**
     * 每个配方类别的「代表图标」物品键（{@code registryName@meta}），没有就是空串。
     *
     * <p>
     * 存的是<b>物品键</b>而不是编号：编号每个会话都会重排（见 README 的已知问题），
     * 而且这份表要写进缓存、下次开局直接用。真正要图标时再用键换成当前编号。
     */
    private static volatile List<String> handlerIcons = Collections.emptyList();

    private static Thread buildThread;
    private static File cacheFile;
    private static String fingerprint = "";

    public static boolean isBuilding() {
        return building;
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isFailed() {
        return failed;
    }

    /**
     * NEI 那边是否已经就绪到可以开建索引了。
     *
     * <p>
     * 只等一件事：配方处理器注册完（NEI 是在 {@code LoadComplete} 阶段加载各模组插件的，
     * GT 那一百多张配方表就是那时候进来的）。早一步开建读到的是一张空表，
     * 而且会把这份空索引写进缓存。
     *
     * <p>
     * <b>刻意不等 {@code ItemList.loadFinished}</b>：NEI 的物品清单是它自己按需加载的，
     * 实测在标题界面待着不动它会一直停在「没加载完」。索引也并不需要那份清单 ——
     * 配方里出现的每个物品都是就地登记进物品目录的。等它只会让功能在主菜单永远起不来。
     */
    public static boolean canBuild() {
        return registeredHandlerCount() > 0;
    }

    /**
     * 索引是不是「只有原版配方」。
     *
     * <p>
     * NEI 只在<b>进过世界之后</b>才注册各模组的配方处理器：停在标题界面时只有十几个
     * 原版处理器（有序/无序合成、烧制……），GT 那两百多张配方表一张都不在。
     * 这时候页面看起来是好的，但搜什么机器都「没有配方」—— 玩家会以为是自己搜错了。
     * 所以这个状态要能报出去，让页面直说「这个客户端还没进过世界」。
     *
     * <p>
     * 判据是处理器数量：纯原版 NEI 是十几个，任何一个真装了模组的整合包都是几百个，
     * 中间没有东西。从缓存里读出来的完整索引不受影响（它自带处理器名表）。
     */
    public static boolean isPartial() {
        int built = builtHandlerCount();
        return ready && built > 0 && built < PARTIAL_HANDLER_THRESHOLD;
    }

    /** 低于这个处理器数就认为「只有原版」，见 {@link #isPartial()}。 */
    private static final int PARTIAL_HANDLER_THRESHOLD = 30;

    /** 当前 NEI 注册了多少个配方处理器（诊断用；NEI 不在场时返回 -1）。 */
    public static int registeredHandlerCount() {
        try {
            return GuiCraftingRecipe.craftinghandlers.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 上一次建立索引时用了多少个处理器。
     *
     * <p>
     * 这个数字是「索引是否还完整」的判据：NEI 注册各模组处理器<b>不是一次性完成的</b>
     * （实测标题界面刚起来时只有十几个原版处理器，GT 那一百多张配方表要更晚才进来），
     * 所以 {@code WebRecipeService} 会定期拿它和当前值比，一旦变多就重建索引。
     * 重建一次只要一两秒，比「等到确定齐全再建」简单得多，也不会让页面空等。
     *
     * @return 上次建索引时的处理器数；还没建过时返回 -1
     */
    public static int builtHandlerCount() {
        if (snapshot == null) return -1;
        return handlerNames.size();
    }

    public static String phase() {
        return phase;
    }

    public static String error() {
        return lastError;
    }

    public static float progress() {
        if (ready) return 1.0F;
        int total = handlersTotal;
        return total <= 0 ? 0.0F : Math.min(1.0F, (float) handlersDone / (float) total);
    }

    public static int recipeCount() {
        Snapshot local = snapshot;
        return local == null ? 0 : local.recipeCount;
    }

    public static int handlerCount() {
        return handlerNames.size();
    }

    public static long buildMillis() {
        return buildMillis;
    }

    public static File cacheFile() {
        return cacheFile;
    }

    // ==================================================================
    // 启动
    // ==================================================================

    /**
     * 开始建立索引（幂等）。
     *
     * @param cacheDir 缓存文件所在目录；为 null 时不落盘
     */
    public static synchronized void start(File cacheDir) {
        if (building || ready || buildThread != null) return;

        if (cacheDir != null) {
            cacheFile = new File(cacheDir, "web_recipes.dat");
        }

        building = true;
        phase = "正在收集 NEI 配方处理器";
        buildThread = new Thread(WebRecipeIndex::runBuild, "futa-web-recipes");
        buildThread.setDaemon(true);
        buildThread.start();
    }

    private static void runBuild() {
        long begin = System.currentTimeMillis();
        try {
            fingerprint = computeFingerprint();
            if (loadFromDisk()) {
                phase = "就绪（缓存）";
                buildMillis = System.currentTimeMillis() - begin;
                FutaGtnhMod.LOG
                    .info("网页配方：索引缓存命中，{} 条配方 / {} 个处理器，耗时 {} ms", recipeCount(), handlerCount(), buildMillis);
                return;
            }
            buildAll();
            saveToDisk();
            phase = "就绪";
            buildMillis = System.currentTimeMillis() - begin;
            FutaGtnhMod.LOG.info("网页配方：索引建立完成，{} 条配方 / {} 个处理器，耗时 {} ms", recipeCount(), handlerCount(), buildMillis);
        } catch (Throwable t) {
            failed = true;
            lastError = String.valueOf(t);
            phase = "建立索引失败";
            FutaGtnhMod.LOG.error("网页配方：建立索引失败（网页还能开，但查不到配方）", t);
        } finally {
            building = false;
        }
    }

    /** 丢弃缓存并重建（改完模组不想等下次启动时用）。 */
    public static synchronized void rebuild() {
        if (building) return;
        snapshot = null;
        stableRids = null;
        ready = false;
        failed = false;
        buildThread = null;
        File file = cacheFile;
        if (file != null && file.isFile() && !file.delete()) {
            FutaGtnhMod.LOG.warn("网页配方：删除旧缓存失败，本次重建后可能仍读到旧数据");
        }
        start(file == null ? null : file.getParentFile());
    }

    // ==================================================================
    // 构建
    // ==================================================================

    /** 处理器标识：{@code 处理器 id | overlay 标识}，用来做确定性排序和缓存失效判定。 */
    private static String handlerTag(ICraftingHandler handler) {
        String id;
        String overlay;
        try {
            id = String.valueOf(handler.getHandlerId());
        } catch (Throwable t) {
            id = handler.getClass()
                .getName();
        }
        try {
            overlay = String.valueOf(handler.getOverlayIdentifier());
        } catch (Throwable t) {
            overlay = "";
        }
        return id + '|' + overlay;
    }

    /**
     * 读一条 GT 配方的 EU/t 与耗时。
     *
     * <p>
     * 数据藏在 GT 自己的 {@code GTRecipe} 里（{@code mEUt} / {@code mDuration}），
     * 而 NEI 的处理器把它包在 {@code CachedDefaultRecipe} 里、放在公开的
     * {@code arecipes} 列表里 —— 所以这里能顺着拿到，不用反射去掏私有字段。
     *
     * <p>
     * <b>不能写死成 GT 类型</b>：GTNH 里装了哪些模组是会变的（也为了让这个类
     * 在没装 GT 的环境下仍然能编译通过），所以按类名判断、拿到就转成两个 int。
     * 非 GT 配方返回 null，界面据此不显示这一段。
     *
     * @return {@code {EU/t, 耗时 tick}}；不是 GT 配方时返回 null
     */
    private static int[] gtPower(ICraftingHandler handler, int index) {
        if (!(handler instanceof codechicken.nei.recipe.TemplateRecipeHandler)) return null;
        try {
            java.util.List<codechicken.nei.recipe.TemplateRecipeHandler.CachedRecipe> cached = ((codechicken.nei.recipe.TemplateRecipeHandler) handler).arecipes;
            if (cached == null || index < 0 || index >= cached.size()) return null;

            Object entry = cached.get(index);
            if (entry == null) return null;
            String name = entry.getClass()
                .getName();
            if (!name.startsWith("gregtech.") && !name.startsWith("gtPlusPlus.")
                && !name.startsWith("bartworks.")
                && !name.startsWith("kubatech.")) {
                return null;
            }

            java.lang.reflect.Field field = entry.getClass()
                .getField("mRecipe");
            Object recipe = field.get(entry);
            if (recipe == null) return null;
            Class<?> type = recipe.getClass();
            int eu = type.getField("mEUt")
                .getInt(recipe);
            int duration = type.getField("mDuration")
                .getInt(recipe);
            return new int[] { eu, duration };
        } catch (Throwable t) {
            // 拿不到就当没有：这条配方照样能用，只是不显示电压耗时
            return null;
        }
    }

    /**
     * 这个配方类别在 NEI 里用哪个物品当图标（烧制 = 熔炉、组装机 = 组装机方块……）。
     *
     * <p>
     * 界面把同类型的配方合并成一组之后，光有名字不够 —— 一排「有序合成 / 无序合成 /
     * 烧制」看下来，认图比认字快得多。NEI 自己就是靠这个给配方标签栏画图标的
     * （{@code GuiRecipeTab.getHandlerInfo(handler).getItemStack()}）。
     * 少数处理器没有物品图标（用的是贴图资源），那种返回空串，界面退回纯文字。
     */
    private static String handlerIconKey(ICraftingHandler handler) {
        try {
            net.minecraft.item.ItemStack icon = codechicken.nei.recipe.GuiRecipeTab.getHandlerInfo(handler)
                .getItemStack();
            if (icon == null || icon.getItem() == null) return "";
            int id = WebStore.idOf(icon);
            return id < 0 ? "" : WebStore.keyOfId(id);
        } catch (Throwable t) {
            // 拿不到图标不影响别的任何东西，安静跳过
            return "";
        }
    }

    /**
     * 某个配方类别该用哪个物品当图标，返回**当前会话**的物品编号；没有返回 -1。
     *
     * <p>
     * 表里存的是物品键，这里换成编号 —— 编号每个会话都会重排，存编号的话
     * 下次开局图标就会张冠李戴。
     */
    public static int iconIdFor(String handlerTag) {
        if (handlerTag == null || handlerTag.isEmpty()) return -1;
        List<String> tags = handlerTags;
        List<String> icons = handlerIcons;
        for (int i = 0; i < tags.size() && i < icons.size(); i++) {
            if (!handlerTag.equals(tags.get(i))) continue;
            String key = icons.get(i);
            return key == null || key.isEmpty() ? -1 : WebStore.idOfKey(key);
        }
        return -1;
    }

    private static String handlerDisplayName(ICraftingHandler handler) {
        try {
            String name = handler.getRecipeTabName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
            // 少数处理器这里会抛，退回类名就够了
        }
        try {
            String name = handler.getRecipeName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
            // 同上
        }
        return handler.getClass()
            .getSimpleName();
    }

    private static List<ICraftingHandler> collectHandlers() {
        List<ICraftingHandler> copy = new ArrayList<>(GuiCraftingRecipe.craftinghandlers);
        final Map<ICraftingHandler, Integer> registrationOrder = new HashMap<>();
        for (int i = 0; i < copy.size(); i++) {
            registrationOrder.put(copy.get(i), i);
        }
        // 排序必须确定性：配方 id 是「表里的序号」，处理器顺序一变，
        // 玩家在手机上存下的配方选择就会指到别的配方上去（解析时有核对兜底）
        Collections.sort(copy, new Comparator<ICraftingHandler>() {

            @Override
            public int compare(ICraftingHandler a, ICraftingHandler b) {
                int byTag = handlerTag(a).compareTo(handlerTag(b));
                if (byTag != 0) return byTag;
                Integer oa = registrationOrder.get(a);
                Integer ob = registrationOrder.get(b);
                return Integer.compare(oa == null ? 0 : oa, ob == null ? 0 : ob);
            }
        });
        return copy;
    }

    /**
     * 缓存指纹：格式版本 + <b>模组列表</b>（id 与版本，排序后拼起来）。
     *
     * <p>
     * <b>刻意不用「配方处理器列表」当指纹。</b>踩过一次：处理器是 NEI 按需注册的，
     * 加载缓存那一刻（{@code LoadComplete} 之后）往往只有十几个原版处理器，
     * 而缓存是进世界之后用两百多个处理器建出来的 —— 指纹永远对不上，
     * 这份缓存也就永远命不中，等于白写。
     *
     * <p>
     * 模组列表在 {@code LoadComplete} 时已经稳定，而且「模组没变 → 处理器最终也会一样」。
     * 处理器临时比缓存少的情况不用管：缓存里连处理器名字一起存着（见 {@code saveToDisk}），
     * 界面显示不受影响；真的比缓存多了，外面那条「处理器变多就重建」会兜住。
     */
    private static String computeFingerprint() {
        StringBuilder builder = new StringBuilder(8192);
        builder.append(FILE_VERSION)
            .append(';');
        try {
            List<String> entries = new ArrayList<>();
            for (cpw.mods.fml.common.ModContainer mod : cpw.mods.fml.common.Loader.instance()
                .getActiveModList()) {
                entries.add(mod.getModId() + "|" + mod.getVersion());
            }
            Collections.sort(entries);
            for (int i = 0; i < entries.size(); i++) {
                builder.append(entries.get(i))
                    .append(';');
            }
        } catch (Throwable t) {
            builder.append("error:")
                .append(t);
        }
        return builder.toString();
    }

    private static void buildAll() {
        List<ICraftingHandler> handlers = collectHandlers();
        List<String> skippedHandlers = new ArrayList<>();
        handlers = filterHandlers(handlers, skippedHandlers);
        if (!skippedHandlers.isEmpty()) {
            FutaGtnhMod.LOG.info(
                "网页配方：按配置跳过 {} 个处理器（不是合成来源的，如战利品袋/任务奖励）：{}",
                Integer.valueOf(skippedHandlers.size()),
                String.join("；", skippedHandlers));
        }
        List<String> names = new ArrayList<>(handlers.size());
        List<String> tags = new ArrayList<>(handlers.size());
        List<String> icons = new ArrayList<>(handlers.size());
        for (int i = 0; i < handlers.size(); i++) {
            names.add(handlerDisplayName(handlers.get(i)));
            tags.add(handlerTag(handlers.get(i)));
            icons.add(handlerIconKey(handlers.get(i)));
        }
        handlerNames = names;
        handlerTags = tags;
        handlerIcons = icons;
        handlersTotal = handlers.size();

        Builder builder = new Builder(handlers.size());
        int failedHandlers = 0;

        for (int slot = 0; slot < handlers.size(); slot++) {
            ICraftingHandler handler = handlers.get(slot);
            if (building) phase = "正在读取配方：" + names.get(slot);

            try {
                ICraftingHandler loaded = allRecipesOf(handler);
                if (loaded != null) {
                    int count = loaded.numRecipes();
                    for (int index = 0; index < count; index++) {
                        builder.addRecipe(loaded, slot, index);
                    }
                }
            } catch (Throwable t) {
                // 单个处理器炸掉不能带走整轮构建：记一笔，继续下一个
                failedHandlers++;
                FutaGtnhMod.LOG.warn("网页配方：处理器 {} 读取失败，已跳过", names.get(slot), t);
            }
            handlersDone = slot + 1;
            phase = "正在读取配方：" + names.get(slot) + "（" + (slot + 1) + "/" + handlers.size() + "）";
        }

        Snapshot built = builder.build();
        snapshot = built;
        stableRids = null;
        ready = true;
        phase = "就绪";
        reportSummary(built, names, failedHandlers);
    }

    /**
     * 按配置（{@code webRecipeSkipHandlers}）剔掉不该进索引的处理器。
     *
     * <p>
     * 有些 NEI「配方」不是合成方法，而是「这东西能从哪来」：战利品袋、任务奖励、
     * 世界生成战利品表。它们会把一堆东西标成「可合成」，默认挑配方时又因为材料极少
     * 永远排第一，把真正的生产链挤掉。实测 BetterQuesting 一家就是 3685 条。
     *
     * <p>
     * 匹配的是处理器<b>标签</b>（{@code 处理器类|配方表}，纯 ASCII），不是显示名 ——
     * 显示名是中文、还会随语言变，标签是稳定的。
     *
     * @param skipped 被跳过的处理器标签会记进来，供日志说明「这次到底剔了什么」
     */
    private static List<ICraftingHandler> filterHandlers(List<ICraftingHandler> handlers, List<String> skipped) {
        String config = Config.webRecipeSkipHandlers;
        if (config == null || config.trim()
            .isEmpty()) {
            return handlers;
        }
        String[] patterns = config.toLowerCase(Locale.ROOT)
            .split(",");
        List<ICraftingHandler> kept = new ArrayList<>(handlers.size());
        for (int i = 0; i < handlers.size(); i++) {
            ICraftingHandler handler = handlers.get(i);
            String tag = handlerTag(handler);
            String lower = tag.toLowerCase(Locale.ROOT);
            boolean hit = false;
            for (int p = 0; p < patterns.length; p++) {
                String pattern = patterns[p].trim();
                if (!pattern.isEmpty() && lower.contains(pattern)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                if (skipped.size() < 20) skipped.add(tag);
            } else {
                kept.add(handler);
            }
        }
        return kept;
    }

    private static ICraftingHandler allRecipesOf(ICraftingHandler handler) {
        if (handler instanceof TemplateRecipeHandler) {
            return ((TemplateRecipeHandler) handler).getAllRecipeHandler();
        }
        return handler.getRecipeHandler("all");
    }

    private static void reportSummary(Snapshot built, List<String> names, int failedHandlers) {
        FutaGtnhMod.LOG.info("网页配方：共 {} 个处理器（{} 个读取失败）、{} 条配方", names.size(), failedHandlers, built.recipeCount);

        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final int[] counts = built.recipesPerHandler;
        Arrays.sort(order, new Comparator<Integer>() {

            @Override
            public int compare(Integer a, Integer b) {
                return Integer.compare(counts[b], counts[a]);
            }
        });
        for (int i = 0; i < Math.min(8, order.length); i++) {
            int slot = order[i];
            FutaGtnhMod.LOG.info("网页配方：  {} -> {} 条", names.get(slot), counts[slot]);
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 产出某个物品的全部配方序号。 */
    public static int[] recipesFor(int itemId) {
        Snapshot local = snapshot;
        if (local == null) return EMPTY;
        int[] found = local.byResult.get(itemId);
        return found == null ? EMPTY : found;
    }

    public static boolean hasRecipes(int itemId) {
        Snapshot local = snapshot;
        return local != null && local.byResult.containsKey(itemId);
    }

    private static final int[] EMPTY = new int[0];

    /** 一条配方的视图，供 JSON 输出和规划器使用。 */
    public static final class RecipeView {

        public int ordinal;
        public String machine = "";
        public String handler = "";
        public int gridW = 1;
        public int gridH = 1;
        public Slot[] inputs = new Slot[0];
        public Slot[] outputs = new Slot[0];
        public Slot[] extras = new Slot[0];
        /** 主产物物品 id；没有主产物的配方（燃料之类）为 -1。 */
        public int resultId = -1;
        public int resultAmount;
        /**
         * GT 机器的耗电与耗时：{@code euPerTick} 是 EU/t（发电配方为负），
         * {@code durationTicks} 是耗时（tick）。不是 GT 配方时都是 0。
         */
        public int euPerTick;
        public int durationTicks;
    }

    /** 一个槽位：格坐标、数量、概率，以及「任意一种都行」的候选物品。 */
    public static final class Slot {

        public int x;
        public int y;
        public int amount = 1;
        public int chance = 10000;
        /** 候选物品 id；单候选时长度为 1。 */
        public int[] alts = EMPTY;
        /** 候选总数（{@link #alts} 可能被截断）。 */
        public int altTotal;
    }

    /**
     * 配方的稳定编号（跨会话、跨索引重建都不变）。
     *
     * <p>
     * 由配方<b>内容</b>算出来：处理器标签 + 耗电耗时 + 每个槽位的物品身份/数量/坐标/概率。
     * 物品身份用稳定键（流体的形如 {@code fluid:显示名}），不用会话内的临时编号，
     * 否则重建一次索引编号又变了 —— 那就等于没修。
     *
     * @return 正数编号；配方不存在时返回 0（0 不作任何配方的编号）
     */
    public static int stableRid(int ordinal) {
        Snapshot local = snapshot;
        if (local == null || ordinal < 0 || ordinal >= local.recipeCount) return 0;
        int[] cache = stableRids;
        if (cache == null || cache.length != local.recipeCount) {
            cache = new int[local.recipeCount];
            stableRids = cache;
        }
        int cached = cache[ordinal];
        if (cached != 0) return cached;
        int rid = computeStableRid(ordinal, local);
        // 偶数会让「0 表示没有」这条判断变脆（哈希撞成 0 的概率虽小，但没必要留着）
        if (rid <= 0) rid = 1;
        cache[ordinal] = rid;
        return rid;
    }

    private static int computeStableRid(int ordinal, Snapshot local) {
        RecipeView view = view(ordinal);
        if (view == null) return 0;

        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, view.handler == null ? 0 : view.handler.hashCode());
        hash = mix(hash, view.euPerTick);
        hash = mix(hash, view.durationTicks);
        hash = mix(hash, view.resultId < 0 ? -1 : stableKeyHash(view.resultId));
        hash = mix(hash, view.resultAmount);
        hash = mixSlots(hash, view.inputs);
        hash = mixSlots(hash, view.outputs);
        hash = mixSlots(hash, view.extras);

        int rid = (int) (hash ^ (hash >>> 32)) & 0x7FFFFFFF;
        return rid;
    }

    private static long mixSlots(long hash, Slot[] slots) {
        if (slots == null) return mix(hash, -7);
        long out = mix(hash, slots.length);
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            out = mix(out, slot.x);
            out = mix(out, slot.y);
            out = mix(out, slot.amount);
            out = mix(out, slot.chance);
            // 候选只取前几个：它们决定「这一格到底是什么东西」，后面的差异极小
            int take = Math.min(slot.alts.length, 4);
            out = mix(out, slot.altTotal * 31 + take);
            for (int a = 0; a < take; a++) {
                out = mix(out, stableKeyHash(slot.alts[a]));
            }
        }
        return out;
    }

    /** 物品稳定键的哈希：会话内的 id 换来换去，键不会。 */
    private static int stableKeyHash(int itemId) {
        String key = WebStore.keyOfId(itemId);
        return key.isEmpty() ? itemId * 31 : key.hashCode();
    }

    private static long mix(long hash, int value) {
        long out = hash ^ (value & 0xFFFFFFFFL);
        return out * 0x100000001b3L;
    }

    public static RecipeView view(int ordinal) {
        Snapshot local = snapshot;
        if (local == null || ordinal < 0 || ordinal >= local.recipeCount) return null;

        RecipeView view = new RecipeView();
        view.ordinal = ordinal;
        int slot = local.handlerSlot[ordinal];
        List<String> names = handlerNames;
        if (slot >= 0 && slot < names.size()) view.machine = names.get(slot);
        List<String> tags = handlerTags;
        if (slot >= 0 && slot < tags.size()) view.handler = tags.get(slot);

        view.inputs = slotsOf(
            local,
            ordinal,
            local.ingOffset,
            local.ingId,
            local.ingAmount,
            local.ingAlt,
            local.ingChance,
            local.ingX,
            local.ingY);
        view.outputs = slotsOf(
            local,
            ordinal,
            local.resOffset,
            local.resId,
            local.resAmount,
            local.resAlt,
            local.resChance,
            local.resX,
            local.resY);
        view.extras = slotsOf(
            local,
            ordinal,
            local.extOffset,
            local.extId,
            local.extAmount,
            local.extAlt,
            local.extChance,
            local.extX,
            local.extY);
        view.resultId = local.primaryResult[ordinal];
        view.euPerTick = ordinal < local.euPerTick.length ? local.euPerTick[ordinal] : 0;
        view.durationTicks = ordinal < local.durationTicks.length ? local.durationTicks[ordinal] : 0;
        view.resultAmount = local.primaryAmount[ordinal];

        // 网格原点取所有槽位里最靠左上的那个，给界面一份紧凑的 0 基坐标。
        // NEI 的坐标是 GUI 像素、一个槽 18 像素，直接除以 18 就是格子。
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        Slot[][] groups = { view.inputs, view.outputs, view.extras };
        for (int g = 0; g < groups.length; g++) {
            for (int i = 0; i < groups[g].length; i++) {
                Slot cell = groups[g][i];
                if (cell.x < minX) minX = cell.x;
                if (cell.y < minY) minY = cell.y;
                if (cell.x > maxX) maxX = cell.x;
                if (cell.y > maxY) maxY = cell.y;
            }
        }
        if (minX == Integer.MAX_VALUE) return view;

        for (int g = 0; g < groups.length; g++) {
            for (int i = 0; i < groups[g].length; i++) {
                Slot cell = groups[g][i];
                cell.x -= minX;
                cell.y -= minY;
            }
        }
        view.gridW = Math.min(12, maxX - minX + 1);
        view.gridH = Math.min(12, maxY - minY + 1);
        return view;
    }

    private static Slot[] slotsOf(Snapshot local, int ordinal, int[] offsets, int[] ids, int[] amounts, int[] alts,
        int[] chances, short[] xs, short[] ys) {
        int from = offsets[ordinal];
        int to = offsets[ordinal + 1];
        if (to <= from) return new Slot[0];

        Slot[] out = new Slot[to - from];
        for (int i = from; i < to; i++) {
            Slot cell = new Slot();
            // ★ 必须用 floorDiv，不能写 / 18。
            //
            // NEI 的槽位坐标里**负数是常态**（配方网格的原点在 GUI 左上角之外，
            // GT 的组装机布局第一行就是 rely = -4）。而 Java 的整数除法对负数是
            // 向零截断：-4 / 18 == 0，于是 y=-4 那一行和 y=14 那一行被折进同一格，
            // 后写的槽把先写的覆盖掉 —— 表现就是「配方 7 个材料，页面上只显示 3 个」。
            // floorDiv(-4, 18) == -1，才是「往上数一格」的正确含义。
            cell.x = Math.floorDiv(xs[i], 18);
            cell.y = Math.floorDiv(ys[i], 18);
            cell.amount = amounts[i] <= 0 ? 1 : amounts[i];
            cell.chance = chances[i];
            // 候选表给全量：规划时要拿它去统计「这些替代品加起来我总共有多少」，
            // 截断只发生在输出 JSON 的时候（见 WebRecipeServer）
            int[] candidates = alts[i] < 0 ? null : local.altSets.get(alts[i]);
            if (candidates == null || candidates.length == 0) {
                candidates = new int[] { ids[i] };
            }
            cell.altTotal = candidates.length;
            cell.alts = candidates;
            out[i - from] = cell;
        }
        return out;
    }

    // ==================================================================
    // 列式存储
    // ==================================================================

    private static final class Snapshot {

        int recipeCount;
        int[] handlerSlot = EMPTY;
        int[] handlerIndex = EMPTY;
        int[] primaryResult = EMPTY;
        int[] primaryAmount = EMPTY;
        /** 每一条配方的 EU/t 与耗时（tick）；0 表示不是 GT 配方。 */
        int[] euPerTick = EMPTY;
        int[] durationTicks = EMPTY;
        int[] recipesPerHandler = EMPTY;

        int[] resOffset = new int[] { 0 };
        int[] resId = EMPTY;
        int[] resAmount = EMPTY;
        int[] resAlt = EMPTY;
        int[] resChance = EMPTY;
        short[] resX = new short[0];
        short[] resY = new short[0];

        int[] ingOffset = new int[] { 0 };
        int[] ingId = EMPTY;
        int[] ingAmount = EMPTY;
        int[] ingAlt = EMPTY;
        int[] ingChance = EMPTY;
        short[] ingX = new short[0];
        short[] ingY = new short[0];

        int[] extOffset = new int[] { 0 };
        int[] extId = EMPTY;
        int[] extAmount = EMPTY;
        int[] extAlt = EMPTY;
        int[] extChance = EMPTY;
        short[] extX = new short[0];
        short[] extY = new short[0];

        List<int[]> altSets = Collections.emptyList();
        Map<Integer, int[]> byResult = Collections.emptyMap();

        static int[] recipesPerHandler(int recipeCount, int[] handlerSlot, int handlerTotal) {
            int[] out = new int[handlerTotal];
            for (int i = 0; i < recipeCount; i++) {
                int slot = handlerSlot[i];
                if (slot >= 0 && slot < handlerTotal) out[slot]++;
            }
            return out;
        }
    }

    /** 一组槽位列（材料 / 产出 / 其它）。 */
    private static final class SlotColumns {

        int[] id = new int[256];
        int[] amount = new int[256];
        int[] alt = new int[256];
        int[] chance = new int[256];
        short[] x = new short[256];
        short[] y = new short[256];
        int size;

        void add(int idValue, int amountValue, int altValue, int chanceValue, int xValue, int yValue) {
            if (size >= id.length) {
                int capacity = id.length * 2;
                id = Arrays.copyOf(id, capacity);
                amount = Arrays.copyOf(amount, capacity);
                alt = Arrays.copyOf(alt, capacity);
                chance = Arrays.copyOf(chance, capacity);
                x = Arrays.copyOf(x, capacity);
                y = Arrays.copyOf(y, capacity);
            }
            id[size] = idValue;
            amount[size] = amountValue;
            alt[size] = altValue;
            chance[size] = chanceValue;
            x[size] = (short) xValue;
            y[size] = (short) yValue;
            size++;
        }
    }

    /** 增长式列构建器。 */
    private static final class Builder {

        int[] handlerSlot = new int[4096];
        int[] handlerIndex = new int[4096];
        int[] primaryResult = new int[4096];
        int[] primaryAmount = new int[4096];
        int[] euPerTick = new int[4096];
        int[] durationTicks = new int[4096];
        int[] resOffset = new int[4097];
        int[] ingOffset = new int[4097];
        int[] extOffset = new int[4097];
        int recipeCount;

        final SlotColumns res = new SlotColumns();
        final SlotColumns ing = new SlotColumns();
        final SlotColumns ext = new SlotColumns();

        final Map<String, Integer> altIndex = new HashMap<>();
        final List<int[]> altSets = new ArrayList<>();
        final Map<Integer, IntList> byResult = new HashMap<>();

        final int handlerTotal;

        Builder(int handlerTotal) {
            this.handlerTotal = handlerTotal;
        }

        private void ensureRecipe() {
            if (recipeCount >= handlerSlot.length) {
                int capacity = handlerSlot.length * 2;
                handlerSlot = Arrays.copyOf(handlerSlot, capacity);
                handlerIndex = Arrays.copyOf(handlerIndex, capacity);
                primaryResult = Arrays.copyOf(primaryResult, capacity);
                primaryAmount = Arrays.copyOf(primaryAmount, capacity);
                euPerTick = Arrays.copyOf(euPerTick, capacity);
                durationTicks = Arrays.copyOf(durationTicks, capacity);
                resOffset = Arrays.copyOf(resOffset, capacity + 1);
                ingOffset = Arrays.copyOf(ingOffset, capacity + 1);
                extOffset = Arrays.copyOf(extOffset, capacity + 1);
            }
        }

        void addRecipe(ICraftingHandler handler, int slot, int index) {
            List<PositionedStack> ingredients;
            List<PositionedStack> others;
            PositionedStack result;
            try {
                ingredients = handler.getIngredientStacks(index);
                others = handler.getOtherStacks(index);
                result = handler.getResultStack(index);
            } catch (Throwable t) {
                // 个别处理器有脏配方（数组越界之类），跳过它，别影响整轮构建
                return;
            }

            ensureRecipe();

            List<int[]> outputs = new ArrayList<>(2);
            List<int[]> inputs = new ArrayList<>(8);
            List<int[]> extras = new ArrayList<>(2);

            int resultId = -1;
            int resultAmount = 0;
            if (result != null) {
                collectOne(result, outputs);
                ItemStack main = primaryOf(result);
                if (main != null) {
                    resultId = WebStore.idOf(main);
                    resultAmount = amountOf(main, resultId);
                }
            } else if (others != null) {
                // ★ NEI 的约定：没有 resultStack 时，otherStacks 就是这条配方的产出。
                //
                // 踩过一次，症状是「NEI 里明明有组装机配方，页面上就是没有」：
                // GT 的处理器在某些配方上 getResultStack() 返回 null（内部对
                // ArrayIndexOutOfBounds 是吞掉的，比如 mOutputs 为空的那些），
                // NEI 于是把这些「其它槽位」当结果显示出来 —— 藻类农场的组装机配方
                // 正是这种形态。当初这里只把它们记进 outputs 却不建反向索引，
                // 结果整条配方在页面上等于不存在。
                for (int i = 0; i < others.size(); i++) collectOne(others.get(i), outputs);
                others = Collections.emptyList();
                if (!outputs.isEmpty()) {
                    resultId = outputs.get(0)[0];
                    resultAmount = outputs.get(0)[1];
                }
            }
            if (ingredients != null) {
                for (int i = 0; i < ingredients.size(); i++) collectOne(ingredients.get(i), inputs);
            }
            if (others != null) {
                for (int i = 0; i < others.size(); i++) collectOne(others.get(i), extras);
            }

            handlerSlot[recipeCount] = slot;
            handlerIndex[recipeCount] = index;
            primaryResult[recipeCount] = resultId;
            primaryAmount[recipeCount] = resultAmount <= 0 ? 1 : resultAmount;

            // GT 机器的配方带电压和耗时：玩家排产时要看「这台机器吃多少电、要多久」，
            // 光有材料表排不出顺序。非 GT 配方这里是 0，界面就不显示这一段。
            int[] power = gtPower(handler, index);
            if (power != null) {
                euPerTick[recipeCount] = power[0];
                durationTicks[recipeCount] = power[1];
            }

            resOffset[recipeCount] = res.size;
            ingOffset[recipeCount] = ing.size;
            extOffset[recipeCount] = ext.size;
            writeSlots(outputs, res);
            writeSlots(inputs, ing);
            writeSlots(extras, ext);

            recipeCount++;
            resOffset[recipeCount] = res.size;
            ingOffset[recipeCount] = ing.size;
            extOffset[recipeCount] = ext.size;

            // 一条配方可能有好几个产出（多方块控制器那种），每个都要能反查得到 ——
            // 玩家搜的是「哪个东西」，不是「哪条主产物」。
            int ordinal = recipeCount - 1;
            for (int i = 0; i < outputs.size(); i++) {
                int outputId = outputs.get(i)[0];
                if (outputId < 0 || indexResulted.contains(outputId)) continue;
                indexResulted.add(outputId);
                IntList list = byResult.get(outputId);
                if (list == null) {
                    list = new IntList();
                    byResult.put(outputId, list);
                }
                list.add(ordinal);
            }
            indexResulted.clear();
        }

        /** 当前这条配方已经索引过的产物（防止同一产物重复进反向表）。 */
        private final java.util.HashSet<Integer> indexResulted = new java.util.HashSet<>();

        private void writeSlots(List<int[]> slots, SlotColumns columns) {
            for (int i = 0; i < slots.size(); i++) {
                int[] cell = slots.get(i);
                columns.add(cell[0], cell[1], cell[2], cell[3], cell[4], cell[5]);
            }
        }

        /**
         * 把一个 {@link PositionedStack} 压成 {@code int[6]}：
         * {@code {主物品 id, 数量, 候选表下标, 概率, x, y}}。
         */
        private void collectOne(PositionedStack positioned, List<int[]> out) {
            if (positioned == null) return;

            ItemStack main = primaryOf(positioned);
            if (main == null) return;
            int mainId = WebStore.idOf(main);
            if (mainId < 0) return;

            logFluidSlotOnce(main, mainId);

            int[] candidates = permutationIds(positioned.items, mainId);
            out.add(
                new int[] { mainId, amountOf(main, mainId), internAlts(candidates), positioned.getChance(),
                    positioned.relx, positioned.rely });
        }

        private int[] permutationIds(ItemStack[] items, int mainId) {
            if (items == null || items.length == 0) return new int[] { mainId };

            int[] ids = new int[items.length];
            int count = 0;
            for (int i = 0; i < items.length; i++) {
                ItemStack stack = items[i];
                if (stack == null || stack.getItem() == null) continue;
                int id = WebStore.idOf(stack);
                if (id < 0) continue;
                boolean duplicate = false;
                for (int k = 0; k < count; k++) {
                    if (ids[k] == id) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) ids[count++] = id;
            }
            if (count == 0) return new int[] { mainId };

            int[] result = Arrays.copyOf(ids, count);
            if (result[0] != mainId) {
                // 主物品排到最前面：界面默认显示它，规划也按它展开
                for (int i = 1; i < result.length; i++) {
                    if (result[i] == mainId) {
                        result[i] = result[0];
                        result[0] = mainId;
                        break;
                    }
                }
            }
            return result;
        }

        private int internAlts(int[] candidates) {
            // 单候选是最常见的情况，没必要进池子，用 -1 表示「就是主物品自己」
            if (candidates.length <= 1) return -1;

            StringBuilder builder = new StringBuilder(candidates.length * 8);
            for (int i = 0; i < candidates.length; i++) {
                builder.append(candidates[i])
                    .append(',');
            }
            String key = builder.toString();
            Integer existing = altIndex.get(key);
            if (existing != null) return existing;

            int index = altSets.size();
            altSets.add(candidates);
            altIndex.put(key, index);
            return index;
        }

        Snapshot build() {
            Snapshot out = new Snapshot();
            out.recipeCount = recipeCount;
            out.handlerSlot = Arrays.copyOf(handlerSlot, recipeCount);
            out.handlerIndex = Arrays.copyOf(handlerIndex, recipeCount);
            out.primaryResult = Arrays.copyOf(primaryResult, recipeCount);
            out.primaryAmount = Arrays.copyOf(primaryAmount, recipeCount);
            out.euPerTick = Arrays.copyOf(euPerTick, recipeCount);
            out.durationTicks = Arrays.copyOf(durationTicks, recipeCount);
            out.recipesPerHandler = Snapshot.recipesPerHandler(recipeCount, out.handlerSlot, handlerTotal);

            out.resOffset = Arrays.copyOf(resOffset, recipeCount + 1);
            out.resId = Arrays.copyOf(res.id, res.size);
            out.resAmount = Arrays.copyOf(res.amount, res.size);
            out.resAlt = Arrays.copyOf(res.alt, res.size);
            out.resChance = Arrays.copyOf(res.chance, res.size);
            out.resX = Arrays.copyOf(res.x, res.size);
            out.resY = Arrays.copyOf(res.y, res.size);

            out.ingOffset = Arrays.copyOf(ingOffset, recipeCount + 1);
            out.ingId = Arrays.copyOf(ing.id, ing.size);
            out.ingAmount = Arrays.copyOf(ing.amount, ing.size);
            out.ingAlt = Arrays.copyOf(ing.alt, ing.size);
            out.ingChance = Arrays.copyOf(ing.chance, ing.size);
            out.ingX = Arrays.copyOf(ing.x, ing.size);
            out.ingY = Arrays.copyOf(ing.y, ing.size);

            out.extOffset = Arrays.copyOf(extOffset, recipeCount + 1);
            out.extId = Arrays.copyOf(ext.id, ext.size);
            out.extAmount = Arrays.copyOf(ext.amount, ext.size);
            out.extAlt = Arrays.copyOf(ext.alt, ext.size);
            out.extChance = Arrays.copyOf(ext.chance, ext.size);
            out.extX = Arrays.copyOf(ext.x, ext.size);
            out.extY = Arrays.copyOf(ext.y, ext.size);

            out.altSets = new ArrayList<>(altSets);

            Map<Integer, int[]> result = new HashMap<>(byResult.size() * 2);
            for (Map.Entry<Integer, IntList> entry : byResult.entrySet()) {
                result.put(
                    entry.getKey(),
                    entry.getValue()
                        .toArray());
            }
            out.byResult = result;
            return out;
        }
    }

    /** 一个只会增长的 int 列表（构建期用，避免每条配方都装箱）。 */
    private static final class IntList {

        int[] data = new int[4];
        int size;

        void add(int value) {
            if (size >= data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static ItemStack primaryOf(PositionedStack positioned) {
        if (positioned.item != null && positioned.item.getItem() != null) return positioned.item;
        ItemStack[] items = positioned.items;
        if (items == null) return null;
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && items[i].getItem() != null) return items[i];
        }
        return null;
    }

    /**
     * 这一格「一次要用多少」。
     *
     * <p>
     * <b>流体要单独看</b>：配方里的流体是以伪物品出现的（NEI/GT 给每种流体造一个显示用
     * ItemStack），它的 stackSize 是 1，真正的用量写在<b>流体 NBT</b> 里 ——
     * 实测「Molten Borosilicate Glass」那格每次要 144 L，而这里原来一律读 stackSize，
     * 于是界面上写「每个配方 ×1，共需 64」：数字全都差了一个 144 倍。
     */
    /**
     * 把流体槽的<b>原始身份</b>打一次：注册名 / damage / NBT / 解析到的 id。
     *
     * <p>
     * 「镍铬合金线圈的流体应该是熔融坎塔尔合金，页面却显示烯丙基氯」这类问题，
     * 光看 id 是查不出来的 —— 得看那一格原始栈到底长什么样、以及它被解析成了谁。
     */
    private static void logFluidSlotOnce(ItemStack stack, int resolvedId) {
        if (!WebStore.isFluidItem(resolvedId)) return;
        String key;
        try {
            key = WebStore.keyOf(stack);
        } catch (Throwable t) {
            key = "?";
        }
        synchronized (fluidSlotLogged) {
            if (fluidSlotLogged.size() >= 30 || !fluidSlotLogged.add(key)) return;
        }
        String nbt = stack.hasTagCompound() ? String.valueOf(stack.getTagCompound()) : "无";
        if (nbt.length() > 200) nbt = nbt.substring(0, 200) + "…";
        com.futa_gtnh.FutaGtnhMod.LOG.info(
            "网页配方：流体槽身份 键={} 解析到 id={} 名字={} NBT={}",
            key,
            Integer.valueOf(resolvedId),
            WebStore.nameOf(resolvedId),
            nbt);
    }

    private static final java.util.Set<String> fluidSlotLogged = new java.util.HashSet<>();

    private static int amountOf(ItemStack stack, int itemId) {
        int fluidAmount = fluidAmountOf(stack, itemId);
        if (fluidAmount > 0) return fluidAmount;
        return stack.stackSize <= 0 ? 1 : stack.stackSize;
    }

    /**
     * 如果这是「流体显示物品」，返回它带着的流体量（mB）；不是流体就返回 0。
     *
     * <p>
     * 用 Forge 的标准读法解析 NBT —— NEI 的流体显示物品、GT 的流体单元，存的都是
     * 一个 {@code FluidStack} 的 NBT，所以这一条能覆盖两边的实现。
     */
    private static int fluidAmountOf(ItemStack stack, int itemId) {
        if (stack == null || stack.getItem() == null) return 0;
        // ★ 这里**不能**用 isFluidItem 当闸：它的名字判据依赖库存快照，
        // 而索引重建时那份快照往往还没到 —— 闸一关，用量就永远读不出来（连着诊断一起哑掉）。
        // 只读 NBT 不看库存，读不出来时才去问「这算不算流体」来决定要不要记日志。
        // GTNH 自己的键最优先：实测 {mFluidMaterialName:..., mFluidDisplayAmount:144L}，
        // Forge 的标准解析认不出它（这正是「每次 144 L 被当成 1」的原因）
        long display = WebStore.fluidDisplayAmount(stack);
        if (display > 0) return (int) Math.min(Integer.MAX_VALUE, display);

        if (!stack.hasTagCompound()) {
            if (WebStore.isFluidItem(itemId)) logFluidTagOnce(stack, "无 NBT");
            return 0;
        }
        try {
            net.minecraftforge.fluids.FluidStack fluid = net.minecraftforge.fluids.FluidStack
                .loadFluidStackFromNBT(stack.getTagCompound());
            if (fluid != null && fluid.amount > 0) return fluid.amount;
            if (WebStore.isFluidItem(itemId)) {
                logFluidTagOnce(stack, "FluidStack 解析不出量：" + stack.getTagCompound());
            }
            return 0;
        } catch (Throwable t) {
            logFluidTagOnce(stack, "解析抛异常：" + t);
        }
        return 0;
    }

    /** 把「流体伪物品用了量、但读不出来」的那种 NBT 打一次，照着实测结构改解析。 */
    private static final java.util.Set<String> fluidTagLogged = new java.util.HashSet<>();

    private static void logFluidTagOnce(ItemStack stack, String detail) {
        String name;
        try {
            name = String.valueOf(stack.getDisplayName());
        } catch (Throwable t) {
            name = "?";
        }
        synchronized (fluidTagLogged) {
            if (fluidTagLogged.size() >= 20 || !fluidTagLogged.add(name)) return;
        }
        String text = detail;
        if (text.length() > 300) text = text.substring(0, 300) + "…";
        com.futa_gtnh.FutaGtnhMod.LOG.info("网页配方：流体的用量读不出来 —— 物品「{}」，NBT 实况：{}", name, text);
    }

    // ==================================================================
    // 磁盘缓存
    //
    // 格式（GZIP 包一层 DataOutputStream）：
    // magic, version, fingerprint, 处理器名表,
    // 物品键表（注册名@meta）, 配方列..., 候选表
    // 物品 id 是本次会话的目录序号、跨会话不稳定，所以存键再重建映射。
    // ==================================================================

    private static boolean loadFromDisk() {
        File file = cacheFile;
        if (file == null || !file.isFile() || snapshot != null) return false;

        phase = "正在读取配方缓存";
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file)), 1 << 16));
            if (in.readInt() != MAGIC) return false;
            if (in.readInt() != FILE_VERSION) return false;
            if (!in.readUTF()
                .equals(fingerprint)) {
                FutaGtnhMod.LOG.info("网页配方：模组列表变了，丢弃旧的配方缓存");
                return false;
            }

            // 处理器名表跟着缓存一起存：这份索引是自描述的，不依赖「此刻 NEI 注册了哪些处理器」。
            // 否则在标题界面读缓存（那时只有十几个原版处理器）就没法把「组装机」这种机器名显示出来。
            int nameCount = in.readInt();
            List<String> names = new ArrayList<>(nameCount);
            List<String> tags = new ArrayList<>(nameCount);
            List<String> icons = new ArrayList<>(nameCount);
            for (int i = 0; i < nameCount; i++) {
                names.add(in.readUTF());
                tags.add(in.readUTF());
                icons.add(in.readUTF());
            }
            handlerNames = names;
            handlerTags = tags;
            handlerIcons = icons;
            handlersTotal = nameCount;

            int itemCount = in.readInt();
            String[] stackKeys = new String[itemCount];
            String[] identityKeys = new String[itemCount];
            for (int i = 0; i < itemCount; i++) {
                stackKeys[i] = in.readUTF();
                identityKeys[i] = in.readUTF();
            }
            int[] remap = new int[itemCount];
            int unreadable = 0;
            for (int i = 0; i < itemCount; i++) {
                ItemStack stack = stackFromKey(stackKeys[i]);
                // 按存下来的身份登记，不重新算键：身份键里含显示名，而显示名不能在索引线程上问
                remap[i] = stack == null ? -1 : WebStore.adopt(stack, identityKeys[i]);
                if (remap[i] < 0) unreadable++;
            }
            // ★ 找不回来的条目要说话。以前这里一声不响：流体身份键反解不出物品，
            // 整批流体被丢掉，玩家看到的是「配方没了」，日志里却什么都没有。
            if (unreadable > 0) {
                FutaGtnhMod.LOG.info("网页配方：缓存里有 {} 个条目在当前客户端上找不到（已跳过，其余照常）", Integer.valueOf(unreadable));
            }

            Snapshot out = new Snapshot();
            out.recipeCount = in.readInt();
            out.handlerSlot = readInts(in);
            out.handlerIndex = readInts(in);
            out.primaryResult = readInts(in);
            out.primaryAmount = readInts(in);
            out.euPerTick = readInts(in);
            out.durationTicks = readInts(in);
            out.recipesPerHandler = Snapshot.recipesPerHandler(out.recipeCount, out.handlerSlot, nameCount);

            out.resOffset = readInts(in);
            out.resId = readInts(in);
            out.resAmount = readInts(in);
            out.resAlt = readInts(in);
            out.resChance = readInts(in);
            out.resX = readShorts(in);
            out.resY = readShorts(in);

            out.ingOffset = readInts(in);
            out.ingId = readInts(in);
            out.ingAmount = readInts(in);
            out.ingAlt = readInts(in);
            out.ingChance = readInts(in);
            out.ingX = readShorts(in);
            out.ingY = readShorts(in);

            out.extOffset = readInts(in);
            out.extId = readInts(in);
            out.extAmount = readInts(in);
            out.extAlt = readInts(in);
            out.extChance = readInts(in);
            out.extX = readShorts(in);
            out.extY = readShorts(in);

            int altCount = in.readInt();
            List<int[]> altSets = new ArrayList<>(altCount);
            for (int i = 0; i < altCount; i++) altSets.add(readInts(in));
            out.altSets = altSets;

            remapIds(out.resId, remap);
            remapIds(out.ingId, remap);
            remapIds(out.extId, remap);
            remapIds(out.primaryResult, remap);
            remapAlts(altSets, remap);

            Map<Integer, IntList> grouped = new HashMap<>();
            for (int ordinal = 0; ordinal < out.recipeCount; ordinal++) {
                int itemId = out.primaryResult[ordinal];
                if (itemId < 0) continue;
                IntList list = grouped.get(itemId);
                if (list == null) {
                    list = new IntList();
                    grouped.put(itemId, list);
                }
                list.add(ordinal);
            }
            Map<Integer, int[]> byResult = new HashMap<>(grouped.size() * 2);
            for (Map.Entry<Integer, IntList> entry : grouped.entrySet()) {
                byResult.put(
                    entry.getKey(),
                    entry.getValue()
                        .toArray());
            }
            out.byResult = byResult;

            snapshot = out;
            stableRids = null;
            ready = true;
            phase = "就绪";
            return true;
        } catch (Throwable t) {
            // 常见原因是上次写到一半就被杀掉（关游戏/崩溃），留下的文件是截断的；
            // 重建一次就好，所以这里只是提示，不算故障
            FutaGtnhMod.LOG.warn("网页配方：缓存文件读不出来（多半是上次没写完），改为重新建立索引", t);
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 关不上也无所谓，文件已经不需要了
                }
            }
        }
    }

    private static void remapIds(int[] ids, int[] remap) {
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            ids[i] = id >= 0 && id < remap.length ? remap[id] : -1;
        }
    }

    private static void remapAlts(List<int[]> altSets, int[] remap) {
        for (int i = 0; i < altSets.size(); i++) {
            int[] set = altSets.get(i);
            int count = 0;
            for (int k = 0; k < set.length; k++) {
                int id = set[k];
                int mapped = id >= 0 && id < remap.length ? remap[id] : -1;
                if (mapped >= 0) set[count++] = mapped;
            }
            if (count == 0) {
                altSets.set(i, EMPTY);
            } else if (count != set.length) {
                altSets.set(i, Arrays.copyOf(set, count));
            }
        }
    }

    private static int[] readInts(DataInputStream in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0 || length > (1 << 26)) throw new java.io.IOException("数组长度异常: " + length);
        int[] out = new int[length];
        for (int i = 0; i < length; i++) out[i] = in.readInt();
        return out;
    }

    private static short[] readShorts(DataInputStream in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0 || length > (1 << 26)) throw new java.io.IOException("数组长度异常: " + length);
        short[] out = new short[length];
        for (int i = 0; i < length; i++) out[i] = in.readShort();
        return out;
    }

    private static ItemStack stackFromKey(String key) {
        int at = key.lastIndexOf('@');
        if (at <= 0) return null;
        String name = key.substring(0, at);
        int meta;
        try {
            meta = Integer.parseInt(key.substring(at + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        Object item = Item.itemRegistry.getObject(name);
        if (!(item instanceof Item)) return null;
        return new ItemStack((Item) item, 1, meta);
    }

    private static void saveToDisk() {
        File file = cacheFile;
        Snapshot local = snapshot;
        if (file == null || local == null) return;

        // ★ 别用「更残缺的」覆盖「更完整的」。
        //
        // 典型场景：玩家这次停在标题界面（NEI 只注册了 15 个处理器），而磁盘上那份缓存
        // 是上次进世界时用 226 个处理器建的。让它把缓存覆盖掉，下次进世界之前就只剩
        // 原版配方了 —— 而那份完整缓存本来是可以直接在标题界面用的。
        int existing = cachedHandlerCount(file);
        if (existing > handlerNames.size()) {
            FutaGtnhMod.LOG.info("网页配方：磁盘上已有更完整的缓存（{} 个处理器 > 本次 {} 个），保留它不覆盖", existing, handlerNames.size());
            return;
        }

        phase = "正在保存配方缓存";
        DataOutputStream out = null;
        // 先写临时文件再整体换名：这份缓存是十几兆，写到一半被杀掉（关游戏、崩溃）时，
        // 直接写目标文件会留下一个截断的坏文件 —— 下次启动读它就报 Not in GZIP format，
        // 白等一轮重建。换成「写 tmp → 原子替换」之后，中途死掉的只是 tmp。
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                FutaGtnhMod.LOG.warn("网页配方：创建缓存目录失败，本次不落盘（功能不受影响）");
                return;
            }
            // ★ 必须和读取端对称：读的是 GZIPInputStream，写就得是 GZIPOutputStream。
            // 这里漏过一次，后果是缓存文件头直接是 MAGIC 原文、读取端每次都报
            // 「Not in GZIP format」然后重建 —— 功能看着正常（反正会重建），
            // 但那份缓存等于从来没生效过，而且白占十几兆磁盘（不压缩的体积）。
            out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temp), 1 << 16), 1 << 16));
            out.writeInt(MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeUTF(fingerprint);

            List<String> names = handlerNames;
            List<String> tags = handlerTags;
            List<String> icons = handlerIcons;
            out.writeInt(names.size());
            for (int i = 0; i < names.size(); i++) {
                out.writeUTF(names.get(i));
                out.writeUTF(i < tags.size() ? tags.get(i) : "");
                // 配方类别的代表图标（物品键）；没有就是空串
                out.writeUTF(i < icons.size() ? icons.get(i) : "");
            }

            int itemTotal = WebStore.size();
            out.writeInt(itemTotal);
            for (int i = 0; i < itemTotal; i++) {
                ItemStack stack = WebStore.rawStackOf(i);
                // 两份键都要存：
                // registryKey（注册名@meta）用来把条目找回来，任何线程都能算；
                // keyOfId 是身份键，流体的是「fluid:显示名」，反推不出是哪个物品。
                // v12 只存了身份键，于是回读时所有流体条目都反解失败被丢掉 ——
                // 表现是「重启之后流体全都没有配方了」，而且日志里一个字都没有。
                out.writeUTF(stack == null ? "?" : WebStore.registryKey(stack));
                out.writeUTF(WebStore.keyOfId(i));
            }

            out.writeInt(local.recipeCount);
            writeInts(out, local.handlerSlot);
            writeInts(out, local.handlerIndex);
            writeInts(out, local.primaryResult);
            writeInts(out, local.primaryAmount);
            writeInts(out, local.euPerTick);
            writeInts(out, local.durationTicks);

            writeInts(out, local.resOffset);
            writeInts(out, local.resId);
            writeInts(out, local.resAmount);
            writeInts(out, local.resAlt);
            writeInts(out, local.resChance);
            writeShorts(out, local.resX);
            writeShorts(out, local.resY);

            writeInts(out, local.ingOffset);
            writeInts(out, local.ingId);
            writeInts(out, local.ingAmount);
            writeInts(out, local.ingAlt);
            writeInts(out, local.ingChance);
            writeShorts(out, local.ingX);
            writeShorts(out, local.ingY);

            writeInts(out, local.extOffset);
            writeInts(out, local.extId);
            writeInts(out, local.extAmount);
            writeInts(out, local.extAlt);
            writeInts(out, local.extChance);
            writeShorts(out, local.extX);
            writeShorts(out, local.extY);

            out.writeInt(local.altSets.size());
            for (int i = 0; i < local.altSets.size(); i++) {
                writeInts(out, local.altSets.get(i));
            }
            out.flush();
            out.close();
            out = null;
            replaceFile(temp, file);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：保存配方缓存失败（下次启动会重建，功能不受影响）", t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }
    }

    /**
     * 只读已有缓存的文件头，问它「里面那份索引用了多少个处理器」。
     *
     * <p>
     * 只读到处理器数量就停（几个字节），不解析后面的表 —— 这里只是用来决定
     * 「要不要拿这次的索引去覆盖它」，见 {@link #saveToDisk()}。
     *
     * @return 处理器数量；文件不可用、格式对不上、或模组列表不符时返回 -1
     */
    private static int cachedHandlerCount(File file) {
        if (file == null || !file.isFile()) return -1;
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(file)), 4096))) {
            if (in.readInt() != MAGIC) return -1;
            if (in.readInt() != FILE_VERSION) return -1;
            if (!in.readUTF()
                .equals(fingerprint)) return -1;
            return in.readInt();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 把临时文件换成正式文件；不支持原子移动的文件系统上退回普通替换。 */
    private static void replaceFile(File temp, File target) throws java.io.IOException {
        java.nio.file.Path from = temp.toPath();
        java.nio.file.Path to = target.toPath();
        try {
            java.nio.file.Files.move(
                from,
                to,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeInts(DataOutputStream out, int[] data) throws java.io.IOException {
        out.writeInt(data.length);
        for (int i = 0; i < data.length; i++) out.writeInt(data[i]);
    }

    private static void writeShorts(DataOutputStream out, short[] data) throws java.io.IOException {
        out.writeInt(data.length);
        for (int i = 0; i < data.length; i++) out.writeShort(data[i]);
    }
}
