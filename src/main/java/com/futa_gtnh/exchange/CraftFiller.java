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
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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

    /** 倍率上限（也是自动合成次数上限）。一次填料最多到 64 个/格，足够堆满。 */
    private static final int MAX_MULTIPLIER = 64;

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
     * 按客户端发来的布局填合成栏（可顺便自动合成）。
     *
     * <p>
     * 容器/合成栏/产物收法都从外面传进来，是因为终端和匠魂合成站共用这一套逻辑：
     * 两边的合成栏都是 3×3、都要「先倒空再按布局补料、材料背包优先其次共享存储」，
     * 只有「产物怎么收进背包」不一样。
     */
    public static void handle(EntityPlayerMP player, Container container, IInventory matrix, ResultTaker resultTaker,
        PacketStorageAction packet, SharedStorage storage, DeltaRecorder recorder) {
        NBTTagCompound tag = packet.getLayoutTag();
        if (tag == null || matrix == null || resultTaker == null) return;

        boolean autocraft = packet.getAction() == PacketStorageAction.AUTOCRAFT;
        long requested = packet.getAmount();
        int multiplier = requested <= 0L ? MAX_MULTIPLIER : (int) Math.min(requested, MAX_MULTIPLIER);

        Target[] targets = parseTargets(tag);
        if (targets == null) return;

        // 先把合成栏里现有的东西退回共享存储 —— 和手动 Shift 点合成栏同一语义，
        // 也保证「补差」逻辑面对的合成栏一定是空的
        for (int i = 0; i < CRAFT_SLOTS; i++) {
            InventoryExchange.depositFrom(player, matrix, i, 0L, storage, recorder);
        }

        fillAll(player, matrix, storage, recorder, targets, multiplier);

        if (autocraft) {
            int crafted = 0;
            while (crafted < multiplier) {
                // 产物放不进背包（防蒸发判断拦住）或产物格没东西（材料断了）都返回 null
                if (resultTaker.takeOnce(player) == null) break;
                crafted++;
                // 每次合成从每个非空格子扣 1，把消耗掉的补回来
                fillAll(player, matrix, storage, recorder, targets, multiplier);
            }
            if (FutaGtnhMod.LOG.isDebugEnabled() && crafted > 0) {
                FutaGtnhMod.LOG.debug("共享存储：自动合成 {} 次（玩家 {}）", crafted, player.getCommandSenderName());
            }
        }

        // 合成栏/背包/产物格是真实槽位，全靠这一句同步回客户端。
        // 幂等，多调无害。
        container.detectAndSendChanges();
    }

    // ==================================================================
    // 填充
    // ==================================================================

    /**
     * 把每一格补到「每次合成用量 × 倍率」（不超过堆叠上限）。
     *
     * <p>
     * 已经有东西的格子只补差、且只补<b>同一种</b> —— 合成格一格只能放一种物品，
     * 混着放等于把玩家的摆栏直接改掉。相同候选的多个槽位会均分可用材料，避免
     * 「尽量多填」把一整叠都堆进第一格、其余配方格仍为空。
     */
    private static void fillAll(EntityPlayerMP player, IInventory matrix, SharedStorage storage, DeltaRecorder recorder,
        Target[] targets, int multiplier) {
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
                key = chooseExactCandidate(player, storage, target, reserved);
                if (key == null && !target.toolOreNames.isEmpty()) {
                    if (indexedToolKeys == null) indexedToolKeys = indexAvailableToolKeys(player, storage);
                    key = chooseToolCandidate(player, storage, target, reserved, indexedToolKeys);
                }
            }
            if (key == null) continue;

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
    }

    /** 按 NEI 优先级选当前可用候选；GT 工具还允许按 craftingTool 矿辞匹配实际变体。 */
    private static ItemKey chooseExactCandidate(EntityPlayerMP player, SharedStorage storage, Target target,
        Map<ItemKey, Long> reserved) {
        long reserveAmount = Math.max(1, target.perCraft);
        for (ItemKey candidate : target.candidates) {
            long available = availableAmount(player, storage, candidate);
            long alreadyReserved = reserved.containsKey(candidate) ? reserved.get(candidate) : 0L;
            if (available <= alreadyReserved) continue;
            reserve(reserved, candidate, reserveAmount);
            return candidate;
        }
        return null;
    }

    private static ItemKey chooseToolCandidate(EntityPlayerMP player, SharedStorage storage, Target target,
        Map<ItemKey, Long> reserved, Map<String, List<ItemKey>> indexedToolKeys) {
        long reserveAmount = Math.max(1, target.perCraft);
        // 工具的 NBT 往往含材质、耐久等实例数据；原料是 craftingToolSaw 这类
        // 矿辞时，配方语义只要求工具类型相同，不要求与 NEI 展示栈的 NBT 完全一致。
        for (String oreName : target.toolOreNames) {
            List<ItemKey> keys = indexedToolKeys.get(oreName);
            if (keys == null) continue;
            for (ItemKey availableKey : keys) {
                long available = availableAmount(player, storage, availableKey);
                long alreadyReserved = reserved.containsKey(availableKey) ? reserved.get(availableKey) : 0L;
                if (available <= alreadyReserved) continue;
                reserve(reserved, availableKey, reserveAmount);
                return availableKey;
            }
        }
        return null;
    }

    private static void reserve(Map<ItemKey, Long> reserved, ItemKey key, long amount) {
        long old = reserved.containsKey(key) ? reserved.get(key) : 0L;
        reserved.put(key, old > Long.MAX_VALUE - amount ? Long.MAX_VALUE : old + amount);
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
