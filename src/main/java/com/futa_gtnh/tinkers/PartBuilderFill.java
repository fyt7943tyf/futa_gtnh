package com.futa_gtnh.tinkers;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

import tconstruct.library.crafting.PatternBuilder;
import tconstruct.tools.inventory.PartCrafterContainer;

/**
 * 部件加工台（匠魂里叫 PartCrafter）的自动补料。
 *
 * <p>
 * <b>槽位</b>（见 {@code PartCrafterContainer} 与 {@code PartBuilderLogic}）：
 * 
 * <pre>
 *   0 = 图纸（上）   2 = 材料（上）   → 产物进 4 / 5
 *   1 = 图纸（下）   3 = 材料（下）   → 产物进 5 之后的格子
 * </pre>
 * 
 * 两次 {@code buildTopPart / buildBottomPart} 分别用 {@code getToolPart(材料, 图纸,
 * 另一张图纸)} 判定 —— 也就是说<b>图纸就是意图</b>：你把「镐头图纸」放进去，
 * 我们就知道要造的是镐头部件，缺的只是「用哪种材料」。
 *
 * <p>
 * <b>材料按你说的规则挑</b>：
 * <ol>
 * <li>同一个加工台里另一套（另一张图纸+材料）已经放了材料 → 用<b>同一种</b>材料
 * （那是玩家刚表达过的偏好）；</li>
 * <li>否则挑共享存储里<b>存量最多</b>的那种「这张图纸能用的材料」。</li>
 * </ol>
 * 「能不能用」不靠猜：直接调匠魂自己的 {@code PatternBuilder.getToolPart(...)}，
 * 只不过喂进去的是<b>拷贝</b>（那个方法可能改传入的栈，用拷贝就不会动到真东西）。
 *
 * <p>
 * 材料补进去之后同样由匠魂自己合成：{@code PartBuilderLogic.setInventorySlotContents}
 * 里会调 {@code buildTopPart / buildBottomPart}。
 */
final class PartBuilderFill {

    /** 两套 (图纸, 材料)：图纸在 0/1，材料在 2/3。 */
    private static final int PAIRS = 2;
    private static final int MATERIAL_OFFSET = 2;

    private PartBuilderFill() {}

    static void tick(EntityPlayerMP player, PartCrafterContainer container, SharedStorage storage,
        DeltaRecorder recorder) {
        if (container.inventorySlots.isEmpty()) return;

        // PartCrafterContainer.logic 是 protected，跨包拿不到；走槽位反查即可 ——
        // 这些槽位包的库存就是那个 PartBuilderLogic
        IInventory logic = container.getSlot(0).inventory;
        if (logic == null) return;

        for (int pair = 0; pair < PAIRS; pair++) {
            ItemStack pattern = logic.getStackInSlot(pair);
            if (pattern == null) continue; // 没图纸 → 不知道你要做什么部件，不猜

            int materialSlot = pair + MATERIAL_OFFSET;
            if (logic.getStackInSlot(materialSlot) != null) continue; // 已经有材料（可能就是刚补的）

            fill(player, logic, pair, materialSlot, pattern, storage, recorder);
        }
    }

    private static void fill(EntityPlayerMP player, IInventory logic, int patternSlot, int materialSlot,
        ItemStack pattern, SharedStorage storage, DeltaRecorder recorder) {
        // 另一张图纸是 getToolPart 的第三个参数（有些部件要两张图纸一起看）
        ItemStack otherPattern = logic.getStackInSlot(patternSlot == 0 ? 1 : 0);
        // 另一套已经放了的材料：优先跟它一致
        ItemStack siblingMaterial = logic.getStackInSlot(patternSlot == 0 ? 3 : 2);
        ItemKey siblingKey = siblingMaterial == null ? null : ItemKey.of(siblingMaterial);

        String patternName = pattern.getItem() == null ? "?"
            : pattern.getItem()
                .getUnlocalizedName();
        Object cacheKey = "partbuilder:" + patternName
            + ':'
            + pattern.getItemDamage()
            + ':'
            + (siblingKey == null ? "-" : siblingKey.toString());

        ItemKey key = FillUtil.pick(
            storage,
            cacheKey,
            k -> true, // 材料可能是锭/板/宝石/块……没法只看键过滤，交给匠魂判定
            prototype -> score(prototype, pattern, otherPattern, siblingKey),
            false);
        if (key == null) return;

        ItemStack got = FillUtil.take(player, storage, recorder, key, 1);
        if (got == null) {
            FillUtil.invalidate(cacheKey);
            return;
        }

        // ★ 这一句会触发匠魂自己 buildTopPart / buildBottomPart（见类注释）
        logic.setInventorySlotContents(materialSlot, got);
    }

    private static int score(ItemStack prototype, ItemStack pattern, ItemStack otherPattern, ItemKey siblingKey) {
        if (!isUsableMaterial(prototype, pattern, otherPattern)) return -1;
        // 能用；如果和另一套已经放着的材料是同一种，优先（那是玩家刚表达过的偏好）
        if (siblingKey != null && siblingKey.equals(ItemKey.of(prototype))) return 5;
        return 1;
    }

    /** 问匠魂：这种材料配这张图纸能不能出部件。喂拷贝，避免它改到真东西。 */
    private static boolean isUsableMaterial(ItemStack material, ItemStack pattern, ItemStack otherPattern) {
        try {
            ItemStack[] result = PatternBuilder.instance
                .getToolPart(material.copy(), pattern.copy(), otherPattern == null ? null : otherPattern.copy());
            return result != null && result.length > 0 && result[0] != null;
        } catch (Throwable t) {
            // 匠魂内部对奇怪 NBT 抛异常时当作「不能用」，不能让一格材料把整个心跳打挂
            return false;
        }
    }
}
