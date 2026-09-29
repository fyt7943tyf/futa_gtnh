package com.futa_gtnh.exchange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

/**
 * NEI 合成联动的服务端半边：按客户端发来的布局填充终端界面的 3×3 合成栏，
 * 材料优先从玩家背包取、不够的从共享存储取；自动合成则在此基础上反复
 * 「取产物进背包 → 补材料」。
 *
 * <p>
 * <b>为什么用服务端直填而不是照抄 NEI 的 {@code DefaultOverlayHandler}。</b>
 * NEI 那套靠 {@code FastTransferManager} 模拟窗口点击搬运物品，只认玩家背包；
 * 想让它「从共享存储取料」就得把材料先取到背包里再等点击执行 —— 取料包和
 * 点击包的先后在网络上没有保证，材料晚到一个 tick，那一串点击就白点。
 * 而合成栏本来就是容器自己的真实状态（两端同步、原版语义），服务端直接写它
 * 和玩家 Shift 点击产物格走的是同一条代码路径，{@code detectAndSendChanges}
 * 会把变化同步回客户端 —— 原子、无竞态、也不需要任何本地预测。
 *
 * <p>
 * <b>安全边界</b>（这里和 {@link StorageActionHandler} 一样是信任边界）：
 * <ul>
 * <li>客户端发来的只是「意图」—— 每格想要哪种候选、每次合成用几个。
 * 普通候选必须是存储/背包里<b>真实存在</b>的 {@link ItemKey}（精确匹配）；
 * 工具候选可按 {@code craftingTool...} 矿辞匹配，但放入合成栏的仍是仓库/背包里的真实键。
 * 数量一律按实际存量封顶，倍率夹在 {@link #MAX_MULTIPLIER} 以内；</li>
 * <li>NEI 的配方置换组（矿物词典变体）由客户端展开成候选列表发来，
 * 服务端不需要（也不会）引任何 NEI 的类；</li>
 * <li>取出走 {@code SharedStorage.extractItem}（守恒），放进背包/合成栏的东西
 * 全部由 {@link ItemKey#prototype} 从键重建 —— 客户端伪造的 NBT 最多让
 * 它「找不到这种材料」，变不出任何东西。</li>
 * </ul>
 */
public final class CraftFiller {

    private CraftFiller() {}

    /** 合成栏格数。和容器共用同一组常量，改尺寸时不会漏。 */
    private static final int CRAFT_SLOTS = ContainerSharedTerminal.CRAFT_SLOTS;

    /** 单次填料时「每次合成用量」的倍率上限。一次填料最多到 64 个/格，足够堆满。 */
    private static final int MAX_MULTIPLIER = 64;

    /**
     * 自动合成的次数上限。
     *
     * <p>
     * 客户端请求量写 0 表示「材料够就一直做」（Shift 点产物格就是这个语义），
     * 这里给它一个安全上限：一次请求最多消耗这么多轮，免得一个包让服务端
     * 卡在一次点击里。真到上限了玩家再点一下就行，比卡住好。
     */
    private static final int MAX_AUTOCRAFT = 1024;

    /** 每格候选数上限。NEI 的矿辞置换组偶尔很长，超出的直接忽略。 */
    private static final int MAX_CANDIDATES = 16;

    /** 每格可接受的 craftingTool 矿辞名上限。 */
    private static final int MAX_TOOL_ORES = 16;

    /**
     * 「把产物收进玩家背包」这件事，各个容器实现不同，所以抽出来：
     * <ul>
     * <li>终端：{@code ContainerSharedTerminal.transferCraftResult}（合成栏是原版的
     * {@code InventoryCrafting} + {@code InventoryCraftResult}）；</li>
     * <li>匠魂合成站：成品槽是 {@code SlotCraftingStation}，取走产物会顺带消耗合成栏
     * （见 {@code station/StationCrafting}）。</li>
     * </ul>
     */
    public interface ResultTaker {

        /**
         * 取一次产物。
         *
         * @return 被取走的产物；没产物 / 背包放不下时返回 null（自动合成循环靠这个停不停）
         */
        ItemStack takeOnce(EntityPlayerMP player);
    }

    /**
     * 按客户端发来的布局填合成栏；{@code AUTOCRAFT} 还会接着反复
     * 「取产物进背包 → 补回消耗掉的料」。
     *
     * <p>
     * 容器/合成栏/产物收法都从外面传进来，是因为终端和匠魂合成站共用这一套逻辑：
     * 两边的合成栏都是 3×3、都要「按布局补料、材料背包优先其次共享存储」，
     * 只有「产物怎么收进背包」不一样。
     *
     * <p>
     * 两个动作对<b>已有合成栏内容</b>的态度不同，见方法体里的注释：填栏先倒空
     * （配方说了算），自动合成原样保留（玩家摆的就是配方）。
     */
    public static void handle(EntityPlayerMP player, Container container, IInventory matrix, ResultTaker resultTaker,
        PacketStorageAction packet, SharedStorage storage, DeltaRecorder recorder) {
        NBTTagCompound tag = packet.getLayoutTag();
        if (tag == null || matrix == null || resultTaker == null) return;

        boolean autocraft = packet.getAction() == PacketStorageAction.AUTOCRAFT;
        long requested = packet.getAmount();
        // 请求量 0 = 「有多少做多少」：填料的倍率照旧封顶在 64，合成次数走另一个上限
        int fillMultiplier = requested <= 0L ? MAX_MULTIPLIER : (int) Math.min(requested, MAX_MULTIPLIER);
        int craftLimit = requested <= 0L ? MAX_AUTOCRAFT : (int) Math.min(requested, MAX_AUTOCRAFT);

        Target[] targets = parseTargets(tag);
        if (targets == null) return;

        // 合成栏要不要先倒空，由布局里的 keep 决定，不看动作：
        //
        // - NEI 那条「Shift 点配方」的布局来自 NEI 的配方页，合成栏里现有的东西
        // 不一定是这个配方要的，所以先原样退回共享存储、再按布局重填；
        // - Shift 点终端自己的产物格时，布局是<b>从合成栏里读出来的</b>
        // （{@code layoutFromCraftMatrix} 会带上 keep = true）—— 玩家摆出来的
        // 那一格就是配方本身，倒空它等于自己把自己抹掉。
        //
        // 倒空这一步必须配干跑：先把玩家的东西退进仓库、再发现候选键和实物键对不上
        // （GT 的 NBT 变体、受损的工具、别的模组注册的同名配方……），格子就填不回来了
        // ——「点一下，合成栏空了、产物没了、材料也没被消耗」就是这么来的。
        boolean keepGrid = tag.getBoolean("keep");
        int unfillable;

        if (keepGrid) {
            // 玩家自己的摆法：<b>一格都不动，也不从仓库补料</b>。
            //
            // 「一次最多消耗完合成栏里现有的原料」是玩家明确要的语义：Shift 点产物格
            // 就是把手头这份料做完为止。想看它继续做，自己往格子里多放点。
            // 自动补料只属于「按 NEI 配方做」那条路 —— 那条路的布局是配方给的，
            // 材料本来就应该从仓库出。
            unfillable = 0;
        } else {
            // 干跑：把「每一格会用哪种键」先定下来，同时确认材料凑得齐。
            ItemKey[] plan = planFill(player, storage, targets, gridContents(matrix));
            unfillable = countMissing(plan, targets);
            if (unfillable > 0) {
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.craft.fill_missing", unfillable));
                reportMissingCells(player, storage, targets, plan);
                FutaGtnhMod.LOG.info(
                    "共享存储：配方{}放弃，合成栏保持原样 —— 布局 {} 格里 {} 格找不到材料（玩家 {}）；缺：{}",
                    autocraft ? "自动合成" : "填栏",
                    countTargets(targets),
                    unfillable,
                    player.getCommandSenderName(),
                    describeMissingCells(player, storage, targets, plan));
                return;
            }

            // 再试摆一遍：这一步挡住的是「材料摆进去了、原版却合不出东西」。
            // NEI 的「+」对所有配方页都开放（机器配方、熔炉配方……一样有那个按钮），
            // 我们照它给的格位把东西摆进 3×3，原版配方表不认就是白摆 ——
            // 以前的表现正是「合成栏被换掉了、产物没有、也没人告诉你为什么」。
            if (!isCraftable(container, player.worldObj, plan)) {
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.craft.not_craftable"));
                FutaGtnhMod.LOG.info(
                    "共享存储：这条配方摆进 3×3 后原版合不出东西，合成栏保持原样 —— 布局 {} 格：{}（玩家 {}）",
                    countTargets(targets),
                    describePlan(plan),
                    player.getCommandSenderName());
                return;
            }

            for (int i = 0; i < CRAFT_SLOTS; i++) {
                InventoryExchange.depositFrom(player, matrix, i, 0L, storage, recorder);
            }
            unfillable = fillAll(player, matrix, storage, recorder, targets, fillMultiplier);
        }

        if (autocraft) {
            // 反复「取产物进背包」，直到合成栏里的料用完或背包放不下
            int crafted = 0;
            while (crafted < craftLimit) {
                // 产物放不进背包（防蒸发判断拦住）或产物格没东西（材料断了）都返回 null
                if (resultTaker.takeOnce(player) == null) break;
                crafted++;
                // 合成途中<b>不</b>补料：一次点击最多消耗掉玩家摆进格子的那些
                // （见上面 keepGrid 的说明）。NEI 那条「按配方做」的除外 —— 它的料
                // 本来就该从仓库出。
                if (!keepGrid) fillAll(player, matrix, storage, recorder, targets, fillMultiplier);
            }

            // 做完了再把合成栏补回原样：原料够的话，下一次 Shift 点就能接着做，
            // 不用自己一趟趟搬料。
            //
            // 补的<b>量</b>按布局来（{@code perCraft}）：玩家摆 8 个就补回 8 个、
            // NEI 配方一格配 1 个就补回 1 个 —— 补回他刚才那个样子，而不是擅自
            // 塞满一整叠。「原料够不够」由 gather 那边按背包 + 仓库实际存量封顶。
            //
            // 只有真的做成过（crafted > 0）才补：一次都没做成说明这个摆法合不出来，
            // 这时候往格子里搬料只会让玩家更糊涂。
            if (crafted > 0) {
                fillAll(player, matrix, storage, recorder, targets, 1);
            }

            reportAutocraft(player, matrix, crafted, unfillable);
        } else {
            // 「填完合成栏里有几格」是最有用的一条凭据：玩家说「点了按钮合成栏空了」时，
            // 只有它能区分「布局本身没料可填」和「填了但没填上」。
            //
            // 后面那串「每格填了什么」是给「填进去了、产物格却是空的」这种情况用的：
            // 那时材料数量都对，问题出在<b>挑了哪一种</b>（工具格挑成锤子而不是扳手、
            // 板子挑成另一个模组的同名板），不看每格的键根本查不出来。
            FutaGtnhMod.LOG.info(
                "共享存储：按配方填栏（玩家 {}，布局 {} 格 → 合成栏现有 {} 格，{} 格缺料）；填的是：{}",
                player.getCommandSenderName(),
                countTargets(targets),
                countFilled(matrix),
                unfillable,
                describeFilledCells(matrix, targets));
        }

        // 合成栏/背包/产物格是真实槽位，全靠这一句同步回客户端。
        // 幂等，多调无害。
        container.detectAndSendChanges();
    }

    /**
     * 自动合成完了给玩家一句人话回执。
     *
     * <p>
     * 「点了没反应」是这个功能最糟的失败方式：服务端明明知道是缺料还是背包放不下，
     * 玩家却只看到一个没动静的界面。所以做成了要报数，没做成也要报<b>为什么</b>。
     */
    private static void reportAutocraft(EntityPlayerMP player, IInventory matrix, int crafted, int unfillable) {
        if (player == null) return;

        if (crafted > 0) {
            FutaGtnhMod.LOG.info("共享存储：自动合成 {} 次（玩家 {}）", crafted, player.getCommandSenderName());
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.craft.done", crafted));
            return;
        }

        if (countFilled(matrix) == 0) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.craft.no_recipe"));
            FutaGtnhMod.LOG.info("共享存储：自动合成没做成 —— 合成栏是空的（玩家 {}）", player.getCommandSenderName());
        } else if (unfillable > 0) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.craft.missing", unfillable));
            FutaGtnhMod.LOG.info("共享存储：自动合成没做成 —— 有 {} 格找不到材料（玩家 {}）", unfillable, player.getCommandSenderName());
        } else {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.craft.full"));
            FutaGtnhMod.LOG.info(
                "共享存储：自动合成没做成 —— 产物收不进背包或这个摆法合不出东西（玩家 {}）：合成栏 {}",
                player.getCommandSenderName(),
                describeGrid(matrix));
        }
    }

    /**
     * 合成栏现状的日志形态（物品 + 数量 + id/meta + 有没有 NBT）。
     *
     * <p>
     * 「摆进去了却合不出东西」只有这一行能分辨：是键挑错了（id/meta/NBT 和配方要的
     * 不一样），还是这条配方压根不属手工合成栏。
     */
    private static String describeGrid(IInventory matrix) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < CRAFT_SLOTS; i++) {
            ItemStack stack = matrix.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0) continue;

            if (out.length() > 0) out.append('，');
            out.append('[')
                .append(i)
                .append("] ")
                .append(stack.getDisplayName())
                .append(" ×")
                .append(stack.stackSize)
                .append(" id=")
                .append(net.minecraft.item.Item.getIdFromItem(stack.getItem()))
                .append('/')
                .append(stack.getItemDamage())
                .append(stack.hasTagCompound() ? " 有NBT" : " 无NBT");
        }
        return out.length() == 0 ? "（空）" : out.toString();
    }

    /** 布局里一共有几个格位（空格位不算）。 */
    private static int countTargets(Target[] targets) {
        int count = 0;
        for (Target target : targets) {
            if (target != null) count++;
        }
        return count;
    }

    /** 合成栏里现在有几格是有东西的。 */
    private static int countFilled(IInventory matrix) {
        int count = 0;
        for (int i = 0; i < CRAFT_SLOTS; i++) {
            ItemStack stack = matrix.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0) count++;
        }
        return count;
    }

    // ==================================================================
    // 填充
    // ==================================================================

    /**
     * 干跑一遍布局：给每一格挑出一个「现在真的拿得到」的键，什么都不改。
     *
     * <p>
     * 要模拟的是「倒空之后」的状态：格子里的东西会被退回仓库、再按布局重填，所以
     * 那批东西<b>仍然算可用</b>。这里踩过一个坑：原来只数仓库和背包，把合成栏里现有的
     * 整叠材料漏掉了 —— 于是「上一轮填进去的 325 块板还在格子里」时，干跑却报
     * 「Bronze Plate 可用 0」，玩家看到的是材料明明堆在眼前却说你没有。
     *
     * <p>
     * 反过来说，<b>不能</b>拿格子里的东西当「这一格已经有着落」：那批东西倒空之后会
     * 回到公共池里，由下面的候选挑选重新分配（而且可能被别的格子拿走）。所以这里
     * 只把它们加进「可用总量」，不改变「每一格要重新挑键」这件事。
     *
     * @return 长度 {@link #CRAFT_SLOTS} 的数组；拿不到料的那一格是 null
     */
    private static ItemKey[] planFill(EntityPlayerMP player, SharedStorage storage, Target[] targets,
        Map<ItemKey, Long> gridCounts) {
        ItemKey[] plan = new ItemKey[CRAFT_SLOTS];
        Map<ItemKey, Long> reserved = new HashMap<>();
        Map<String, List<ItemKey>> indexedToolKeys = null;

        for (Target target : targets) {
            if (target == null) continue;

            ItemKey key = chooseExactCandidate(player, storage, target, reserved, gridCounts);
            if (key == null && !target.toolOreNames.isEmpty()) {
                if (indexedToolKeys == null) indexedToolKeys = indexAvailableToolKeys(player, storage);
                key = chooseToolCandidate(player, storage, target, reserved, indexedToolKeys, gridCounts);
            }
            plan[target.index] = key;
        }
        return plan;
    }

    /**
     * 合成栏里现在装着什么 —— 干跑要把它算进「可用」，因为填栏会先把它退回仓库。
     *
     * <p>
     * 只收 {@code stackSize > 0} 的；数量用饱和加法累加，别让不正常的格子把总数搞溢出。
     */
    private static Map<ItemKey, Long> gridContents(IInventory matrix) {
        Map<ItemKey, Long> counts = new HashMap<>();
        if (matrix == null) return counts;
        for (int i = 0; i < matrix.getSizeInventory(); i++) {
            ItemStack stack = matrix.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0) continue;
            ItemKey key = ItemKey.of(stack);
            if (key == null) continue;
            Long old = counts.get(key);
            long merged = old == null ? stack.stackSize
                : (old > Long.MAX_VALUE - stack.stackSize ? Long.MAX_VALUE : old + stack.stackSize);
            counts.put(key, merged);
        }
        return counts;
    }

    private static int countMissing(ItemKey[] plan, Target[] targets) {
        int missing = 0;
        for (Target target : targets) {
            if (target != null && plan[target.index] == null) missing++;
        }
        return missing;
    }

    /** 聊天栏里最多列几格缺料，再多就只说一句「还有更多，见日志」。 */
    private static final int MAX_MISSING_LINES = 4;

    /**
     * 缺料时说清楚「缺的是哪一格、哪个物品、手上有几个」。
     *
     * <p>
     * 只报一个数字（「还缺 1 格材料」）等于没说 —— 玩家看到的是「材料明明有」，
     * 而缺的那一格可能是<b>另一种</b>物品，也可能只是数量差一点：四角同一种材料时，
     * 手上有 3 个、配方要 4 个，报的就是「还缺 1 格材料」。
     * 这里把每格的物品名和实际可用数量都摆出来，让这句话自己回答「到底缺什么」。
     */
    private static void reportMissingCells(EntityPlayerMP player, SharedStorage storage, Target[] targets,
        ItemKey[] plan) {
        int shown = 0;
        for (Target target : targets) {
            if (target == null || plan[target.index] != null) continue;
            if (shown++ >= MAX_MISSING_LINES) {
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.craft.fill_missing_more"));
                break;
            }
            // 物品名交给客户端翻译（服务端没有玩家的语言文件），所以传的是栈本身
            player.addChatMessage(
                new ChatComponentTranslation(
                    "futa_gtnh.msg.craft.fill_missing_detail",
                    target.index / ContainerSharedTerminal.CRAFT_SIZE + 1,
                    target.index % ContainerSharedTerminal.CRAFT_SIZE + 1,
                    missingDisplay(target),
                    missingAvailable(player, storage, target)));
        }
    }

    /** 日志形态：{@code 第1行第1列 铜板 可用 3}。 */
    private static String describeMissingCells(EntityPlayerMP player, SharedStorage storage, Target[] targets,
        ItemKey[] plan) {
        StringBuilder out = new StringBuilder();
        for (Target target : targets) {
            if (target == null || plan[target.index] != null) continue;
            if (out.length() > 0) out.append("；");
            out.append("第")
                .append(target.index / ContainerSharedTerminal.CRAFT_SIZE + 1)
                .append("行第")
                .append(target.index % ContainerSharedTerminal.CRAFT_SIZE + 1)
                .append("列 ")
                .append(nameOf(missingDisplay(target)))
                .append(" 可用 ")
                .append(missingAvailable(player, storage, target));
            List<String> nearMisses = describeNearMisses(player, storage, target);
            if (!nearMisses.isEmpty()) {
                out.append("（仓库里有像的：")
                    .append(String.join("、", nearMisses))
                    .append("）");
            }
        }
        return out.length() == 0 ? "无" : out.toString();
    }

    /**
     * 「明明有却报 0」时最有用的那条线索：仓库里有没有<b>同一个物品的另一把键</b> ——
     * NBT 变体（受损的工具、带数据的物品），或者别的模组注册的<b>同名</b>物品
     * （GT 的板和 IC2 的板都叫 Bronze Plate，却是两个物品）。
     *
     * <p>
     * 有的话就说明是候选键对不上、而不是玩家真的没料；没有的话「可用 0」就是字面意思。
     */
    private static List<String> describeNearMisses(EntityPlayerMP player, SharedStorage storage, Target target) {
        List<String> out = new ArrayList<>();
        for (ItemKey candidate : target.candidates) {
            if (availableAmount(player, storage, candidate) > 0L) continue;
            String candidateName = nameOf(safePrototype(candidate));
            for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
                if (entry.getValue() <= 0L || entry.getKey()
                    .equals(candidate)) continue;
                ItemKey stored = entry.getKey();
                boolean sameItemKind = stored.getItem() == candidate.getItem()
                    && stored.getMeta() == candidate.getMeta();
                boolean sameName = candidateName.equals(nameOf(safePrototype(stored)));
                if (!sameItemKind && !sameName) continue;

                String detail = nameOf(safePrototype(stored)) + " x"
                    + entry.getValue()
                    + (sameItemKind ? "(NBT 不同)" : "(同名物品)");
                if (!out.contains(detail)) out.add(detail);
                if (out.size() >= 4) return out;
            }
        }
        return out;
    }

    private static ItemStack safePrototype(ItemKey key) {
        try {
            return key.prototype();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 日志形态：每一格现在装的是什么 —— {@code 第1行第1列 青铜板 x64；第2行第2列 青铜扳手 x1}。
     *
     * <p>
     * 「材料数量都对、产物格却是空的」时，这条是唯一能看出<b>挑了哪一种</b>的凭据：
     * 扳手碎掉之后填料可能挑了把锤子，或者挑了另一个模组的同名板 —— 光看数量都是满的。
     */
    private static String describeFilledCells(IInventory matrix, Target[] targets) {
        StringBuilder out = new StringBuilder();
        for (Target target : targets) {
            if (target == null) continue;
            ItemStack stack = matrix.getStackInSlot(target.index);
            if (stack == null) continue;
            if (out.length() > 0) out.append("；");
            out.append("第")
                .append(target.index / ContainerSharedTerminal.CRAFT_SIZE + 1)
                .append("行第")
                .append(target.index % ContainerSharedTerminal.CRAFT_SIZE + 1)
                .append("列 ")
                .append(nameOf(stack))
                .append(" x")
                .append(stack.stackSize);
        }
        return out.length() == 0 ? "无" : out.toString();
    }

    /** 这一格首选物品的展示栈；工具格退回矿辞名 —— 拿不到名字也不会把流程打断。 */
    private static Object missingDisplay(Target target) {
        if (!target.candidates.isEmpty()) {
            try {
                ItemStack stack = target.candidates.get(0)
                    .prototype();
                if (stack != null) return stack;
            } catch (Throwable ignored) {
                // 名字拿不到就退回键名，见下
            }
            return String.valueOf(target.candidates.get(0));
        }
        return target.toolOreNames.isEmpty() ? "?" : target.toolOreNames.get(0);
    }

    /** 这一格所有候选里最富裕的那个能拿出多少 —— 回答「手上到底有几个」。 */
    private static long missingAvailable(EntityPlayerMP player, SharedStorage storage, Target target) {
        long best = 0L;
        for (ItemKey candidate : target.candidates) {
            long available = availableAmount(player, storage, candidate);
            if (available > best) best = available;
        }
        return best;
    }

    private static String nameOf(Object display) {
        if (display instanceof ItemStack) {
            try {
                return ((ItemStack) display).getDisplayName();
            } catch (Throwable ignored) {
                return String.valueOf(display);
            }
        }
        return String.valueOf(display);
    }

    /** 试摆一遍：把干跑挑出来的键放进一个临时 3×3，看原版配方表认不认。 */
    private static boolean isCraftable(Container container, net.minecraft.world.World world, ItemKey[] plan) {
        if (world == null) return true; // 拿不到世界就不拦（宁可放着，也别把功能卡死）

        InventoryCrafting trial = new InventoryCrafting(
            container,
            ContainerSharedTerminal.CRAFT_SIZE,
            ContainerSharedTerminal.CRAFT_SIZE);
        for (int i = 0; i < plan.length && i < trial.getSizeInventory(); i++) {
            if (plan[i] != null) trial.setInventorySlotContents(i, plan[i].prototype());
        }
        return CraftingManager.getInstance()
            .findMatchingRecipe(trial, world) != null;
    }

    /** 干跑结果的日志形态：{@code [0] minecraft:log@3，[4] ...}。 */
    private static String describePlan(ItemKey[] plan) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] == null) continue;
            if (out.length() > 0) out.append('，');
            out.append('[')
                .append(i)
                .append("] ")
                .append(plan[i]);
        }
        return out.length() == 0 ? "（空）" : out.toString();
    }

    /**
     * 把每一格补到「每次合成用量 × 倍率」（不超过堆叠上限）。 *
     * <p>
     * 已经有东西的格子只补差、且只补<b>同一种</b> —— 合成格一格只能放一种物品，
     * 混着放等于把玩家的摆栏直接改掉。相同候选的多个槽位会均分可用材料，避免
     * 「尽量多填」把一整叠都堆进第一格、其余配方格仍为空。
     *
     * @return 有几格<b>一点着落都没有</b>（格子里本来就是空的，背包和存储里也找不到
     *         任何可用候选）。0 表示每一格都至少填上了东西 —— 玩家回执靠它区分
     *         「缺料」和「背包放不下产物」这两种失败
     */
    private static int fillAll(EntityPlayerMP player, IInventory matrix, SharedStorage storage, DeltaRecorder recorder,
        Target[] targets, int multiplier) {
        int unfillable = 0;
        Map<ItemKey, Long> reserved = new HashMap<>();
        Map<ItemKey, List<FillRequest>> byKey = new HashMap<>();
        Map<String, List<ItemKey>> indexedToolKeys = null;
        for (Target target : targets) {
            if (target == null) continue;

            ItemStack current = matrix.getStackInSlot(target.index);
            ItemKey key;
            if (current != null) {
                key = ItemKey.of(current);
            } else {
                key = chooseExactCandidate(player, storage, target, reserved, null);
                if (key == null && !target.toolOreNames.isEmpty()) {
                    if (indexedToolKeys == null) indexedToolKeys = indexAvailableToolKeys(player, storage);
                    key = chooseToolCandidate(player, storage, target, reserved, indexedToolKeys, null);
                }
            }
            if (key == null) {
                unfillable++;
                continue;
            }

            int cap = stackLimitOf(key);
            int want = (int) Math.min((long) target.perCraft * multiplier, cap);
            if (want <= 0) continue;

            FillRequest request = new FillRequest(target, want, current == null ? 0 : current.stackSize);
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(request);
        }

        // 只取每组确实需要的数量，并按「当前堆叠数 / 单次配方用量」从低到高
        // 分配。这样同一物品用于两个槽位时，63 个原料会分成 32 + 31，而不是
        // 先给第一格 63 个、第二格留空。
        for (Map.Entry<ItemKey, List<FillRequest>> entry : byKey.entrySet()) {
            ItemKey key = entry.getKey();
            List<FillRequest> requests = entry.getValue();
            long available = availableAmount(player, storage, key);
            long remaining = Math.min(available, totalMissing(requests));

            while (remaining > 0L) {
                FillRequest leastFilled = null;
                for (FillRequest request : requests) {
                    if (request.current + request.added >= request.want) continue;
                    if (leastFilled == null || isLessFilled(request, leastFilled)) {
                        leastFilled = request;
                    }
                }
                if (leastFilled == null) break;
                leastFilled.added++;
                remaining--;
            }

            for (FillRequest request : requests) {
                if (request.added <= 0) continue;
                int got = gather(player, storage, recorder, key, request.added);
                if (got <= 0) continue;

                ItemStack stack = matrix.getStackInSlot(request.target.index);
                if (stack == null) {
                    matrix.setInventorySlotContents(request.target.index, key.prototype(got));
                } else if (key.equals(ItemKey.of(stack))) {
                    stack.stackSize += got;
                    matrix.setInventorySlotContents(request.target.index, stack);
                }
            }
        }
        return unfillable;
    }

    /**
     * 干跑时每一格记多少账：<b>1 个</b>。
     *
     * <p>
     * 原版 3×3 的每一格一次合成只消耗 1 个 —— 格里放一叠 64 只是「能做 64 次」，
     * 不是「一次要吃 64 个」。所以「够不够做出来」这个问题每一格只该记 1 个的账。
     *
     * <p>
     * 这里踩过坑：原来记的是布局里的 <b>perCraft（每格填多少，最多 64）</b>，
     * 于是同一物品占两格的配方（两块木板、两个铁锭、两根木棍……）里，
     * 第一格先把整叠记成「已占用」，第二格一看「剩的还没占用的多」就判成缺料，
     * 报「还缺 1 格材料」—— 明明仓库里有一整叠。干跑是<b>拦错</b>用的，
     * 宁可放过也别错杀：真缺料时后面的试验摆放与实际填料自己会露馅。
     */
    private static final long RESERVE_PER_CELL = 1L;

    /**
     * 按 NEI 优先级选当前可用候选；GT 工具还允许按 craftingTool 矿辞匹配实际变体。
     *
     * @param alsoCount 额外算进「可用」的数量（干跑时是合成栏里现有的那批 ——
     *                  它们会被退回仓库再重新分配，所以确实拿得到）；填料的实际
     *                  分配阶段传 null，那时该由 {@code fillAll} 自己一套账算清楚
     */
    private static ItemKey chooseExactCandidate(EntityPlayerMP player, SharedStorage storage, Target target,
        Map<ItemKey, Long> reserved, Map<ItemKey, Long> alsoCount) {
        for (ItemKey candidate : target.candidates) {
            long available = availableAmount(player, storage, candidate, alsoCount);
            long alreadyReserved = reserved.containsKey(candidate) ? reserved.get(candidate) : 0L;
            if (available <= alreadyReserved) continue;
            reserve(reserved, candidate, RESERVE_PER_CELL);
            return candidate;
        }
        return null;
    }

    private static ItemKey chooseToolCandidate(EntityPlayerMP player, SharedStorage storage, Target target,
        Map<ItemKey, Long> reserved, Map<String, List<ItemKey>> indexedToolKeys, Map<ItemKey, Long> alsoCount) {
        // 工具的 NBT 往往含材质、耐久等实例数据；原料是 craftingToolSaw 这类
        // 矿辞时，配方语义只要求工具类型相同，不要求与 NEI 展示栈的 NBT 完全一致。
        for (String oreName : target.toolOreNames) {
            List<ItemKey> keys = indexedToolKeys.get(oreName);
            if (keys == null) continue;
            for (ItemKey availableKey : keys) {
                // 碎掉的工具不再匹配配方（GT 的工具耗尽耐久后就是这种状态）：
                // 把它填进合成栏等于摆了个空壳，产物格永远不会有东西 —— 宁可跳过，
                // 让别的候选（背包里另一把好扳手）顶上。
                if (isBrokenTool(availableKey)) continue;
                long available = availableAmount(player, storage, availableKey, alsoCount);
                long alreadyReserved = reserved.containsKey(availableKey) ? reserved.get(availableKey) : 0L;
                if (available <= alreadyReserved) continue;
                reserve(reserved, availableKey, RESERVE_PER_CELL);
                return availableKey;
            }
        }
        return null;
    }

    /** 耐久耗尽（damage >= maxDamage）的工具：GT 里它已经不能再当合成工具用了。 */
    private static boolean isBrokenTool(ItemKey key) {
        try {
            ItemStack stack = key.prototype();
            if (stack == null) return false;
            int max = stack.getMaxDamage();
            return max > 0 && stack.getItemDamage() >= max;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void reserve(Map<ItemKey, Long> reserved, ItemKey key, long amount) {
        long old = reserved.containsKey(key) ? reserved.get(key) : 0L;
        reserved.put(key, old > Long.MAX_VALUE - amount ? Long.MAX_VALUE : old + amount);
    }

    private static long availableAmount(EntityPlayerMP player, SharedStorage storage, ItemKey key,
        Map<ItemKey, Long> alsoCount) {
        long total = availableAmount(player, storage, key);
        if (alsoCount != null) {
            Long extra = alsoCount.get(key);
            if (extra != null) {
                total = total > Long.MAX_VALUE - extra ? Long.MAX_VALUE : total + extra;
            }
        }
        return total;
    }

    private static long availableAmount(EntityPlayerMP player, SharedStorage storage, ItemKey key) {
        long total = storage.getItemAmount(key);
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack == null || !key.equals(ItemKey.of(stack))) continue;
            if (total > Long.MAX_VALUE - stack.stackSize) return Long.MAX_VALUE;
            total += stack.stackSize;
        }
        return total;
    }

    /** 背包里的键排前面，随后是共享存储键；为实际可用 GT 工具建立矿辞索引。 */
    private static Map<String, List<ItemKey>> indexAvailableToolKeys(EntityPlayerMP player, SharedStorage storage) {
        Map<String, List<ItemKey>> byOre = new HashMap<>();
        Set<ItemKey> seen = new HashSet<>();
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack == null || stack.stackSize <= 0) continue;
            ItemKey key = ItemKey.of(stack);
            if (key != null) indexToolKey(key, byOre, seen);
        }
        for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            if (entry.getValue() > 0L) indexToolKey(entry.getKey(), byOre, seen);
        }
        return byOre;
    }

    private static void indexToolKey(ItemKey key, Map<String, List<ItemKey>> byOre, Set<ItemKey> seen) {
        if (!seen.add(key)) return;
        try {
            for (int id : OreDictionary.getOreIDs(key.prototype())) {
                String name = OreDictionary.getOreName(id);
                if (name != null && name.startsWith("craftingTool")) {
                    byOre.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(key);
                }
            }
        } catch (Throwable ignored) {
            // 某个特殊物品的矿辞查询失败时，跳过它。
        }
    }

    private static long totalMissing(List<FillRequest> requests) {
        long total = 0L;
        for (FillRequest request : requests) {
            int missing = Math.max(0, request.want - request.current);
            total = total > Long.MAX_VALUE - missing ? Long.MAX_VALUE : total + missing;
        }
        return total;
    }

    private static boolean isLessFilled(FillRequest candidate, FillRequest current) {
        long candidateCount = (long) candidate.current + candidate.added;
        long currentCount = (long) current.current + current.added;
        return candidateCount * current.target.perCraft < currentCount * candidate.target.perCraft;
    }

    private static final class FillRequest {

        final Target target;
        final int want;
        final int current;
        int added;

        FillRequest(Target target, int want, int current) {
            this.target = target;
            this.want = want;
            this.current = current;
        }
    }

    /**
     * 凑出 {@code want} 个 {@code key}：先背包后存储。
     *
     * <p>
     * 顺序是有讲究的：背包里的先清掉，玩家看着「材料被吃进合成栏」更直觉；
     * 共享存储作为兜底，也少一次全服增量广播（背包内部搬运不产生存储增量）。
     */
    private static int gather(EntityPlayerMP player, SharedStorage storage, DeltaRecorder recorder, ItemKey key,
        int want) {
        int fromInventory = takeFromInventory(player, key, want);
        int remaining = want - fromInventory;

        long fromStorage = 0L;
        if (remaining > 0) {
            fromStorage = storage.extractItem(key, remaining);
            if (fromStorage > 0L) {
                recorder.item(key);
            }
        }
        return fromInventory + (int) fromStorage;
    }

    /** 从玩家主背包（0..35，不含护甲）按精确键取材料；取走的直接从背包扣掉。 */
    private static int takeFromInventory(EntityPlayerMP player, ItemKey key, int want) {
        ItemStack[] main = player.inventory.mainInventory;
        int taken = 0;

        for (int i = 0; i < main.length && taken < want; i++) {
            ItemStack slot = main[i];
            if (slot == null || slot.getItem() == null) continue;
            if (!key.equals(ItemKey.of(slot))) continue;

            int take = Math.min(want - taken, slot.stackSize);
            slot.stackSize -= take;
            if (slot.stackSize <= 0) {
                main[i] = null;
            }
            taken += take;
        }

        if (taken > 0) {
            player.inventory.markDirty();
        }
        return taken;
    }

    private static int stackLimitOf(ItemKey key) {
        try {
            int limit = key.prototype()
                .getMaxStackSize();
            return limit <= 0 ? 64 : limit;
        } catch (Throwable t) {
            return 64;
        }
    }

    // ==================================================================
    // 布局解析
    // ==================================================================

    /** 一格的目标：候选（按优先级）和每次合成消耗几个。 */
    private static final class Target {

        final int index;
        final int perCraft;
        final List<ItemKey> candidates;
        final List<String> toolOreNames;

        Target(int index, int perCraft, List<ItemKey> candidates, List<String> toolOreNames) {
            this.index = index;
            this.perCraft = perCraft;
            this.candidates = candidates;
            this.toolOreNames = toolOreNames;
        }
    }

    /**
     * 解析布局 NBT（格式见 {@link PacketStorageAction#craft}）。
     *
     * @return 长度 {@link #CRAFT_SLOTS} 的数组（下标即合成栏格位，没被布局用到的格是 null）；
     *         整个包不合法时返回 null，什么都不做
     */
    private static Target[] parseTargets(NBTTagCompound tag) {
        NBTTagList list = tag.getTagList("slots", 10);
        if (list == null || list.tagCount() == 0) return null;

        Target[] out = new Target[CRAFT_SLOTS];
        int entries = Math.min(list.tagCount(), CRAFT_SLOTS);

        for (int i = 0; i < entries; i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);

            int index = entry.getInteger("idx");
            if (index < 0 || index >= CRAFT_SLOTS) continue;

            int perCraft = entry.getInteger("count");
            if (perCraft < 1) perCraft = 1;
            if (perCraft > 64) perCraft = 64;

            NBTTagList candList = entry.getTagList("cands", 10);
            int candidateCount = Math.min(candList.tagCount(), MAX_CANDIDATES);
            List<ItemKey> candidates = new ArrayList<>(candidateCount);
            for (int j = 0; j < candidateCount; j++) {
                ItemKey key = ItemKey.readFromNbt(candList.getCompoundTagAt(j));
                if (key != null && !candidates.contains(key)) {
                    candidates.add(key);
                }
            }

            NBTTagList toolOreList = entry.getTagList("toolOres", 8);
            int toolOreCount = Math.min(toolOreList.tagCount(), MAX_TOOL_ORES);
            List<String> toolOreNames = new ArrayList<>(toolOreCount);
            for (int j = 0; j < toolOreCount; j++) {
                String name = toolOreList.getStringTagAt(j);
                if (name.startsWith("craftingTool") && !toolOreNames.contains(name)) {
                    toolOreNames.add(name);
                }
            }
            if (candidates.isEmpty() && toolOreNames.isEmpty()) continue;

            out[index] = new Target(index, perCraft, candidates, toolOreNames);
        }
        return out;
    }
}
