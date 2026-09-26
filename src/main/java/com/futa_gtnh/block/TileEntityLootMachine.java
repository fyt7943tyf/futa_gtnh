package com.futa_gtnh.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.lootbag.EnhancedLootBagsCompat;
import com.futa_gtnh.lootbag.EnhancedLootBagsCompat.RolledItem;
import com.futa_gtnh.lootbag.TokenWallet;

/**
 * 自选抽奖机的方块实体：两个输入槽（战利品袋 / 时运附魔书）+ 一次未领取的开袋会话。
 *
 * <p>
 * <b>会话规则</b>（都会随 NBT 持久化，关界面、下线、重启都不丢）：
 * <ul>
 * <li>每次成功 roll 记为一「轮」历史（{@link #ROLLS_PER_BAG} 轮封顶），界面里可以
 * 回看任意一轮并从中<b>选一轮领取</b>；领取消耗袋子（书若在槽里一起消耗）并清空
 * 全部历史 —— 一袋只换一轮结果。</li>
 * <li>次数用完后可以花「时运等级²」的技术员代币重置回满，可反复买；重置会清空
 * 历史 —— 买的是全新三轮。</li>
 * <li>袋子槽的内容一变（取出/换袋），会话立即作废 —— 防止对着 A 袋 roll、换 B 袋领取。</li>
 * <li>空轮（什么都没开出来）不占历史，也不消耗机会。</li>
 * <li>限量掉落的记账推迟到领取时（见 {@link EnhancedLootBagsCompat}）。</li>
 * </ul>
 *
 * <p>
 * 历史不用 {@code RolledItem} 存字段：那是 ELB 联动类型，方块实体是公共类，
 * ELB 缺席时不能让它的签名出现在字段/方法签名里。这里用自带的
 * {@link LootRound}（平行的堆/掉率/条目 ID 数组），只在实际开袋/领取的代码
 * 路径里临时转换。
 */
public class TileEntityLootMachine extends TileEntity implements IInventory {

    /** 每个袋子（每次会话）的总 roll 次数。 */
    public static final int ROLLS_PER_BAG = 3;

    public static final int SLOT_BAG = 0;
    public static final int SLOT_BOOK = 1;

    private final ItemStack[] slots = new ItemStack[2];

    /** 一轮开袋的完整记录。字段对本类私有语义公开只读。 */
    public static final class LootRound {

        public final ItemStack[] stacks;
        public final double[] chances;
        public final String[] dropIds;
        /** 该轮 roll 时的时运等级（领取时限量记账要用同一等级）。 */
        public final int fortune;
        public final int bagMeta;

        LootRound(ItemStack[] stacks, double[] chances, String[] dropIds, int fortune, int bagMeta) {
            this.stacks = stacks;
            this.chances = chances;
            this.dropIds = dropIds;
            this.fortune = fortune;
            this.bagMeta = bagMeta;
        }
    }

    /** 历史轮次，最多 {@link #ROLLS_PER_BAG} 个；空列表 = 还没开过袋。 */
    private final List<LootRound> rounds = new ArrayList<>();

    // ==================================================================
    // 输入槽变化 -> 会话作废
    // ==================================================================

    /** 袋子槽上一次的快照，用来判断「内容是不是变了」。 */
    private ItemStack lastBagSnapshot;

    /**
     * GUI 的槽位每次变动最终都会落到 {@code setInventorySlotContents} /
     * {@code decrStackSize}，在这里统一检查袋子槽。
     */
    private void onSlotMutation() {
        if (ItemStack.areItemStacksEqual(slots[SLOT_BAG], lastBagSnapshot)) return;

        lastBagSnapshot = slots[SLOT_BAG] == null ? null : slots[SLOT_BAG].copy();
        if (hasResult()) {
            clearSession();
            markDirty();
        }
    }

    public boolean hasResult() {
        return !rounds.isEmpty();
    }

    private void clearSession() {
        rounds.clear();
    }

    // ==================================================================
    // 动作（由 Container 在服务端调用，客户端永远碰不到）
    // ==================================================================

    /** @return 袋子和书当前生效的时运等级（取两者较大值） */
    public int currentFortune() {
        int fortune = EnhancedLootBagsCompat.getFortuneLevel(slots[SLOT_BOOK]);
        if (slots[SLOT_BAG] != null) {
            fortune = Math.max(fortune, EnhancedLootBagsCompat.getFortuneLevel(slots[SLOT_BAG]));
        }
        return fortune;
    }

    public int getRollsUsed() {
        return rounds.size();
    }

    /** @return 历史轮次（只读视图，按 roll 顺序） */
    public List<LootRound> getRounds() {
        return Collections.unmodifiableList(rounds);
    }

    /**
     * 「打开 / 刷新」：模拟开一次袋，作为新一轮追加进历史。
     *
     * @return true = 已出新轮（同步界面）；false = 失败原因已发给玩家
     */
    public boolean roll(EntityPlayer player) {
        if (player == null || worldObj == null || worldObj.isRemote) return false;

        if (!EnhancedLootBagsCompat.isLootBag(slots[SLOT_BAG])) {
            notify(player, "futa_gtnh.loot_machine.msg.need_bag");
            return false;
        }
        if (rounds.size() >= ROLLS_PER_BAG) {
            notify(player, "futa_gtnh.loot_machine.msg.no_chances");
            return false;
        }

        int bagMeta = slots[SLOT_BAG].getItemDamage();
        int fortune = currentFortune();

        java.util.List<RolledItem> rolled = EnhancedLootBagsCompat.simulate(player, bagMeta, fortune);
        if (rolled == null || rolled.isEmpty()) {
            // 对应原版开袋的 try_again：什么都没开出来，不消耗机会、不占历史
            notify(player, "futa_gtnh.loot_machine.msg.try_again");
            return false;
        }

        ItemStack[] stacks = new ItemStack[rolled.size()];
        double[] chances = new double[rolled.size()];
        String[] dropIds = new String[rolled.size()];
        for (int i = 0; i < rolled.size(); i++) {
            RolledItem item = rolled.get(i);
            stacks[i] = item.stack;
            chances[i] = item.chancePercent;
            dropIds[i] = item.dropId;
        }
        rounds.add(new LootRound(stacks, chances, dropIds, fortune, bagMeta));

        markDirty();
        return true;
    }

    /**
     * 「领取」：发放指定轮的结果，消耗袋子（书在槽里则一并消耗），清空全部历史。
     *
     * @param roundIndex 要领取的历史轮次下标（客户端报上来的数字，这里做权威校验）
     * @return true = 领取成功（同步界面）
     */
    public boolean claim(EntityPlayer player, int roundIndex) {
        if (player == null || worldObj == null || worldObj.isRemote) return false;

        if (roundIndex < 0 || roundIndex >= rounds.size()) {
            notify(player, "futa_gtnh.loot_machine.msg.nothing");
            return false;
        }
        if (!EnhancedLootBagsCompat.isLootBag(slots[SLOT_BAG])) {
            // 会话期间袋子被拿走了（onSlotMutation 理论上已作废会话，这里再兜一层）
            clearSession();
            markDirty();
            notify(player, "futa_gtnh.loot_machine.msg.need_bag");
            return false;
        }

        LootRound round = rounds.get(roundIndex);
        java.util.List<RolledItem> items = new ArrayList<>(round.stacks.length);
        for (int i = 0; i < round.stacks.length; i++) {
            items.add(new RolledItem(round.stacks[i], round.chances[i], round.dropIds[i]));
        }
        EnhancedLootBagsCompat.giveRolledItems(player, round.bagMeta, round.fortune, items);

        // 袋子必耗；书若在槽里一起吃掉（对齐「时运书是一次性投入」的定位）
        decrStackSize(SLOT_BAG, 1);
        if (slots[SLOT_BOOK] != null) {
            decrStackSize(SLOT_BOOK, 1);
        }

        clearSession();
        markDirty();

        worldObj.playSoundAtEntity(player, "enhancedlootbags:lootbag_open", 0.75F, 1.0F);
        notify(player, "futa_gtnh.loot_machine.msg.claimed");
        return true;
    }

    /**
     * 「重置次数」：花当前时运等级² 的技术员代币，把 roll 次数恢复满并清空历史。
     *
     * <p>
     * 费用按<b>点击那一刻</b>的时运算（无时运按 1 计，防止免费无限重置）。
     *
     * @return true = 重置成功（同步界面）
     */
    public boolean resetRolls(EntityPlayer player) {
        if (player == null || worldObj == null || worldObj.isRemote) return false;

        if (rounds.size() < ROLLS_PER_BAG) {
            notify(player, "futa_gtnh.loot_machine.msg.charges_left");
            return false;
        }

        int fortune = Math.max(1, currentFortune());
        int cost = fortune * fortune;

        if (!TokenWallet.isAvailable()) {
            notify(player, "futa_gtnh.loot_machine.msg.token_unavailable");
            return false;
        }
        if (!TokenWallet.tryCharge(player, cost)) {
            notify(player, "futa_gtnh.loot_machine.msg.insufficient", cost);
            return false;
        }

        clearSession();
        markDirty();
        notify(player, "futa_gtnh.loot_machine.msg.charged", cost);
        return true;
    }

    private void notify(EntityPlayer player, String key, Object... args) {
        player.addChatMessage(new ChatComponentTranslation(key, args));
    }

    // ==================================================================
    // IInventory（2 个输入槽）
    // ==================================================================

    @Override
    public int getSizeInventory() {
        return slots.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return slot >= 0 && slot < slots.length ? slots[slot] : null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        if (slot < 0 || slot >= slots.length || slots[slot] == null) return null;

        ItemStack split;
        if (amount >= slots[slot].stackSize) {
            split = slots[slot];
            slots[slot] = null;
        } else {
            split = slots[slot].splitStack(amount);
            if (slots[slot].stackSize <= 0) slots[slot] = null;
        }
        onSlotMutation();
        return split;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        // 方块被挖掉时由 breakBlock 负责退回物品，这里不做处理
        return null;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (slot < 0 || slot >= slots.length) return;
        slots[slot] = stack;
        if (stack != null && stack.stackSize > getInventoryStackLimit()) {
            stack.stackSize = getInventoryStackLimit();
        }
        onSlotMutation();
    }

    @Override
    public String getInventoryName() {
        return "container.futa_gtnh.loot_machine";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        if (worldObj == null || worldObj.getTileEntity(xCoord, yCoord, zCoord) != this) return false;
        return player.getDistanceSq(xCoord + 0.5D, yCoord + 0.5D, zCoord + 0.5D) <= 64.0D;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        if (slot == SLOT_BAG) return EnhancedLootBagsCompat.isLootBag(stack);
        if (slot == SLOT_BOOK) return EnhancedLootBagsCompat.isFortuneBook(stack);
        return false;
    }

    // ==================================================================
    // 持久化
    // ==================================================================

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);

        NBTTagList slotList = new NBTTagList();
        for (ItemStack stack : slots) {
            NBTTagCompound entry = new NBTTagCompound();
            if (stack != null) stack.writeToNBT(entry);
            slotList.appendTag(entry);
        }
        tag.setTag("slots", slotList);

        // 历史轮次：每轮一个 compound（时运/袋子组 + 物品列表）
        NBTTagList roundList = new NBTTagList();
        for (LootRound round : rounds) {
            NBTTagCompound roundTag = new NBTTagCompound();
            roundTag.setInteger("fortune", round.fortune);
            roundTag.setInteger("bagMeta", round.bagMeta);
            NBTTagList itemList = new NBTTagList();
            for (int i = 0; i < round.stacks.length; i++) {
                NBTTagCompound entry = new NBTTagCompound();
                if (round.stacks[i] != null) round.stacks[i].writeToNBT(entry);
                entry.setDouble("chance", round.chances[i]);
                entry.setString("dropId", round.dropIds[i] == null ? "" : round.dropIds[i]);
                itemList.appendTag(entry);
            }
            roundTag.setTag("items", itemList);
            roundList.appendTag(roundTag);
        }
        tag.setTag("rounds", roundList);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);

        NBTTagList slotList = tag.getTagList("slots", 10);
        for (int i = 0; i < slots.length && i < slotList.tagCount(); i++) {
            NBTTagCompound entry = slotList.getCompoundTagAt(i);
            slots[i] = entry.hasKey("id") ? ItemStack.loadItemStackFromNBT(entry) : null;
        }
        lastBagSnapshot = slots[SLOT_BAG] == null ? null : slots[SLOT_BAG].copy();

        rounds.clear();
        NBTTagList roundList = tag.getTagList("rounds", 10);
        int count = Math.min(roundList.tagCount(), ROLLS_PER_BAG);
        for (int r = 0; r < count; r++) {
            NBTTagCompound roundTag = roundList.getCompoundTagAt(r);
            NBTTagList itemList = roundTag.getTagList("items", 10);
            int n = itemList.tagCount();
            ItemStack[] stacks = new ItemStack[n];
            double[] chances = new double[n];
            String[] dropIds = new String[n];
            for (int i = 0; i < n; i++) {
                NBTTagCompound entry = itemList.getCompoundTagAt(i);
                stacks[i] = entry.hasKey("id") ? ItemStack.loadItemStackFromNBT(entry) : null;
                chances[i] = entry.getDouble("chance");
                dropIds[i] = entry.getString("dropId");
            }
            rounds.add(
                new LootRound(
                    stacks,
                    chances,
                    dropIds,
                    roundTag.getInteger("fortune"),
                    roundTag.getInteger("bagMeta")));
        }
    }
}
