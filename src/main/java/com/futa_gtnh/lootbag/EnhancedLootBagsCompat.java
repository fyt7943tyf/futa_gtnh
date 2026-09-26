package com.futa_gtnh.lootbag;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import cpw.mods.fml.common.Loader;
import eu.usrv.enhancedlootbags.EnhancedLootBags;
import eu.usrv.enhancedlootbags.core.LootGroupsHandler;
import eu.usrv.enhancedlootbags.core.items.ItemLootBag;
import eu.usrv.enhancedlootbags.core.serializer.LootGroups.LootGroup;
import eu.usrv.enhancedlootbags.core.serializer.LootGroups.LootGroup.Drop;

/**
 * Enhanced LootBags 联动的桥接类 —— 本工程里唯一直接引用 {@code eu.usrv.enhancedlootbags.*}
 * 的类。和匠魂 / lootgames 联动同一个套路：所有方法都先过 {@link #isAvailable()} 这道闸，
 * 没装 ELB 时绝不进入方法体，缺失的类就不会被加载。
 *
 * <p>
 * 「自选抽奖机」要复刻的是 ELB 自己的开袋流程（{@code ItemLootBag.onItemRightClick} +
 * {@code getRandomLootItems}），并且时运走它自带的概率算法 —— 也就是
 * {@link LootGroupsHandler#getMergedGroupFromID}（垃圾组按 {@code recalcWeightByFortune}
 * 逐级减权）加上 {@code calcPercentageFromWeight}（NEI 掉率 F0/1/2/3 显示的同一套数学）。
 * 这样界面上显示的掉率和袋子真实开出来的分布完全一致。
 *
 * <p>
 * 和原版流程仅有的两处刻意差异：
 * <ul>
 * <li>限量掉落（LimitedDropCount）在<b>模拟时不记账</b>（{@code isDropAllowedForPlayer(..,
 * false)}），只在领取时记一次 —— 否则刷 3 次机会会把限量配额白白烧掉。</li>
 * <li>结果以 {@link RolledItem} 列表返回而不是直接丢在地上；领取时再走
 * {@link #giveRolledItems}。</li>
 * </ul>
 */
public final class EnhancedLootBagsCompat {

    private EnhancedLootBagsCompat() {}

    /** @return Enhanced LootBags 是否在场 */
    public static boolean isAvailable() {
        return Loader.isModLoaded("enhancedlootbags");
    }

    /** 一次模拟开袋产出的物品：堆 + 该物品在当前时运等级下的掉率（供界面悬浮显示）。 */
    public static final class RolledItem {

        public final ItemStack stack;
        /** 当前时运等级下的单次掉率（%）。负数表示算不出来（组配置被改过等）。 */
        public final double chancePercent;
        /** 来源掉落条目的 Identifier。领取时按它找回条目做限量记账；空串表示未知。 */
        public final String dropId;

        public RolledItem(ItemStack stack, double chancePercent, String dropId) {
            this.stack = stack;
            this.chancePercent = chancePercent;
            this.dropId = dropId == null ? "" : dropId;
        }
    }

    /** @return 这个堆是不是 ELB 的战利品袋 */
    public static boolean isLootBag(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemLootBag;
    }

    /** @return 这个堆是不是带时运的附魔书 */
    public static boolean isFortuneBook(ItemStack stack) {
        return stack != null && stack.getItem() == Items.enchanted_book && getFortuneLevel(stack) > 0;
    }

    /** 时运等级。附魔书走 StoredEnchantments，袋子走普通附魔，{@code getEnchantmentLevel} 都认。 */
    public static int getFortuneLevel(ItemStack stack) {
        if (stack == null) return 0;
        return EnchantmentHelper.getEnchantmentLevel(Enchantment.fortune.effectId, stack);
    }

    /**
     * 模拟开一次袋。
     *
     * @param player       用于限量掉落判定的玩家
     * @param bagMeta      袋子的 metadata（= 战利品组 ID）
     * @param fortuneLevel 生效的时运等级（袋子和书取较大值，由调用方算好）
     * @return 本次开袋的全部产物；{@code null} 表示这次随机没开出任何东西
     *         （对应原版的「try again」，此时不应消耗机会）
     */
    public static List<RolledItem> simulate(EntityPlayer player, int bagMeta, int fortuneLevel) {
        LootGroupsHandler handler = EnhancedLootBags.LootGroupHandler;
        LootGroup group = handler.getMergedGroupFromID(bagMeta, fortuneLevel);
        if (group == null) return null;

        // 组的「基准形态」用于掉率显示：calcPercentageFromWeight 收的是未合并垃圾组的原组，
        // 垃圾权重按 FortuneLevel 单独加回来 —— 和 NEI 的 F0..F3 列是同一套算法
        LootGroup baseGroup = handler.getGroupByID(bagMeta);
        LootGroupsHandler.FortuneLevel fortune = fortuneLevelOf(fortuneLevel);

        int q = group.getMinItems();
        if (group.getMaxItems() > group.getMinItems()) q = EnhancedLootBags.Rnd.nextInt(group.getMaxItems()) + 1;

        List<RolledItem> result = new ArrayList<>();
        while (q > 0) {
            List<RolledItem> batch = rollOnce(handler, player, group, baseGroup, fortune);
            if (batch.isEmpty()) return null;
            q -= batch.size();
            result.addAll(batch);
        }
        return result;
    }

    /**
     * 复刻 {@code ItemLootBag.getRandomLootItems} 的单轮抽取：按权重选条目 → 展开同组条目
     * → 限量过滤 → 生成 ItemStack（含 NBT/随机数量，超出单组上限的拆分）。
     */
    private static List<RolledItem> rollOnce(LootGroupsHandler handler, EntityPlayer player, LootGroup group,
        LootGroup baseGroup, LootGroupsHandler.FortuneLevel fortune) {

        List<RolledItem> result = new ArrayList<>();
        int runs = 0;
        Drop selected;

        do {
            selected = null;
            double rnd = EnhancedLootBags.Rnd.nextDouble() * group.getMaxWeight();
            for (Drop drop : group.getDrops()) {
                rnd -= drop.getChance();
                if (rnd <= 0.0D) {
                    selected = drop;
                    break;
                }
            }

            if (selected != null) {
                List<Drop> possibleDrops = handler.getItemGroupDrops(group, selected);
                List<Drop> pending = new ArrayList<>();
                for (Drop drop : possibleDrops) {
                    // 只判定、不记账：记账推迟到领取时
                    if (handler.isDropAllowedForPlayer(player, group, drop, false)) pending.add(drop);
                }

                for (Drop drop : pending) {
                    int amount = drop.getAmount();
                    if (drop.getIsRandomAmount()) amount = EnhancedLootBags.Rnd.nextInt(amount) + 1;

                    ItemStack stack = drop.getItemStack(amount);
                    if (stack == null) continue;

                    double chance = -1.0D;
                    if (baseGroup != null) {
                        try {
                            chance = handler.calcPercentageFromWeight(drop, baseGroup, fortune);
                        } catch (Throwable t) {
                            // 掉率只是显示用，算不出就跳过
                        }
                    }

                    while (stack.stackSize > stack.getMaxStackSize()) {
                        result.add(
                            new RolledItem(stack.splitStack(stack.getMaxStackSize()), chance, drop.getIdentifier()));
                    }
                    result.add(new RolledItem(stack, chance, drop.getIdentifier()));
                }
            }

            runs++;
        } while (result.isEmpty() && runs < 10);

        return result;
    }

    /**
     * 领取：把模拟结果真正发给玩家，并补做限量掉落记账。
     *
     * <p>
     * 记账按 {@code dropId} 在当前组里找回条目，然后走 ELB 自己的
     * {@code isDropAllowedForPlayer(.., true)} —— 从模拟到领取之间如果别的途径把
     * 限量记满了，对应物品会被跳过，语义和原版开袋一致。放不进背包的物品掉在脚下。
     */
    public static void giveRolledItems(EntityPlayer player, int bagMeta, int fortuneLevel, List<RolledItem> items) {
        if (player == null || items == null || items.isEmpty()) return;

        LootGroupsHandler handler = EnhancedLootBags.LootGroupHandler;
        LootGroup group = handler.getMergedGroupFromID(bagMeta, fortuneLevel);

        for (RolledItem item : items) {
            if (item.stack == null || item.stack.stackSize <= 0) continue;

            boolean allowed = true;
            if (group != null && !item.dropId.isEmpty()) {
                Drop drop = findDropById(group, item.dropId);
                if (drop != null) allowed = handler.isDropAllowedForPlayer(player, group, drop, true);
            }
            if (!allowed) continue;

            giveItem(player, item.stack.copy());
        }
    }

    private static Drop findDropById(LootGroup group, String dropId) {
        for (Drop drop : group.getDrops()) {
            if (dropId.equals(drop.getIdentifier())) return drop;
        }
        return null;
    }

    private static void giveItem(EntityPlayer player, ItemStack stack) {
        if (player.inventory == null) return;
        ItemStack leftover = stack.copy();
        // 先试着塞进背包（不弹物品栏界面），塞不下的掉在脚下供拾取
        player.inventory.addItemStackToInventory(leftover);
        if (leftover.stackSize <= 0) return;

        World world = player.worldObj;
        if (world == null) return;
        EntityItem entity = new EntityItem(world, player.posX, player.posY, player.posZ, leftover);
        entity.delayBeforeCanPickup = 0;
        world.spawnEntityInWorld(entity);
    }

    private static LootGroupsHandler.FortuneLevel fortuneLevelOf(int level) {
        switch (level) {
            case 1:
                return LootGroupsHandler.FortuneLevel.LV1;
            case 2:
                return LootGroupsHandler.FortuneLevel.LV2;
            case 3:
                return LootGroupsHandler.FortuneLevel.LV3;
            default:
                return LootGroupsHandler.FortuneLevel.LV0;
        }
    }
}
