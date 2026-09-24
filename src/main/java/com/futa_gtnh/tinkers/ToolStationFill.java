package com.futa_gtnh.tinkers;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

import tconstruct.library.crafting.ToolBuilder;
import tconstruct.library.crafting.ToolRecipe;
import tconstruct.tools.inventory.ToolForgeContainer;
import tconstruct.tools.inventory.ToolStationContainer;
import tconstruct.tools.items.ToolPart;
import tconstruct.tools.logic.ToolStationLogic;

/**
 * 工匠工作站 / 工匠锻造台的自动补料。
 *
 * <p>
 * <b>槽位</b>（见 {@code ToolStationContainer.initializeContainer}）：0 号是产物格，
 * 1/2/3 是<b>头 / 柄 / 附件</b>三个部件槽；锻造台（{@code ToolForgeContainer}）多一个
 * 4 号「额外」槽，用来造锤子那类大件。
 *
 * <p>
 * <b>怎么知道你要造什么</b>：只能从你已经放进去的部件推。做法是把
 * {@code ToolBuilder.instance.recipeList} 里所有配方过一遍，
 * 留下「已放部件在各自位置上都被这个配方认可」的那些 ——
 * <b>只有恰好剩一个配方时才动手</b>。剩两个以上说明意图不明确（比如你只放了一根
 * 工具手柄，那几乎所有工具都成立），这时候补料就是在替你猜工具类型，而匠魂的部件
 * 是讲材质的，猜错会把好材料白白吃掉，所以宁可不动。
 *
 * <p>
 * <b>材质按你说的规则挑</b>：优先和你已经放进去的那个部件<b>同材质</b>
 * （比较 {@code ToolBuilder.getMaterialID}），都不匹配时退回「共享存储里存量最多的
 * 那一种合格部件」。
 *
 * <p>
 * 补料只写工作站的库存，<b>合成交给匠魂自己</b>：{@code ToolStationLogic
 * .setInventorySlotContents} 里就会试一次 {@code buildTool}，所以缺的那个部件一补进去，
 * 工具当场就出来了。同理，我们不主动反复调 {@code buildTool}（产物格满了它也不会重复消耗，
 * 但没必要每 tick 去戳它）。
 */
final class ToolStationFill {

    /** 工作站：头 / 柄 / 附件。 */
    private static final int STATION_PARTS = 3;

    /** 锻造台：多一个「额外」槽（锤子那类大件）。 */
    private static final int FORGE_PARTS = 4;

    private ToolStationFill() {}

    static void tick(EntityPlayerMP player, ToolStationContainer container, SharedStorage storage,
        DeltaRecorder recorder) {
        ToolStationLogic logic = container.logic;
        if (logic == null) return;

        int partSlots = container instanceof ToolForgeContainer ? FORGE_PARTS : STATION_PARTS;

        ItemStack[] parts = new ItemStack[partSlots];
        boolean any = false;
        for (int i = 0; i < partSlots; i++) {
            parts[i] = logic.getStackInSlot(i + 1);
            if (parts[i] != null) any = true;
        }
        // 一个部件都没放：不知道你要造什么工具，什么都不做（见类注释）
        if (!any) return;

        ToolRecipe recipe = uniqueRecipe(parts);
        if (recipe == null) return;

        int preferred = preferredMaterial(parts);
        for (int i = 0; i < partSlots; i++) {
            if (parts[i] != null) continue;
            fillPart(player, logic, i + 1, recipe, i, preferred, storage, recorder);
        }
    }

    /** @return 与已放部件相符的配方；没有或者不唯一时返回 null（表示「不猜」）。 */
    private static ToolRecipe uniqueRecipe(ItemStack[] parts) {
        ToolRecipe found = null;
        for (ToolRecipe recipe : ToolBuilder.instance.recipeList.values()) {
            if (!matches(recipe, parts)) continue;
            if (found != null) return null; // 有歧义
            found = recipe;
        }
        return found;
    }

    /** 已放下的部件是否都落在各自位置上被这个配方认可（空槽不参与判断）。 */
    private static boolean matches(ToolRecipe recipe, ItemStack[] parts) {
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null) continue;
            if (!validAt(recipe, i, parts[i])) return false;
        }
        return true;
    }

    private static boolean validAt(ToolRecipe recipe, int position, ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        switch (position) {
            case 0:
                return recipe.validHead(stack.getItem());
            case 1:
                return recipe.validHandle(stack.getItem());
            case 2:
                return recipe.validAccessory(stack.getItem());
            default:
                return recipe.validExtra(stack.getItem());
        }
    }

    /** 已放部件里第一个能认出材质的那个的材质 ID；认不出来返回 -1。 */
    private static int preferredMaterial(ItemStack[] parts) {
        for (ItemStack part : parts) {
            if (part == null) continue;
            int id = ToolBuilder.instance.getMaterialID(part);
            if (id >= 0) return id;
        }
        return -1;
    }

    private static void fillPart(EntityPlayerMP player, ToolStationLogic logic, int slot, ToolRecipe recipe,
        int position, int preferredMaterial, SharedStorage storage, DeltaRecorder recorder) {
        String tool = recipe.getType() == null ? "?"
            : recipe.getType()
                .getUnlocalizedName();
        Object cacheKey = "toolstation:" + tool + ':' + position + ':' + preferredMaterial;

        ItemKey key = FillUtil.pick(
            storage,
            cacheKey,
            k -> k.getItem() instanceof ToolPart,
            prototype -> score(recipe, position, preferredMaterial, prototype),
            false);
        if (key == null) return;

        ItemStack got = FillUtil.take(player, storage, recorder, key, 1);
        if (got == null) {
            // 正好被别的玩家拿光了：丢掉缓存，下一 tick 重新挑
            FillUtil.invalidate(cacheKey);
            return;
        }

        // ★ 这一句会触发匠魂自己 buildTool（见类注释）
        logic.setInventorySlotContents(slot, got);
    }

    private static int score(ToolRecipe recipe, int position, int preferredMaterial, ItemStack prototype) {
        if (!validAt(recipe, position, prototype)) return -1;
        int material = ToolBuilder.instance.getMaterialID(prototype);
        if (preferredMaterial >= 0 && material == preferredMaterial) return 10; // 同材质优先
        return material >= 0 ? 1 : 0;
    }
}
