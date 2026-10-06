package com.futa_gtnh.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合成规划器：给定「要 N 个某某」，算出「先做什么、再做什做、最后做什么」。
 *
 * <p>
 * 输入是 {@link WebRecipeIndex} 那张拍平的配方表，输出是一份纯数据的结果
 * （原材料清单 + 有序步骤），不碰游戏状态，所以可以随便跑、随便缓存。
 *
 * <p>
 * <b>算法</b>：从目标物品出发做「按需展开」。每个物品先挑一条配方
 * （玩家在手机上选过就用他选的，否则按一个启发式打分挑），然后按
 * {@code 需要数量 / 一次产出} 向上取整得到「这条配方要做几次」，
 * 再把它的材料推进队列。关键点是<b>按增量展开</b>：同一条配方可能被
 * 后面更大的需求量再次推到，那时只补做「多出来的那几次」，
 * 已经推过的材料不会重复累加 —— 否则一个菱形依赖（两个中间产物都要同一种钢板）
 * 就会被算两遍。
 *
 * <p>
 * <b>三件必须处理的事</b>：
 * <ol>
 * <li><b>环路</b>。GTNH 里有大量「反过来也能做」的配方（锭 ↔ 粉、板 ↔ 块）。
 * 纯递归会无限展开，所以每个节点都带着一条祖先链，材料出现在自己的祖先里就
 * 停止展开、当作原材料 —— 语义上也正是玩家想要的（「这个得自己备」）。</li>
 * <li><b>库存抵扣</b>。共享背包 + 玩家背包里已有的量会从每一层扣掉：
 * 中间产物够就不用做那么多次，原材料够就不缺。这是「还缺多少」的来源。</li>
 * <li><b>爆炸</b>。层数、步骤数、节点数都有上限，超了就停下并给一条警告，
 * 而不是把手机卡死在一棵几十万节点的树上。</li>
 * </ol>
 *
 * <p>
 * <b>产物数量</b>按配方的「一次产出」算，副产物（概率产出的第二第三个物品）
 * 不进树，只在步骤里显示概率角标：把它们算进需求会让「还缺多少」变得无法解释。
 */
public final class WebPlanner {

    private WebPlanner() {}

    /** 展开层数上限。GTNH 的深层配方（芯片 → 电路 → 机器）大概 10 层上下。 */
    private static final int DEFAULT_MAX_DEPTH = 20;

    /** 最多几步合成。 */
    private static final int DEFAULT_MAX_STEPS = 600;

    /** 内部展开的节点总数上限（防止需求爆炸时算到天荒地老）。 */
    private static final int MAX_VISITS = 20000;

    /** 单个数量上限，防止 {@code long} 溢出成一堆负数。 */
    private static final long MAX_AMOUNT = 1_000_000_000L;

    /** 挑配方时最多看多少个候选（有的物品在 GT 里有上千条配方）。 */
    private static final int MAX_CANDIDATES_SCORED = 32;

    /** 一次掐一个，最多重来几次来拆掉「互相依赖」的配方环。 */
    private static final int MAX_CYCLE_BREAKS = 12;

    /**
     * 一次一个掐不完时，最多再来几轮「把环上的全钉成原材料」。
     *
     * <p>
     * 每一轮都要重新展开一遍（配方会变），所以这个数字直接乘在规划耗时上。
     * 五轮封顶：实测最坏情况下一轮展开只要几十毫秒，而这个上限换来的是一份
     * 不会退化成「1 步 + 一百多件自己准备」的计划。
     */
    private static final int MAX_CYCLE_ROUNDS = 4;

    // ==================================================================
    // 输入 / 输出
    // ==================================================================

    public static final class Request {

        public int itemId;
        public long count = 1L;
        public boolean useStock = true;

        /**
         * 目标产物<b>不扣库存</b>，中间产物照旧扣。
         *
         * <p>
         * 「我要 64 个 A，仓库里已经有 2 个」——玩家要的往往是<b>再做 64 个</b>
         * （补货、交付、给别人），而不是再做 62 个。中间产物没有这个语义：
         * 手里有的当然要用掉，不然会凭空多做一批中间产物。
         *
         * <p>
         * 和 {@link #useStock} 的关系：{@code useStock=false} 是「所有东西都不扣库存」
         * （从零算），那时这个开关自然不起作用（见 {@code usesStockFor}）。
         */
        public boolean targetIgnoresStock;

        /**
         * 这次要一起做的东西。
         *
         * <p>
         * 「合成 A 64 个 + B 3 个」是常态（做机器要成套）：几件东西共用材料时，
         * 分开算会各算一份、合起来才发现其实是同一批中间产物。
         * 展开是从这几个根同时开始的，公共部分自然只算一次。
         *
         * <p>
         * 为空时退回 {@link #itemId} + {@link #count}（单个目标）。
         */
        public final List<Target> targets = new ArrayList<>();
        /** 玩家指定的配方：物品 id -> 配方序号。 */
        public Map<Integer, Integer> choices = new HashMap<>();
        /**
         * 玩家给过配方、但那条配方在当前索引里找不到的物品。
         *
         * <p>
         * 配方编号是稳定编号（见 {@code WebRecipeIndex.stableRid}），跨索引重建不变；
         * 真找不到只可能是模组/配方变了。这种情况必须说出来 ——
         * 「我明明选了它的合成方式，规划却按别的配方算」正是这么来的。
         */
        public final Set<Integer> unmatchedChoices = new LinkedHashSet<>();
        /**
         * 调试模式（请求里带 {@code debug=1}）：把「被掐断的那些物品各自选了什么配方、
         * 材料是谁」一并写进警告里。
         *
         * <p>
         * 「为什么这件东西明明能做却报缺」只能靠这个查 —— 光看材料表只知道它被掐断了，
         * 不知道是被哪条配方、哪个材料带进环里的。默认关着（正常玩家不需要看这些）。
         */
        public boolean debug;

        /**
         * 玩家给「多候选材料」指定的选择：{@code "谁的配方:格子的x:格子的y" -> 用哪个候选}。
         *
         * <p>
         * 键里带「谁的配方」是因为同一个物品在不同配方里出现在不同格子，
         * 光有坐标分不清是哪一条配方的槽；带坐标是因为同一个配方里可能有
         * 两格都接受多个候选，而它们各自该用哪个是两回事。
         *
         * <p>
         * 值为负 = 没指定，退回候选里的第一个。
         */
        public Map<String, Integer> alts = new HashMap<>();
        /** 当作原始材料、不再往下展开的物品 id。 */
        public Set<Integer> raw = new HashSet<>();
        /** 玩家手动标成「非消耗品」的物品 id（自动判定之外再补的）。 */
        public Set<Integer> catalyst = new HashSet<>();
        /** 玩家手动标成「就按消耗算」的物品 id：推翻自动的非消耗品判定。 */
        public Set<Integer> consumable = new HashSet<>();
        public int maxDepth = DEFAULT_MAX_DEPTH;
        public int maxSteps = DEFAULT_MAX_STEPS;
    }

    public static final class Plan {

        public boolean ok;
        public String error;

        /**
         * 购物清单里被跳过的目标数（那些编号在这台客户端上不存在）。
         *
         * <p>
         * 不失败、只是不参与规划：清单里混进别的客户端的编号是很常见的事
         * （物品编号是每台客户端自己的），为它把玩家正在算的东西一起打掉毫无道理。
         */
        public int skippedTargets;
        public final List<String> warnings = new ArrayList<>();
        public int targetId;
        public String targetName = "";
        public long targetCount;
        /** 这份计划要一起做的东西（至少一个；多个时是「A 64 个 + B 3 个」）。 */
        public final List<Target> targets = new ArrayList<>();
        public final List<Material> materials = new ArrayList<>();
        public final List<Step> steps = new ArrayList<>();
        public int depth;
        public boolean truncated;
    }

    /** 计划里的一个「要做的东西」。 */
    public static final class Target {

        public int itemId;
        public long count = 1L;

        public Target() {}

        public Target(int itemId, long count) {
            this.itemId = itemId;
            this.count = Math.max(1L, Math.min(count, MAX_AMOUNT));
        }
    }

    /** 一份需要玩家自己去弄到的东西。 */
    public static final class Material {

        public int itemId;
        public long need;
        public long have;
        public long missing;

        /**
         * 它为什么成了「要你自己准备的材料」。
         *
         * <p>
         * 这个字段是给玩家看的：同样是「需要准备」，应对方式完全不同 ——
         * 没配方只能去挖，被自己勾成原始材料就该去把勾去掉，循环依赖则换条配方可能就绕开了。
         * 不写清楚，玩家只会看到「我明明选了配方，怎么还让我自己准备」。
         */
        public String reason = "other";
    }

    /** {@link Material#reason} 的取值。 */
    public static final String WHY_NO_RECIPE = "none";

    public static final String WHY_MARKED_RAW = "raw";
    public static final String WHY_CYCLE = "cycle";
    public static final String WHY_LOOP = "loop";
    public static final String WHY_DEPTH = "depth";
    public static final String WHY_UNUSABLE = "fluid";

    /** 非消耗品（可编程电路、模具、常驻工具、透镜）：准备一份就能反复用。 */
    public static final String WHY_CATALYST = "catalyst";

    /**
     * 这个物品这次规划算不算「非消耗品」。
     *
     * <p>
     * 默认来自 {@link WebStore#isCatalyst(int)} 的自动判定，玩家可以按物品推翻它
     * （两个方向都行）：{@code request.consumable} 是「我就要按消耗算」，
     * {@code request.catalyst} 是「这个也算非消耗品」。
     */
    private static boolean isCatalyst(int itemId, Request request) {
        if (request.consumable.contains(itemId)) return false;
        if (request.catalyst.contains(itemId)) return true;
        return WebStore.isCatalyst(itemId);
    }

    /**
     * 这个物品是不是「这次要做的东西」之一。
     *
     * <p>
     * 掐环时不能碰目标本身：把目标钉成原材料等于告诉玩家「这个你自己去弄」，
     * 那这份计划就白算了。多目标时每一个目标都受这条保护。
     */
    private static boolean isTarget(int itemId, Request request) {
        if (itemId == request.itemId) return true;
        for (int i = 0; i < request.targets.size(); i++) {
            Target target = request.targets.get(i);
            if (target != null && target.itemId == itemId) return true;
        }
        return false;
    }

    /**
     * 这件东西要不要按库存扣。
     *
     * <p>
     * 三种模式都从这里出去，别在各处再写一遍判断：
     * <ul>
     * <li>默认：谁都扣；</li>
     * <li>{@code targetIgnoresStock}：<b>目标不扣</b>，中间产物照扣 ——
     * 「我要 64 个 A，仓库里那 2 个不算数，但做 A 要的钢板还是先用仓库里的」；</li>
     * <li>{@code useStock=false}（从零算）：谁都不扣。</li>
     * </ul>
     */
    private static boolean usesStockFor(int itemId, Request request) {
        if (!request.useStock) return false;
        if (!request.targetIgnoresStock) return true;
        return !isTarget(itemId, request);
    }

    public static final class Step {

        public int ordinal;
        public int itemId;
        public String machine = "";
        public long crafts;
        public int perCraft = 1;
        public long total;
        public final List<Ingredient> inputs = new ArrayList<>();
        public final List<Ingredient> extras = new ArrayList<>();

        /** 第几步，从 1 开始，和界面上显示的序号一致。 */
        public int n;

        /**
         * 这一步要等哪些步骤先做完（放的是步骤号）。
         *
         * <p>
         * 空表示「原材料凑齐就能开工」。界面靠它算「现在能并行做哪几步」——
         * 这一步做完之后，等着它的那些步骤才可能变成可做的。
         */
        public final List<Integer> needs = new ArrayList<>();

        /**
         * 第几批：1 = 一开始就能做，2 = 要等第 1 批做完……
         *
         * <p>
         * 同一批之间没有依赖，可以并行（GT 里就是几台机器一起跑）。
         * 这是「最快几轮做完」的度量，也让玩家一眼看出哪些活能同时干。
         */
        public int level = 1;
    }

    public static final class Ingredient {

        public int itemId;
        public int perCraft = 1;
        public long need;
        public long have;
        public long missing;
        /** 这条材料是不是「任意一种都行」的候选之一（界面用来提示可以替代）。 */
        public boolean alternatives;
        /**
         * 这一槽的<b>全部候选</b>（{@code alternatives} 为真时才有意义）。
         *
         * <p>
         * 界面要拿它列出「还可以用哪几种」，库存也要按整组一起算 ——
         * 只看第一候选会告诉玩家「还缺 400」，而他手里正躺着 400 个替代品。
         */
        public int[] alts;
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    public static Plan plan(Request request) {
        Plan plan = new Plan();

        // 目标：显式给了 targets 就用它们，否则退回「单个 itemId + count」。
        // 多个目标共用同一次展开，公共的中间产物只会算一次 ——
        // 分开算再相加的话，「做 A 64 个 + B 3 个」会各要一份同样的电路板，
        // 而实际上它们是同一批。
        List<Target> targets = new ArrayList<>();
        if (!request.targets.isEmpty()) {
            for (int i = 0; i < request.targets.size(); i++) {
                Target target = request.targets.get(i);
                if (target != null && target.itemId >= 0) targets.add(target);
            }
        }
        if (targets.isEmpty()) {
            targets.add(new Target(request.itemId, request.count));
        }
        plan.targets.addAll(targets);
        plan.targetId = targets.get(0).itemId;
        plan.targetName = WebStore.nameOf(plan.targetId);
        plan.targetCount = targets.get(0).count;
        // 后面打分/掐环都只认「第一个目标」，这里统一一下
        request.itemId = plan.targetId;
        request.count = plan.targetCount;

        if (!WebRecipeIndex.isReady()) {
            plan.error = "配方索引还没建立好";
            return plan;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (WebStore.stackOf(targets.get(i).itemId) != null) continue;

            // 物品编号是**每台客户端自己的**（按各自的注册表顺序排），所以换了客户端或存档之后，
            // 页面本地存着的旧编号（目标、配方选择、候选选择、购物清单）全都会指错或指空。
            //
            // 主目标不存在 = 玩家要算的东西在这台客户端上没有：说清原因（光说「找不到物品」
            // 会让人以为模组坏了）。
            if (i == 0) {
                plan.error = "这台客户端上没有这个物品。物品编号是每台客户端自己的，" + "换客户端或存档之后，浏览器里存的旧编号就失效了 —— 重新搜索一次即可。";
                return plan;
            }

            // 附带目标（购物清单里的其它东西）不存在：跳过它，别让整份计划失败。
            // 清单里混进别的客户端的编号很常见，为它把玩家正在算的东西一起打掉毫无道理。
            plan.skippedTargets++;
        }

        // ★ 展开 → 查环 → 把环里的一个物品改成「自己准备」→ 再展开。
        //
        // 为什么要重来一遍而不是「就地删掉一条配方」：真正的问题不在于某一步算错了，
        // 而在于<b>这组配方选择本身自相矛盾</b>。GTNH 里「A 由 B 做、B 又由 A 做」的组合
        // 到处都是（圆石 ↔ 石头、锭 ↔ 粉），一旦选中这样一对，计划里就会同时出现
        // 「用圆石做石头」和「用石头做圆石」两步 —— 谁也排不到谁前面。
        // 把环里的一种物品钉成原材料之后，它的配方整条不再参与，环自然就没了，
        // 而且最后给出的说法是诚实的：「这一样你得自己弄到」。
        Set<Integer> forcedRaw = new HashSet<>();
        Expansion expansion = null;
        int breaks = 0;
        int rounds = 0;
        while (rounds <= MAX_CYCLE_ROUNDS) {
            expansion = expand(request, forcedRaw);
            expansion.deps = resolveDependencies(expansion.pushedEdges, expansion.chosen, expansion.crafts);

            Set<Integer> cyclic = findUnorderable(expansion.crafts, expansion.deps);
            if (cyclic.isEmpty()) break;

            // 先一次一个地掐：掐最深的那个，对整棵树的扰动最小，能拆掉大部分环
            if (breaks < MAX_CYCLE_BREAKS) {
                int victim = pickCycleBreaker(cyclic, expansion, request, forcedRaw);
                if (victim >= 0) {
                    forcedRaw.add(victim);
                    breaks++;
                    continue;
                }
            }

            // 一个掐不完（互相咬合的环有好几组），把「真的在环上」的那些一次钉完再展开确认。
            //
            // 第一轮不碰玩家明确选过配方的物品：他选了哪条就是哪条，掐环从环上的
            // 另一样下手；实在绕不开（环上全是玩家选的）才在后面的轮次里动它。
            rounds++;
            // 轮次用完了就别再钉：钉了也没有下一次展开来验证，只会凭空多几样「自己准备」
            if (rounds > MAX_CYCLE_ROUNDS) break;
            if (!pinCycleMembers(cyclic, expansion, request, forcedRaw, rounds > 1)) break;
        }
        if (expansion == null) {
            plan.error = "规划失败";
            return plan;
        }

        // 说清楚是「哪些」被掐断了：只写「其中一种」的话，玩家在材料表里看到某样东西
        // 突然变成「要自己准备」，根本对不上号（「我明明选了它的配方」就是这么来的）
        if (!forcedRaw.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Integer id : forcedRaw) {
                names.add(WebStore.nameOf(id));
                if (names.size() >= 6) break;
            }
            plan.warnings.add(
                "这些物品的配方互相绕回自己，规划把它们当成要你自己准备的：" + String.join("、", names)
                    + (forcedRaw.size() > names.size() ? " 等 " + forcedRaw.size() + " 种" : "")
                    + "。给其中某一样换条配方，往往就能绕开。");
        }

        plan.depth = expansion.depth;
        plan.truncated = expansion.truncated;
        if (expansion.truncated) {
            plan.warnings.add("展开到上限就停下了，结果可能不完整（可以试着把某些材料设成「自己准备」）。");
        }
        if (!expansion.cycleBroken.isEmpty()) {
            plan.warnings.add("有 " + expansion.cycleBroken.size() + " 种物品的配方绕回了自己，已经当作需要你自己准备的材料。");
        }
        // 玩家手动选过配方、而那条配方正处在环上：直接点名。
        // 「我明明选了它的合成方式，规划还是把它当原料」多半就是这个 ——
        // 选中的那条是回收/提取类配方（材料本身来自这个物品），走一步就回到原点。
        Set<Integer> chosenOnCycle = new LinkedHashSet<>();
        for (Integer id : expansion.cycleBroken) {
            if (request.choices.containsKey(id)) chosenOnCycle.add(id);
        }
        if (!chosenOnCycle.isEmpty()) {
            plan.warnings.add("这几样你手动选过配方，但那条配方展开时绕回了自己：" + nameList(chosenOnCycle) + "。换一条配方通常就能绕开。");
        }
        // 给过配方、却在当前索引里找不到那一条：说清楚，别让它以为自己的选择生效了
        if (!request.unmatchedChoices.isEmpty()) {
            plan.warnings.add(
                "这几样你之前选过配方，但当前索引里找不到那一条（配方编号在重建索引后会变，换过模组也会）：" + nameList(request.unmatchedChoices)
                    + "。已经按自动挑选处理，在物品页重新选一次就好。");
        }
        if (request.debug) {
            for (Integer id : expansion.cycleBroken) {
                plan.warnings.add(describeChoice("[调试] ", expansion, id.intValue()));
            }
            // 被掐环掐成「自己准备」的：把它们各自选中的配方也写出来。
            // 不加这一段的话，线上只能看到一个名字，根本看不出它是从哪条配方绕回自己的
            // （「熔融焊锡为什么算原材料」就是这么查出来的：它选的是流体提取机 ← 焊锡粉）。
            for (Integer id : forcedRaw) {
                plan.warnings.add(describeChoice("[调试] 掐环 ", expansion, id.intValue()));
            }
        }

        // 原材料 = 「没能展开成配方」的那些需求。
        //
        // 注意这里用的是 mustObtain 而不是 need：同一个物品可能既被展开过（它自己能做出来）、
        // 又有一处需求绕回了它自己（环路被掐断的那一支）。那一支的量是真得自己弄到的，
        // 但它不在 need 的「没有配方」那一类里 —— 只看 need 会把它整个漏掉，
        // 计划就变成「做 A 需要 B、做 B 需要 A」外加一张空的材料表。
        for (Map.Entry<Integer, Long> entry : expansion.mustObtain.entrySet()) {
            int itemId = entry.getKey();
            long required = entry.getValue();
            // 已经展开过的物品，它的库存已经在「要不要多做几次」那一步扣过了，
            // 这里再扣一次等于把同一批库存花两遍
            long have = usesStockFor(itemId, request) && !expansion.expanded.contains(itemId)
                ? Math.min(required, stock(itemId, expansion.stockCache))
                : 0L;
            long missing = Math.max(0L, required - have);
            if (missing <= 0L) continue;
            Material material = new Material();
            material.itemId = itemId;
            material.need = required;
            material.have = have;
            material.missing = missing;
            String why = expansion.rawReason.get(itemId);
            material.reason = why == null ? "other" : why;
            plan.materials.add(material);
        }

        // 非消耗品也列进「需要准备」，但只有一份：可编程电路、模具、常驻工具这些
        // 做完还在手上，要的是「备一个」，不是「备 512 个」
        for (Map.Entry<Integer, Long> entry : expansion.catalystNeed.entrySet()) {
            int itemId = entry.getKey();
            long required = Math.max(1L, entry.getValue());
            long have = usesStockFor(itemId, request) ? Math.min(required, stock(itemId, expansion.stockCache)) : 0L;
            long missing = Math.max(0L, required - have);
            if (missing <= 0L) continue;
            Material material = new Material();
            material.itemId = itemId;
            material.need = required;
            material.have = have;
            material.missing = missing;
            material.reason = WHY_CATALYST;
            plan.materials.add(material);
        }
        plan.materials.sort((a, b) -> Long.compare(b.missing, a.missing));

        buildSteps(plan, request, expansion.crafts, expansion.deps, expansion.chosen, expansion.stockCache);
        plan.ok = true;
        return plan;
    }

    /** 一次展开的全部产物。 */
    private static final class Expansion {

        final Map<Integer, Long> need = new LinkedHashMap<>();
        /** 必须自己弄到的量：所有「没能展开成配方」的那部分需求。 */
        final Map<Integer, Long> mustObtain = new LinkedHashMap<>();
        final Map<Integer, Long> crafts = new HashMap<>();
        final Map<Integer, Integer> chosen = new HashMap<>();
        /** 展开时记下的原始依赖边，最后统一翻译（见 {@link #resolveDependencies}）。 */
        final List<int[]> pushedEdges = new ArrayList<>();
        final Set<Integer> expanded = new LinkedHashSet<>();
        final Set<Integer> cycleBroken = new LinkedHashSet<>();
        /** 每个「要自己准备」的物品是因为哪一条没能展开，见 {@link Material#reason}。 */
        final Map<Integer, String> rawReason = new LinkedHashMap<>();
        /**
         * 非消耗品需要备几份：取「单次配方用量」的最大值，不按合成次数乘。
         * 见 {@link WebStore#isCatalyst(int)}。
         */
        final Map<Integer, Long> catalystNeed = new LinkedHashMap<>();
        final Map<Integer, Long> stockCache = new HashMap<>();
        /** 物品在树里的最大层数，用来挑「环里最该被当成原材料的那一个」。 */
        final Map<Integer, Integer> itemDepth = new HashMap<>();

        Map<Integer, Set<Integer>> deps = Collections.emptyMap();
        int depth;
        boolean truncated;
    }

    private static Expansion expand(Request request, Set<Integer> extraRaw) {
        Expansion out = new Expansion();
        Deque<Work> queue = new ArrayDeque<>();
        // 多个目标就是多个起点：共用同一次展开，公共的中间产物自然只算一次
        for (int i = 0; i < request.targets.size(); i++) {
            Target target = request.targets.get(i);
            if (target == null || target.itemId < 0) continue;
            queue.add(new Work(target.itemId, Math.max(1L, Math.min(target.count, MAX_AMOUNT)), 1L, 0, EMPTY_PATH, -1));
        }
        if (queue.isEmpty()) {
            queue.add(
                new Work(request.itemId, Math.max(1L, Math.min(request.count, MAX_AMOUNT)), 1L, 0, EMPTY_PATH, -1));
        }

        int visits = 0;
        while (!queue.isEmpty()) {
            if (++visits > MAX_VISITS) {
                out.truncated = true;
                break;
            }
            Work work = queue.poll();
            if (work.amount <= 0L) continue;

            add(out.need, work.itemId, work.amount);
            if (work.depth > out.depth) out.depth = work.depth;
            Integer known = out.itemDepth.get(work.itemId);
            if (known == null || work.depth > known) out.itemDepth.put(work.itemId, work.depth);

            boolean expandedHere = false;
            // 「库存已经够了、这一次不用做」也是一种正常的收敛，不能记成原材料
            boolean coveredByStock = false;
            // 没能展开的原因，见 Material.reason
            String why = null;

            // 非消耗品（可编程电路、模具、常驻工具、透镜）：不展开，也不按次数累加 ——
            // 一份就能反复用。玩家要的是「准备一个模具」，不是「准备 512 个模具」。
            if (isCatalyst(work.itemId, request)) {
                Long previous = out.catalystNeed.get(work.itemId);
                if (previous == null || work.unit > previous) {
                    // 取「单次配方用量」的最大值：不同配方一次要的份数不一样，
                    // 备够最费的那一条就行
                    out.catalystNeed.put(work.itemId, work.unit);
                }
                continue;
            }

            boolean expandable = !request.raw.contains(work.itemId) && !extraRaw.contains(work.itemId)
                && work.depth < request.maxDepth
                && !contains(work.path, work.itemId)
                && WebRecipeIndex.hasRecipes(work.itemId);

            if (expandable) {
                int ordinal = resolve(request, out.chosen, work.itemId);
                WebRecipeIndex.RecipeView view = ordinal < 0 ? null : WebRecipeIndex.view(ordinal);
                int perCraft = view == null ? 0 : perCraftOf(view, work.itemId);
                // 只有流体/能量输入的配方也不能拿来指导（见 pickRecipe 的说明）：
                // 那种配方在物品形态上看就是「凭空产出」，照着它算出来的步骤是假的
                if (view != null && perCraft > 0 && view.inputs.length > 0) {
                    long available = usesStockFor(work.itemId, request) ? stock(work.itemId, out.stockCache) : 0L;
                    long effective = Math.max(0L, out.need.get(work.itemId) - available);
                    long wantedCrafts = ceilDiv(effective, perCraft);

                    long done = out.crafts.containsKey(ordinal) ? out.crafts.get(ordinal) : 0L;
                    long delta = wantedCrafts - done;
                    if (delta > 0L) {
                        out.crafts.put(ordinal, done + delta);
                        out.expanded.add(work.itemId);
                        expandedHere = true;

                        if (out.crafts.size() > request.maxSteps) {
                            out.truncated = true;
                            break;
                        }

                        int[] childPath = push(work.path, work.itemId);
                        for (int i = 0; i < view.inputs.length; i++) {
                            WebRecipeIndex.Slot slot = view.inputs[i];
                            int childItem = pickAlt(request, work.itemId, slot);
                            if (childItem < 0) continue;
                            long amount = Math.min(MAX_AMOUNT, (long) slot.amount * delta);
                            queue.add(
                                new Work(
                                    childItem,
                                    amount,
                                    Math.max(1, slot.amount),
                                    work.depth + 1,
                                    childPath,
                                    ordinal));
                            // 边要在这里记，而且要记全：同一个物品可能在好几条分支上被需要，
                            // 而它只会被「展开」一次（后面的需求只补次数、不再推材料）。
                            // 只在那一次展开时连边的话，其余消费者就失去了「先做它」这条约束，
                            // 步骤顺序会错（实测「第 1 步要的圆石排在第 5 步」就是这么来的）。
                            out.pushedEdges.add(new int[] { ordinal, childItem });
                        }
                    } else {
                        coveredByStock = true;
                    }
                } else {
                    // 有配方，但照着它指导不了（没有物品材料 / 主产物对不上）
                    why = WHY_UNUSABLE;
                }
            }

            if (contains(work.path, work.itemId)) out.cycleBroken.add(work.itemId);
            if (!expandedHere && !coveredByStock) {
                add(out.mustObtain, work.itemId, work.amount);
                if (why == null) {
                    if (request.raw.contains(work.itemId)) {
                        why = WHY_MARKED_RAW;
                    } else if (extraRaw.contains(work.itemId)) {
                        why = WHY_CYCLE;
                    } else if (contains(work.path, work.itemId)) {
                        why = WHY_LOOP;
                    } else if (!WebRecipeIndex.hasRecipes(work.itemId)) {
                        why = WHY_NO_RECIPE;
                    } else if (work.depth >= request.maxDepth) {
                        why = WHY_DEPTH;
                    } else {
                        why = "other";
                    }
                }
                // 同一个物品可能从好几条分支被需要，第一个原因是哪条就是哪条
                if (!out.rawReason.containsKey(work.itemId)) out.rawReason.put(work.itemId, why);
            }
        }
        return out;
    }

    /** 拓扑排序排不出去的那些配方 = 处在环里（或被环挡住）的配方。 */
    private static Set<Integer> findUnorderable(Map<Integer, Long> crafts, Map<Integer, Set<Integer>> deps) {
        Map<Integer, Integer> inDegree = new HashMap<>();
        Map<Integer, List<Integer>> dependents = new HashMap<>();
        for (Integer ordinal : crafts.keySet()) {
            Set<Integer> needs = deps.get(ordinal);
            inDegree.put(ordinal, needs == null ? 0 : needs.size());
        }
        for (Map.Entry<Integer, Set<Integer>> entry : deps.entrySet()) {
            if (!crafts.containsKey(entry.getKey())) continue;
            for (Integer producer : entry.getValue()) {
                if (!crafts.containsKey(producer)) continue;
                dependents.computeIfAbsent(producer, key -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }

        Deque<Integer> ready = new ArrayDeque<>();
        for (Map.Entry<Integer, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) ready.add(entry.getKey());
        }
        Set<Integer> ordered = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            int ordinal = ready.poll();
            ordered.add(ordinal);
            List<Integer> next = dependents.get(ordinal);
            if (next == null) continue;
            for (int i = 0; i < next.size(); i++) {
                int consumer = next.get(i);
                int remaining = inDegree.get(consumer) - 1;
                inDegree.put(consumer, remaining);
                if (remaining == 0) ready.add(consumer);
            }
        }

        Set<Integer> cyclic = new LinkedHashSet<>();
        for (Integer ordinal : crafts.keySet()) {
            if (!ordered.contains(ordinal)) cyclic.add(ordinal);
        }
        return cyclic;
    }

    /**
     * 从环里挑一个物品钉成原材料。
     *
     * <p>
     * 挑法，按优先级：
     * <ol>
     * <li><b>不碰玩家明确指定过配方的物品</b>。「我给它选了配方」是这份计划里最强的意图表达，
     * 掐掉它等于当面把玩家的选择删了 —— 玩家看到的现象就是「我明明选了铝杆的配方，
     * 为什么还让我自己准备」。被玩家抓到过一次，所以这一条排在最前面。</li>
     * <li>不挑目标本身（等于告诉玩家「这个你自己去弄」，那这条计划就白算了）；</li>
     * <li>挑层数最深的（最像中间产物，掐掉它对整棵树的影响最小）。</li>
     * </ol>
     */
    private static int pickCycleBreaker(Set<Integer> cyclic, Expansion expansion, Request request,
        Set<Integer> forcedRaw) {
        int best = -1;
        int bestDepth = -1;
        int fallback = -1;
        int fallbackDepth = -1;
        // 掐环必须掐在环上。
        //
        // cyclic 里装的<b>不只是环上的配方</b>：拓扑排序排不完的节点全都算在里面，
        // 包括那些只是「排在环后面」的（它们自己不在环上，掐掉对环毫无影响）。
        // 只按深度挑就会挑中这种，白费一次机会 —— 实测「水单元 ↔ 水」那个环，
        // 它去掐了深度相同的「空单元」，掐完环还在，最后只能把目标自己列进材料表。
        for (Integer ordinal : cyclic) {
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(ordinal);
            if (view == null || view.resultId < 0) continue;
            if (isTarget(view.resultId, request)) continue;
            if (forcedRaw.contains(view.resultId)) continue;
            if (!onCycle(ordinal, expansion.deps, expansion.crafts)) continue;
            Integer depth = expansion.itemDepth.get(view.resultId);
            int value = depth == null ? 0 : depth;

            boolean chosenByPlayer = request.choices.containsKey(view.resultId);
            if (chosenByPlayer) {
                // 玩家指定的配方：只在实在没得挑时才动它
                if (value > fallbackDepth) {
                    fallbackDepth = value;
                    fallback = view.resultId;
                }
                continue;
            }
            if (value > bestDepth) {
                bestDepth = value;
                best = view.resultId;
            }
        }
        if (best >= 0) return best;
        if (fallback >= 0) return fallback;

        // 环上一个能掐的都没有（都是目标或玩家指定过的）：退而求其次，挑最深的那个
        for (Integer ordinal : cyclic) {
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(ordinal);
            if (view == null || view.resultId < 0 || isTarget(view.resultId, request)) continue;
            Integer depth = expansion.itemDepth.get(view.resultId);
            int value = depth == null ? 0 : depth;
            if (value > fallbackDepth) {
                fallbackDepth = value;
                fallback = view.resultId;
            }
        }
        return fallback;
    }

    /**
     * 把「真的在环上」的物品一次钉成要玩家自己准备的材料。
     *
     * <p>
     * ★ 关键在于<b>只钉在环上的</b>。{@code cyclic} 里装的是「拓扑排序排不完的全部节点」，
     * 其中绝大多数只是<b>排在环后面</b>（被环挡住的中间产物），它们自己根本不在环上。
     * 早先这里是把 {@code cyclic} 整个钉掉，实测在一次真实的计划里钉了 185 样东西 ——
     * 里面正好包含顶层目标工作站的全部 7 样直接材料，整份计划当场退化成「1 步 + 7 样自己准备」，
     * 玩家看到的却是「熔融焊锡明明能提取，却报缺 9216」。
     *
     * @param includeChosen 连玩家明确选过配方的物品也一起钉（只在绕不开的后续轮次里为 true）
     * @return 有没有真的钉掉东西（一个都没有就别再空转重算了）
     */
    private static boolean pinCycleMembers(Set<Integer> cyclic, Expansion expansion, Request request,
        Set<Integer> forcedRaw, boolean includeChosen) {
        boolean changed = false;
        for (Integer ordinal : cyclic) {
            if (!onCycle(ordinal, expansion.deps, expansion.crafts)) continue;
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(ordinal);
            if (view == null || view.resultId < 0 || isTarget(view.resultId, request)) continue;
            if (!includeChosen && request.choices.containsKey(view.resultId)) continue;
            if (forcedRaw.contains(view.resultId)) continue;
            changed |= forcedRaw.add(view.resultId);
        }
        return changed;
    }

    /**
     * 这个配方在不在环上（顺着「要先做完」的边能不能绕回自己）。
     *
     * <p>
     * 掐环只能掐环上的节点：掐一个只是「排在环后面」的节点，环原封不动，
     * 下一轮还会挑到它，几轮机会全浪费掉。
     */
    private static boolean onCycle(int ordinal, Map<Integer, Set<Integer>> deps, Map<Integer, Long> crafts) {
        Deque<Integer> stack = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        Set<Integer> direct = deps.get(ordinal);
        if (direct == null || direct.isEmpty()) return false;
        stack.addAll(direct);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            if (current == ordinal) return true;
            if (!seen.add(current)) continue;
            Set<Integer> next = deps.get(current);
            if (next == null) continue;
            for (Integer producer : next) {
                if (crafts.containsKey(producer)) stack.push(producer);
            }
        }
        return false;
    }

    /**
     * 把展开时记下的原始边翻译成「配方 → 要先做完的配方」。
     *
     * <p>
     * 判据是「那个材料的配方确实进了这份计划」（{@code crafts} 里有它）：没进就说明它在这次
     * 规划里是要玩家自己准备的材料，不构成先后依赖。这条判据同时也保证了图里不会有环 ——
     * 能进 {@code crafts} 的配方都是沿着一棵展开树推出来的。
     */
    private static Map<Integer, Set<Integer>> resolveDependencies(List<int[]> pushedEdges, Map<Integer, Integer> chosen,
        Map<Integer, Long> crafts) {
        Map<Integer, Set<Integer>> deps = new HashMap<>();
        for (int i = 0; i < pushedEdges.size(); i++) {
            int[] edge = pushedEdges.get(i);
            Integer producer = chosen.get(edge[1]);
            if (producer == null || producer == edge[0]) continue;
            if (!crafts.containsKey(producer)) continue;
            deps.computeIfAbsent(edge[0], key -> new LinkedHashSet<>())
                .add(producer);
        }
        return deps;
    }

    private static void buildSteps(Plan plan, Request request, Map<Integer, Long> crafts,
        Map<Integer, Set<Integer>> deps, Map<Integer, Integer> chosen, Map<Integer, Long> stockCache) {
        // 依赖图的边来自「展开时真的把谁推给了谁」（见 plan 里往 deps 里记的那两行），
        // 而不是重新扫一遍每条配方的材料表。
        //
        // 这个区别是必须的：配方表里 A 的材料可能是 B、B 的材料又可能是 A（GTNH 里
        // 锭 ↔ 粉、电路板 ↔ 编程电路这种 1:1 转换满地都是），照材料表连边就会连出环，
        // 拓扑排序排不完，步骤顺序直接乱掉（实测「第 4 步要的石英纤维排在第 15 步」）。
        // 而实际展开出来的是一棵树：绕回祖先的那一支根本没往下推，也就没有那条边。
        Map<Integer, List<Integer>> dependents = new HashMap<>();
        Map<Integer, Integer> inDegree = new HashMap<>();
        for (Integer ordinal : crafts.keySet()) {
            Set<Integer> needs = deps.get(ordinal);
            inDegree.put(ordinal, needs == null ? 0 : needs.size());
        }
        for (Map.Entry<Integer, Set<Integer>> entry : deps.entrySet()) {
            if (!crafts.containsKey(entry.getKey())) continue;
            for (Integer producer : entry.getValue()) {
                if (!crafts.containsKey(producer)) continue;
                dependents.computeIfAbsent(producer, key -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }

        // 拓扑排序（Kahn）：先做的排前面
        Deque<Integer> ready = new ArrayDeque<>();
        for (Map.Entry<Integer, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) ready.add(entry.getKey());
        }
        List<Integer> order = new ArrayList<>(crafts.size());
        while (!ready.isEmpty()) {
            int ordinal = ready.poll();
            order.add(ordinal);
            List<Integer> next = dependents.get(ordinal);
            if (next == null) continue;
            for (int i = 0; i < next.size(); i++) {
                int consumer = next.get(i);
                int remaining = inDegree.get(consumer) - 1;
                inDegree.put(consumer, remaining);
                if (remaining == 0) ready.add(consumer);
            }
        }
        // 排序没排完时把剩下的接在后面（上面的边集来自一棵树，理论上不会发生），
        // 宁可顺序差一点也不要丢步骤
        for (Integer ordinal : crafts.keySet()) {
            if (!order.contains(ordinal)) order.add(ordinal);
        }

        // 配方编号 -> 步骤号。拓扑序保证依赖一定排在前面，
        // 所以轮到自己时，所有依赖都已经有号了
        Map<Integer, Integer> stepNo = new HashMap<>();
        Map<Integer, Integer> levelOf = new HashMap<>();
        int nextNo = 0;

        for (int i = 0; i < order.size(); i++) {
            int ordinal = order.get(i);
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(ordinal);
            if (view == null) continue;
            long times = crafts.get(ordinal);

            // 这一步是做哪个物品的？从「物品 → 配方」反查回来，而不是拿配方的主产物 ——
            // 一条配方可能有多个产出，规划它是为了哪一个由 chosen 说了算
            int itemId = view.resultId;
            for (Map.Entry<Integer, Integer> entry : chosen.entrySet()) {
                if (entry.getValue() == ordinal) {
                    itemId = entry.getKey();
                    break;
                }
            }
            int perCraft = perCraftOf(view, itemId);
            if (perCraft <= 0) perCraft = Math.max(1, view.resultAmount);

            Step step = new Step();
            step.n = ++nextNo;
            step.ordinal = ordinal;
            step.itemId = itemId;
            step.machine = view.machine;
            step.crafts = times;
            step.perCraft = perCraft;
            step.total = step.perCraft * times;
            fillIngredients(step.inputs, view.inputs, times, request, itemId, stockCache);
            fillIngredients(step.extras, view.extras, times, request, itemId, stockCache);

            // 把依赖的「配方编号」翻译成「步骤号」：界面上认的是序号，
            // 内部编号（ordinal）是索引里的位置，玩家和前端都不该看到它。
            // 顺便算批次：第几轮才能开工
            Set<Integer> needOrdinals = deps.get(ordinal);
            int level = 1;
            if (needOrdinals != null) {
                for (Integer producer : needOrdinals) {
                    Integer producerNo = stepNo.get(producer);
                    if (producerNo == null) continue; // 那个物品被当成原始材料了，不算步骤
                    step.needs.add(producerNo);
                    Integer producerLevel = levelOf.get(producer);
                    if (producerLevel != null && producerLevel + 1 > level) {
                        level = producerLevel + 1;
                    }
                }
                Collections.sort(step.needs);
            }
            step.level = level;
            stepNo.put(ordinal, step.n);
            levelOf.put(ordinal, level);
            plan.steps.add(step);
        }
    }

    private static void fillIngredients(List<Ingredient> out, WebRecipeIndex.Slot[] slots, long times, Request request,
        int ownerItemId, Map<Integer, Long> stockCache) {
        // 同一种材料在配方里可能占好几格（比如四块钢板），合并成一行更好读
        //
        // 合并键用**整组候选**而不是第一候选：矿物词典那一类「铁板 / 各类铁板都行」的槽，
        // 用第一候选当键的话，同一个槽的两个候选会被算成两种材料（钢板一栏、铁板一栏），
        // 而且库存只统计了第一候选那一种 —— 玩家明明有 400 个替代品，页面还是说「还缺 400」。
        Map<String, Ingredient> merged = new LinkedHashMap<>();
        List<Ingredient> order = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            WebRecipeIndex.Slot slot = slots[i];
            if (slot.alts.length == 0) continue;

            StringBuilder key = new StringBuilder();
            for (int a = 0; a < slot.alts.length; a++) {
                key.append(slot.alts[a])
                    .append(',');
            }
            Ingredient ingredient = merged.get(key.toString());
            if (ingredient == null) {
                ingredient = new Ingredient();
                // 玩家给这一格指定过用哪个候选就用那个 —— 材料表里的「还缺多少」
                // 也要跟着按它算，否则会出现「界面上显示 A、库存却按 B 算」
                ingredient.itemId = pickAlt(request, ownerItemId, slot);
                if (ingredient.itemId < 0) ingredient.itemId = slot.alts[0];
                ingredient.alts = slot.alts.clone();
                ingredient.perCraft = slot.amount;
                ingredient.alternatives = slot.alts.length > 1;
                merged.put(key.toString(), ingredient);
                order.add(ingredient);
            } else {
                ingredient.perCraft += slot.amount;
            }
        }

        for (int i = 0; i < order.size(); i++) {
            Ingredient ingredient = order.get(i);
            ingredient.need = Math.min(MAX_AMOUNT, (long) ingredient.perCraft * times);
            ingredient.have = usesStockFor(ingredient.itemId, request)
                ? Math.min(ingredient.need, stockOfAny(ingredient, stockCache))
                : 0L;
            ingredient.missing = Math.max(0L, ingredient.need - ingredient.have);
            out.add(ingredient);
        }
    }

    /**
     * 这一格该用哪个候选。
     *
     * <p>
     * 默认是候选里的第一个（索引建好后就不再变，所以同一份数据每次算出来都一样）；
     * 玩家在界面上给这一格指定过的话就用指定的那个 —— <b>但只有它确实在这一格的候选里才算数</b>。
     * 这条校验是必须的：配方换了、索引重建了之后坐标可能指向别的槽位，
     * 那时候默默按一个不相干的物品算，比忽略这个选择糟得多。
     *
     * @param ownerItemId 这一格属于谁的配方（键的第一段）
     * @return 选中的物品号；没有候选时返回 -1
     */
    private static int pickAlt(Request request, int ownerItemId, WebRecipeIndex.Slot slot) {
        if (slot == null || slot.alts.length == 0) return -1;

        Integer chosen = request.alts.get(ownerItemId + ":" + slot.x + ":" + slot.y);
        if (chosen != null) {
            for (int i = 0; i < slot.alts.length; i++) {
                if (slot.alts[i] == chosen.intValue()) return chosen.intValue();
            }
        }
        return slot.alts[0];
    }

    /**
     * 这组候选加起来有多少。
     *
     * <p>
     * 「任意一种都行」的槽位，库存要把所有候选算在一起：玩家有 6 组铁板 + 2 组钢板，
     * 那这个槽就是够的。只看第一候选会得出「还缺 8 组」这种让人白跑一趟的结论。
     */
    private static long stockOfAny(Ingredient ingredient, Map<Integer, Long> stockCache) {
        if (ingredient.alts == null || ingredient.alts.length <= 1) {
            return stock(ingredient.itemId, stockCache);
        }
        long total = 0L;
        for (int i = 0; i < ingredient.alts.length; i++) {
            total += stock(ingredient.alts[i], stockCache);
            if (total >= MAX_AMOUNT) return MAX_AMOUNT;
        }
        return total;
    }

    // ==================================================================
    // 配方选择
    // ==================================================================

    private static int resolve(Request request, Map<Integer, Integer> chosen, int itemId) {
        Integer cached = chosen.get(itemId);
        if (cached != null) return cached;

        int ordinal = pickRecipe(request, itemId);
        chosen.put(itemId, ordinal);
        return ordinal;
    }

    /**
     * 挑一条配方。
     *
     * <p>
     * 打分只看三件事，都是玩家自己能理解的量：材料种类少、手头已有的比例高、
     * 能在工作台里做。真正「哪条对」是玩家的事 —— 手机上每个节点都能改，
     * 这里只保证默认值不离谱。
     */
    private static int pickRecipe(Request request, int itemId) {
        Integer forcedRid = request.choices.get(itemId);
        if (forcedRid != null) {
            int forced = ordinalOfRid(itemId, forcedRid.intValue());
            if (forced >= 0 && isUsable(forced, itemId)) return forced;
            // 玩家存的编号在当前索引里找不到（模组或配方变过）：记下来，最后在警告里说清楚。
            // 不能一声不响地换成别的配方 —— 那正是「我明明选了它的合成方式」的来源。
            request.unmatchedChoices.add(Integer.valueOf(itemId));
        }

        int[] candidates = WebRecipeIndex.recipesFor(itemId);
        if (candidates.length == 0) return -1;

        // ★ 先按原来的打分排序，再从上往下取第一条「不绕回自己」的。
        //
        // 为什么要多这一步：回收/提取/固化/装罐类配方（熔融焊锡单元 → 熔融焊锡、
        // 焊锡滚珠 → 熔融焊锡……）材料少、在打分里最占便宜，于是默认总挑中它们；
        // 可沿着它走一步就绕回原点，最后整件东西被当成「要你自己准备的材料」——
        // 玩家看到的就是「明明能做的流体，却报缺」。
        //
        // 先排序再逐个检查，是因为那个检查要翻材料的配方（比打分贵得多）：
        // 排在最前面的候选通常一次就通过，代价可以忽略。
        int limit = Math.min(candidates.length, MAX_CANDIDATES_SCORED);
        int[] scored = new int[limit];
        double[] scores = new double[limit];
        int scoredCount = 0;
        for (int i = 0; i < limit; i++) {
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(candidates[i]);
            if (!isDefaultCandidate(view, itemId)) continue;
            scored[scoredCount] = candidates[i];
            scores[scoredCount] = scoreOf(view);
            scoredCount++;
        }
        if (scoredCount == 0) return -1;

        // 排序：先按分数，同分时「回收类」排后面。
        //
        // ★ 回收类配方（处理器标签里带 {@code _recycling}）必须排最后：它们是「把成品拆回原料」，
        // 不是生产方法。材料少、打分高，索引顺序又常常靠前，于是默认总挑中它们，
        // 然后一条条绕回原点（实测：熔融焊锡挑成了「焊锡螺栓 → 小撮焊锡粉 → 熔融焊锡」这种回炉环，
        // 而它其实有正规配方「焊锡锭 → 熔融焊锡」，规划就再也不肯用它了）。
        for (int i = 0; i < scoredCount; i++) {
            int bestAt = i;
            for (int j = i + 1; j < scoredCount; j++) {
                if (betterCandidate(scored[j], scores[j], scored[bestAt], scores[bestAt])) bestAt = j;
            }
            if (bestAt != i) {
                double tmpScore = scores[i];
                scores[i] = scores[bestAt];
                scores[bestAt] = tmpScore;
                int tmpOrdinal = scored[i];
                scored[i] = scored[bestAt];
                scored[bestAt] = tmpOrdinal;
            }
        }

        // 第一轮：正规配方 + 材料有正规来源 + 不绕回自己
        for (int i = 0; i < scoredCount; i++) {
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(scored[i]);
            if (view == null || isRecycling(view) || deadEndFor(view, itemId)) continue;
            if (loopsBack(view, itemId)) continue;
            if (inputsHaveCleanSource(view)) return scored[i];
        }
        // 第二轮：这件东西只有回收来源（或者正规配方都绕回自己），那就用回收的
        for (int i = 0; i < scoredCount; i++) {
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(scored[i]);
            if (view == null || isRecycling(view) || deadEndFor(view, itemId)) continue;
            return scored[i];
        }
        // 第三轮：连正规来源都没有，用回收的
        for (int i = 0; i < scoredCount; i++) {
            WebRecipeIndex.RecipeView view = WebRecipeIndex.view(scored[i]);
            if (view == null || deadEndFor(view, itemId)) continue;
            return scored[i];
        }
        // 全都被排掉了：还是按打分来，别因为「怕绕路」就凭空变成「没有配方」
        return scored[0];
    }

    /**
     * 这条配方的材料，会不会反过来要用这件东西才做得出来（一步回到原点）。
     *
     * <p>
     * 例子（实测，就是玩家报的「熔融焊锡报缺」那一次）：熔融焊锡 ← 焊锡粉 ← 焊锡锭，
     * 而为焊锡锭挑中的是「烧制：2x焊锡导线 → 焊锡锭」—— 可 2x焊锡导线本身就是拿焊锡锭轧出来的。
     * 这种配方材料只有一样、打分最高、索引顺序又靠前，于是默认总被挑中；
     * 挑中之后这条链在拓扑排序里永远排不出去，最后一大批东西被当成「要你自己准备」。
     *
     * <p>
     * 只看两层（材料的正规来源里有没有它），不做传递闭包：真正咬合的都是这一层，
     * 而每多一层就要多翻一遍配方表 —— 这个判断会在挑配方时被调很多次。
     */
    private static boolean loopsBack(WebRecipeIndex.RecipeView view, int itemId) {
        for (int s = 0; s < view.inputs.length; s++) {
            int[] alts = view.inputs[s].alts;
            int take = Math.min(alts.length, 4);
            for (int a = 0; a < take; a++) {
                int input = alts[a];
                if (input < 0) continue;
                if (input == itemId) return true;
                int[] producers = WebRecipeIndex.recipesFor(input);
                int limit = Math.min(producers.length, 12);
                for (int p = 0; p < limit; p++) {
                    WebRecipeIndex.RecipeView produced = WebRecipeIndex.view(producers[p]);
                    if (produced == null || isRecycling(produced)) continue;
                    if (consumesItem(produced, itemId)) return true;
                }
            }
        }
        return false;
    }

    /**
     * 这条配方的材料里，是不是每一样都备得出来。
     *
     * <p>
     * 判据是「至少有一条<b>正规</b>（非回收）来源，或者它本来就是原材料」。<b>是</b>才用它 ——
     * 这样「熔融焊锡 ← 提取机 ← 焊锡锭 ← 合金炉」这种正常链会排在
     * 「… ← 焊锡箔 ← 焊锡粉 ← 研磨机回收」这种绕一大圈（而且要靠回炉）的前面。
     */
    private static boolean inputsHaveCleanSource(WebRecipeIndex.RecipeView view) {
        for (int s = 0; s < view.inputs.length; s++) {
            int[] alts = view.inputs[s].alts;
            if (alts.length == 0) continue;
            boolean clean = false;
            int altTake = Math.min(alts.length, 4);
            for (int a = 0; a < altTake && !clean; a++) {
                int[] producers = WebRecipeIndex.recipesFor(alts[a]);
                if (producers.length == 0) {
                    // 本来就是要去挖/去换的原材料，没问题
                    clean = true;
                    break;
                }
                int limit = Math.min(producers.length, 8);
                for (int p = 0; p < limit; p++) {
                    WebRecipeIndex.RecipeView produced = WebRecipeIndex.view(producers[p]);
                    if (produced != null && !isRecycling(produced)) {
                        clean = true;
                        break;
                    }
                }
            }
            if (!clean) return false;
        }
        return true;
    }

    /** 排序用：分数高的优先；同分时非回收的优先。 */
    private static boolean betterCandidate(int ordinalA, double scoreA, int ordinalB, double scoreB) {
        if (scoreA != scoreB) return scoreA > scoreB;
        WebRecipeIndex.RecipeView a = WebRecipeIndex.view(ordinalA);
        WebRecipeIndex.RecipeView b = WebRecipeIndex.view(ordinalB);
        boolean recyclingA = a != null && isRecycling(a);
        boolean recyclingB = b != null && isRecycling(b);
        if (recyclingA != recyclingB) return !recyclingA;
        return false;
    }

    /** 这条配方是不是「回收类」（处理器标签里带 {@code _recycling}）。 */
    private static boolean isRecycling(WebRecipeIndex.RecipeView view) {
        String handler = view.handler;
        if (handler == null || handler.isEmpty()) return false;
        return handler.toLowerCase(java.util.Locale.ROOT)
            .contains("_recycling");
    }

    /**
     * 这条配方能不能当默认候选（与「挑哪条更好」无关的那几条硬性排除）。
     *
     * <p>
     * 排掉的三类：没有物品形态材料的（等于凭空产出）、要消耗自己的、
     * 以及同名的另一个变体 1:1 换成本物品的。
     */
    private static boolean isDefaultCandidate(WebRecipeIndex.RecipeView view, int itemId) {
        if (view == null || perCraftOf(view, itemId) <= 0) return false;

        // 一个物品形态的材料都没有的配方，在这张表里就等于「凭空产出」——
        // GT 的铸造盆、只能吃流体的那些机器都是这样（配方表里只有流体，没有物品槽）。
        // 照着它规划会得出「8 个铁块不需要任何东西」这种假步骤，所以直接不算数：
        // 这种物品一律当成要玩家自己准备的材料。
        if (view.inputs.length == 0) return false;

        // 「把自己变成自己」的配方（NEI 里一大堆 1:1 转换，比如电路板 ↔ 编程电路）
        // 也不能当默认：沿着它往下走必然绕回原点，最后算出一棵「A 做 B、B 做 A」的鬼树。
        if (consumesItself(view, itemId)) return false;

        // 同名的另一个变体（不同 meta）1:1 换成本物品，也不能当默认。
        // 这类配方在数据上不是自循环（输入是另一个物品 id），但玩家看到的是一句废话：
        // 「做 64 次：藻类农场 → 藻类农场」，材料还写着「藻类农场 需要 64」。
        // GT 的多方块控制器就有这种「换个变体」的配方。
        if (swapsSameName(view, itemId)) return false;

        return true;
    }

    /** 默认候选的打分：材料种类少、手头已有的比例高、能在工作台里做。 */
    private static double scoreOf(WebRecipeIndex.RecipeView view) {
        double coverage = 0.0D;
        int slots = 0;
        for (int s = 0; s < view.inputs.length; s++) {
            WebRecipeIndex.Slot slot = view.inputs[s];
            if (slot.alts.length == 0) continue;
            slots++;
            long have = 0L;
            for (int a = 0; a < slot.alts.length; a++) {
                have += Math.max(0L, WebStore.stockOf(slot.alts[a]));
            }
            if (slot.amount > 0) coverage += Math.min(1.0D, (double) have / (double) slot.amount);
        }
        if (slots > 0) coverage /= slots;

        return 2.0D * coverage - 0.08D * view.inputs.length + (isHandCraftable(view.machine) ? 0.35D : 0.0D);
    }

    /** 这条配方是不是要消耗它自己（任意一个候选槽里出现了同一个物品）。 */
    private static boolean consumesItself(WebRecipeIndex.RecipeView view, int itemId) {
        for (int i = 0; i < view.inputs.length; i++) {
            int[] alts = view.inputs[i].alts;
            for (int a = 0; a < alts.length; a++) {
                if (alts[a] == itemId) return true;
            }
        }
        return false;
    }

    /**
     * 调试用的一行：「某某 #id 选中=机器 [处理器] 材料=a + bx2」。
     *
     * <p>
     * 只算给 {@code debug=1} 用的诊断输出，不进界面文案，所以直白就好。
     */
    private static String describeChoice(String prefix, Expansion expansion, int itemId) {
        Integer ordinal = expansion.chosen.get(Integer.valueOf(itemId));
        WebRecipeIndex.RecipeView view = ordinal == null ? null : WebRecipeIndex.view(ordinal.intValue());
        StringBuilder line = new StringBuilder(prefix).append(WebStore.nameOf(itemId))
            .append(" #")
            .append(itemId);
        if (view == null) return line.append(" 没选中任何配方")
            .toString();
        line.append(" 选中=")
            .append(view.machine)
            .append(" [")
            .append(view.handler)
            .append("] 材料=");
        for (int s = 0; s < view.inputs.length; s++) {
            if (s > 0) line.append(" + ");
            WebRecipeIndex.Slot slot = view.inputs[s];
            line.append(slot.alts.length == 0 ? "?" : WebStore.nameOf(slot.alts[0]));
            if (slot.amount > 1) {
                line.append('x')
                    .append(slot.amount);
            }
        }
        return line.toString();
    }

    /** 把一组物品 id 写成「名字、名字 等 N 种」（只列前几个，够玩家对上号就行）。 */
    private static String nameList(Set<Integer> ids) {
        StringBuilder builder = new StringBuilder();
        int n = 0;
        for (Integer id : ids) {
            if (n >= 5) break;
            if (n > 0) builder.append('、');
            String name = WebStore.nameOf(id.intValue());
            builder.append(name.isEmpty() ? ("#" + id) : name);
            n++;
        }
        if (ids.size() > n) builder.append(" 等 ")
            .append(ids.size())
            .append(" 种");
        return builder.toString();
    }

    /**
     * 这条配方的材料里，有没有哪一个是「死路」—— 它<b>每一条</b>来源都要用到本物品。
     *
     * <p>
     * 注意是「每一条」，不是「有一条」。只看一条会误伤正经配方：焊锡锭既能用合金炉
     * （锡+锑+铅）做，也能用流体固化器从熔融焊锡做 —— 有前者在，
     * 「焊锡锭 → 熔融焊锡」这条正规提取配方就不该被排掉（实测误伤过，
     * 结果规划绕成「焊锡箔 → 焊锡粉 → 提取」一大圈）。
     *
     * <p>
     * 来源多到看不完（超过 12 条）时保守放行：宁可让它试，也别把可能存在的正规链堵死。
     */
    private static boolean deadEndFor(WebRecipeIndex.RecipeView view, int itemId) {
        for (int s = 0; s < view.inputs.length; s++) {
            int[] alts = view.inputs[s].alts;
            int take = Math.min(alts.length, 4);
            for (int a = 0; a < take; a++) {
                int alt = alts[a];
                if (alt < 0 || alt == itemId) continue;
                int[] producers = WebRecipeIndex.recipesFor(alt);
                if (producers.length == 0 || producers.length > 12) continue;
                boolean allNeedTarget = true;
                for (int p = 0; p < producers.length; p++) {
                    WebRecipeIndex.RecipeView produced = WebRecipeIndex.view(producers[p]);
                    if (produced == null || !consumesItem(produced, itemId)) {
                        allNeedTarget = false;
                        break;
                    }
                }
                if (allNeedTarget) return true;
            }
        }
        return false;
    }

    /** 这条配方的材料里有没有 {@code itemId}。 */
    private static boolean consumesItem(WebRecipeIndex.RecipeView view, int itemId) {
        for (int i = 0; i < view.inputs.length; i++) {
            int[] alts = view.inputs[i].alts;
            for (int a = 0; a < alts.length; a++) {
                if (alts[a] == itemId) return true;
            }
        }
        return false;
    }

    /** 在这个物品的配方里找「稳定编号 == rid」的那一条；找不到返回 -1。 */
    private static int ordinalOfRid(int itemId, int rid) {
        int[] candidates = WebRecipeIndex.recipesFor(itemId);
        for (int i = 0; i < candidates.length; i++) {
            if (WebRecipeIndex.stableRid(candidates[i]) == rid) return candidates[i];
        }
        return -1;
    }

    /**
     * 这条配方是不是「同名的另一个变体换成本物品」（1:1 或更少）。
     *
     * <p>
     * 判据用<b>显示名相同</b>而不是物品 id —— 名字相同、id 不同的东西正是「变体」
     * （同一个多方块控制器的不同 meta 共用同一个显示名）。真正的自循环由
     * {@link #consumesItself} 负责，这里遇到就直接放行，不重复管。
     *
     * <p>
     * 为什么必须排掉：1:N 的配方是有意义的加工（一块木头出四块木板），
     * 而 1:1 同名互换在指导里等于什么都没说。
     */
    private static boolean swapsSameName(WebRecipeIndex.RecipeView view, int itemId) {
        if (perCraftOf(view, itemId) > 1) return false;

        String target = WebStore.nameOf(itemId);
        if (target.isEmpty()) return false;

        boolean sameName = false;
        for (int i = 0; i < view.inputs.length; i++) {
            int[] alts = view.inputs[i].alts;
            for (int a = 0; a < alts.length; a++) {
                if (alts[a] == itemId) return false;
                if (target.equals(WebStore.nameOf(alts[a]))) sameName = true;
            }
        }
        return sameName;
    }

    /**
     * 这条配方一次产出几个 {@code itemId}；不产出它时返回 0。
     *
     * <p>
     * 之所以不能只看 {@code view.resultId}：一条配方可能有多个产出
     * （GT++ 的多方块控制器就是「主产物槽为空、产出都摆在其它槽里」那种形态），
     * 玩家搜的是「哪个东西」，只要它在产出里，这条配方就该能用。
     */
    private static int perCraftOf(WebRecipeIndex.RecipeView view, int itemId) {
        int total = 0;
        for (int i = 0; i < view.outputs.length; i++) {
            WebRecipeIndex.Slot slot = view.outputs[i];
            for (int a = 0; a < slot.alts.length; a++) {
                if (slot.alts[a] == itemId) {
                    total += Math.max(1, slot.amount);
                    break;
                }
            }
        }
        return total;
    }

    private static boolean isUsable(int ordinal, int itemId) {
        WebRecipeIndex.RecipeView view = WebRecipeIndex.view(ordinal);
        return view != null && perCraftOf(view, itemId) > 0;
    }

    /**
     * 是不是「工作台能直接做」的配方。
     *
     * <p>
     * 判据是 NEI 那两类处理器的名字。用名字而不是「材料少不少」之类的间接指标，
     * 是因为这个判断只用来给默认配方加一点点权重 —— 玩家想用机器做，点一下就能改。
     */
    private static boolean isHandCraftable(String machine) {
        if (machine == null) return false;
        String lower = machine.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("shaped") || lower.contains("shapeless")
            || lower.contains("crafting")
            || lower.contains("工作台")
            || lower.contains("合成");
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static final int[] EMPTY_PATH = new int[0];

    private static final class Work {

        final int itemId;
        final long amount;
        /**
         * <b>单次配方</b>的用量（不是总量）。
         *
         * <p>
         * 非消耗品要按「备一份就够」算，所以需要的是这个数，而不是 {@link #amount}
         * （那个已经被合成次数乘过了）。
         */
        final long unit;
        final int depth;
        final int[] path;
        /** 把这一项推下来的那条配方（根节点是 -1）；用来连「先做谁后做谁」的边。 */
        final int parentRecipe;

        Work(int itemId, long amount, long unit, int depth, int[] path, int parentRecipe) {
            this.itemId = itemId;
            this.amount = amount;
            this.unit = unit;
            this.depth = depth;
            this.path = path;
            this.parentRecipe = parentRecipe;
        }
    }

    private static int[] push(int[] path, int itemId) {
        int[] out = new int[path.length + 1];
        System.arraycopy(path, 0, out, 0, path.length);
        out[path.length] = itemId;
        return out;
    }

    private static boolean contains(int[] path, int itemId) {
        for (int i = 0; i < path.length; i++) {
            if (path[i] == itemId) return true;
        }
        return false;
    }

    private static void add(Map<Integer, Long> map, int key, long amount) {
        Long old = map.get(key);
        map.put(key, old == null ? amount : Math.min(MAX_AMOUNT, old + amount));
    }

    private static long stock(int itemId, Map<Integer, Long> cache) {
        Long cached = cache.get(itemId);
        if (cached != null) return cached;
        long value = WebStore.stockOf(itemId);
        cache.put(itemId, value);
        return value;
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0L) return 0L;
        return (value + divisor - 1L) / divisor;
    }
}
