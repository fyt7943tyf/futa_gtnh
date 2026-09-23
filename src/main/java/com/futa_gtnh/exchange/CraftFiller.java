package com.futa_gtnh.exchange;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

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
 * 候选必须是存储/背包里<b>真实存在</b>的 {@link ItemKey}（精确匹配），
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

    public static void handle(EntityPlayerMP player, ContainerSharedTerminal container, PacketStorageAction packet,
        SharedStorage storage, DeltaRecorder recorder) {
        NBTTagCompound tag = packet.getLayoutTag();
        if (tag == null) return;

        boolean autocraft = packet.getAction() == PacketStorageAction.AUTOCRAFT;
        long requested = packet.getAmount();
        int multiplier = requested <= 0L ? MAX_MULTIPLIER : (int) Math.min(requested, MAX_MULTIPLIER);

        Target[] targets = parseTargets(tag);
        if (targets == null) return;

        // 先把合成栏里现有的东西退回共享存储 —— 和手动 Shift 点合成栏同一语义，
        // 也保证「补差」逻辑面对的合成栏一定是空的
        for (int i = 0; i < CRAFT_SLOTS; i++) {
            InventoryExchange.depositFrom(player, container.getCraftMatrix(), i, 0L, storage, recorder);
        }

        fillAll(player, container, storage, recorder, targets, multiplier);

        if (autocraft) {
            int crafted = 0;
            while (crafted < multiplier) {
                // 产物放不进背包（canAcceptAll 拦住）或产物格没东西（材料断了）都返回 null
                if (container.transferCraftResult(player) == null) break;
                crafted++;
                // SlotCrafting 每次合成从每个非空格子扣 1，把消耗掉的补回来
                fillAll(player, container, storage, recorder, targets, multiplier);
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
     * 混着放等于把玩家的摆栏直接改掉。空格子按候选顺序试，第一个能凑到料的胜出
     * （候选顺序就是 NEI 给出的优先级，通常第一个就是原配方那一种）。
     */
    private static void fillAll(EntityPlayerMP player, ContainerSharedTerminal container, SharedStorage storage,
        DeltaRecorder recorder, Target[] targets, int multiplier) {
        IInventory matrix = container.getCraftMatrix();

        for (Target target : targets) {
            if (target == null) continue;

            int cap = stackLimitOf(target.candidates.get(0));
            int want = (int) Math.min((long) target.perCraft * multiplier, cap);
            if (want <= 0) continue;

            ItemStack current = matrix.getStackInSlot(target.index);
            if (current != null) {
                ItemKey currentKey = ItemKey.of(current);
                if (currentKey == null) continue;
                int need = want - current.stackSize;
                if (need <= 0) continue;
                int got = gather(player, storage, recorder, currentKey, need);
                if (got > 0) {
                    current.stackSize += got;
                    matrix.setInventorySlotContents(target.index, current);
                }
                continue;
            }

            for (ItemKey candidate : target.candidates) {
                int got = gather(player, storage, recorder, candidate, want);
                if (got > 0) {
                    matrix.setInventorySlotContents(target.index, candidate.prototype(got));
                    break;
                }
            }
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

        Target(int index, int perCraft, List<ItemKey> candidates) {
            this.index = index;
            this.perCraft = perCraft;
            this.candidates = candidates;
        }
    }

    /**
     * 解析布局 NBT（格式见 {@link PacketStorageAction#craft}）。
     *
     * @return 长度 4 的数组（下标即合成栏格位，没被布局用到的格是 null）；
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
            if (candidates.isEmpty()) continue;

            out[index] = new Target(index, perCraft, candidates);
        }
        return out;
    }
}
