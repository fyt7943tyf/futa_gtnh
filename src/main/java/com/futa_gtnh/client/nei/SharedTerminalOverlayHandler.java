package com.futa_gtnh.client.nei;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.client.ClientStorageCache;
import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import codechicken.nei.NEIClientUtils;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.DefaultOverlayHandler;
import codechicken.nei.recipe.GuiOverlayButton.ItemOverlayState;
import codechicken.nei.recipe.IRecipeHandler;
import gregtech.api.util.GTUtility;

/**
 * 终端界面的 NEI 配方转移：Shift 点 NEI 配方里的「填入合成栏」按钮，
 * 材料<b>直接从共享存储取</b>，不再需要先取到背包再摆。
 *
 * <p>
 * 基类 {@link DefaultOverlayHandler} 那套「数背包 → 模拟点击搬运」整体不用
 * （原因见服务端 {@code CraftFiller} 的类注释）——这里只做三件事：
 * <ol>
 * <li>把 NEI 的 {@link PositionedStack} 布局归一化成 3×3 合成栏的格位，
 * 连同每个位置的候选（矿物词典置换组）打包发给服务端；</li>
 * <li>「材料够不够」的绿红提示（{@link #presenceOverlay}）把
 * <b>背包 + 共享存储</b>算在一起 —— 仓库里有就算够；</li>
 * <li>NEI 的自动合成按钮（{@link #craft}）发 AUTOCRAFT 动作，
 * 由服务端填栏、取产物、补材料循环执行。</li>
 * </ol>
 */
public class SharedTerminalOverlayHandler extends DefaultOverlayHandler {

    /**
     * NEI 配方界面里材料的坐标原点是 (25, 6)（这是 NEI 给原版工作台注册 overlay 时用的
     * 参照：工作台合成栏在 (30, 17)，而它给的偏移是 (5, 11)，两者相减就是 25 / 6）。
     * 3×3 与 2×2 配方共用同一个坐标空间（{@code ShapedRecipeHandler.CachedShapedRecipe}
     * 按配方自身宽高摆材料），所以两种配方能用同一组偏移。
     *
     * <p>
     * 只影响幽灵材料指引叠层的对齐，不影响取料本身 —— 填栏走的是服务端
     * {@code CraftFiller}，那边按「第几行第几列」归一化，和绝对坐标无关。
     */
    public static final int OVERLAY_OFFSET_X = ContainerSharedTerminal.CRAFT_X + 1 - 25;
    public static final int OVERLAY_OFFSET_Y = ContainerSharedTerminal.CRAFT_Y + 1 - 6;

    /** 合成栏边长（3×3），和容器共用同一组常量。 */
    private static final int SIDE = ContainerSharedTerminal.CRAFT_SIZE;

    /** 每格最多带多少个候选去服务端（矿辞置换组偶尔很长）。 */
    private static final int MAX_CANDIDATES = 16;

    public SharedTerminalOverlayHandler() {
        super(OVERLAY_OFFSET_X, OVERLAY_OFFSET_Y);
    }

    // ==================================================================
    // 填入 / 自动合成
    // ==================================================================

    @Override
    public void overlayRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        transferRecipe(gui, recipe, recipeIndex, maxTransfer ? 0 : 1);
    }

    /**
     * 发「填合成栏」请求。返回值语义沿用基类（实际倍率），
     * 但服务端是异步执行的，这里返回的是<b>请求</b>的倍率。
     */
    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (!(gui instanceof GuiSharedTerminal)) return 0;

        NBTTagCompound layout = buildLayout(recipe, recipeIndex);
        if (layout == null) return 0;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.craft(PacketStorageAction.FILL_CRAFT_MATRIX, layout, multiplier));
        return multiplier;
    }

    /** NEI 的合成按钮：把「填栏 + 连续合成」整个交给服务端循环。 */
    @Override
    public boolean craft(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (!(gui instanceof GuiSharedTerminal)) return false;

        NBTTagCompound layout = buildLayout(recipe, recipeIndex);
        if (layout == null) return false;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.craft(PacketStorageAction.AUTOCRAFT, layout, multiplier));
        return true;
    }

    @Override
    public boolean canFillCraftingGrid(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex) {
        return true;
    }

    // ==================================================================
    // 材料是否够（绿 / 红提示）
    // ==================================================================

    /**
     * 把共享存储的存量并进「可用材料」里。
     *
     * <p>
     * 匹配粒度沿用 NEI 的 {@code NEIClientUtils.presenceOverlay}（它每个材料位
     * 只核销 1 个，多位配方同理按位算）—— 宁可提示比实际宽松一点也不把
     * 「仓库里明明有」标红。液体材料以 GT 流体显示物品的形式参加核对。
     */
    @Override
    public List<ItemOverlayState> presenceOverlay(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        List<PositionedStack> ingredients = recipe.getIngredientStacks(recipeIndex);
        List<ItemStack> available = new ArrayList<>();

        // 玩家自己背包里的（和默认实现的筛选条件一致）
        for (int i = 0; i < gui.inventorySlots.inventorySlots.size(); i++) {
            Slot slot = gui.inventorySlots.getSlot(i);
            if (slot == null || !slot.getHasStack()) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.stackSize <= 0) continue;
            if (!slot.isItemValid(stack) || !slot.canTakeStack(gui.mc.thePlayer)) continue;
            available.add(stack.copy());
        }

        // 共享存储的那一份（每种参与配方的候选都查一次存量）
        for (PositionedStack positioned : ingredients) {
            if (positioned == null || positioned.items == null) continue;
            for (ItemStack candidate : positioned.items) {
                ItemStack fromStorage = storageAvailabilityOf(candidate);
                if (fromStorage != null) {
                    available.add(fromStorage);
                }
            }
        }

        return NEIClientUtils.presenceOverlay(ingredients, available);
    }

    /** @return 代表「共享存储里有这种东西」的一个可视堆；没有时 null */
    private ItemStack storageAvailabilityOf(ItemStack candidate) {
        try {
            // GT 的流体显示物品：按流体核对（NEI 的配方材料里它代表一管流体）
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(candidate);
            if (fluid != null && fluid.getFluid() != null && fluid.amount > 0) {
                FluidKey key = FluidKey.of(fluid);
                if (key != null) {
                    long have = ClientStorageCache.getFluidAmount(fluid);
                    if (have > 0L) {
                        return GTUtility
                            .getFluidDisplayStack(key.prototype((int) Math.min(have, Integer.MAX_VALUE)), true);
                    }
                }
                return null;
            }
        } catch (Throwable ignored) {
            // 流体那条路出问题就按物品算，别让提示挂掉
        }

        long have = ClientStorageCache.getItemAmount(candidate);
        if (have <= 0L) return null;

        ItemKey key = ItemKey.of(candidate);
        if (key == null) return null;
        // 封顶一个背包能装下的量：presence 判定按位核销，给多了没意义
        return key.prototype((int) Math.min(have, 36L * 64L));
    }

    // ==================================================================
    // 布局归一化
    // ==================================================================

    /**
     * 把 NEI 的材料坐标归一化到 3×3 合成栏格位（0=左上 … 8=右下，行优先）。
     *
     * <p>
     * 不同配方处理器给的 relx/rely 基准不一样，所以不按绝对坐标算，而是
     * 「收集所有出现过的 x 和 y，各取前三个不同值」并排成 0/1/2 档：
     * 3×3（工作台）和 2×2（原版背包那种，会落在左上角 2×2 ——
     * 原版 {@code ShapedRecipes.matches} 本来就会在整个 3×3 里平移匹配，所以位置合法）
     * 都能填；行列超过三档的（某些 GT 多方块配方）直接放弃，免得摆出个错的形状。
     *
     * <p>
     * 可见性是 protected：合成站那边的 {@code StationOverlayHandler} 直接复用这一套
     * （合成站也是 3×3，归一化规则一模一样），见那个类的说明。
     */
    protected NBTTagCompound buildLayout(IRecipeHandler recipe, int recipeIndex) {
        List<PositionedStack> ingredients = recipe.getIngredientStacks(recipeIndex);
        if (ingredients == null || ingredients.isEmpty()) return null;

        // 收集 distinct 的 x / y（TreeMap 自动排序，保证「左/上」映射到 0 号位）
        TreeMap<Integer, Integer> columnRanks = new TreeMap<>();
        TreeMap<Integer, Integer> rowRanks = new TreeMap<>();
        for (PositionedStack positioned : ingredients) {
            if (positioned == null) continue;
            if (!columnRanks.containsKey(positioned.relx)) columnRanks.put(positioned.relx, 0);
            if (!rowRanks.containsKey(positioned.rely)) rowRanks.put(positioned.rely, 0);
        }
        if (columnRanks.size() > SIDE || rowRanks.size() > SIDE) return null;

        int rank = 0;
        for (int key : columnRanks.keySet()) {
            columnRanks.put(key, rank++);
        }
        rank = 0;
        for (int key : rowRanks.keySet()) {
            rowRanks.put(key, rank++);
        }

        NBTTagList slotList = new NBTTagList();
        NBTTagCompound[] perSlot = new NBTTagCompound[SIDE * SIDE];

        for (PositionedStack positioned : ingredients) {
            if (positioned == null || positioned.items == null || positioned.items.length == 0) continue;

            int index = rowRanks.get(positioned.rely) * SIDE + columnRanks.get(positioned.relx);
            if (index < 0 || index >= perSlot.length) continue;

            if (perSlot[index] == null) {
                NBTTagCompound slot = new NBTTagCompound();
                slot.setInteger("idx", index);
                slot.setInteger("count", perCraftCount(positioned));
                NBTTagList candidates = new NBTTagList();
                int added = 0;
                for (ItemStack candidate : positioned.items) {
                    if (candidate == null || added >= MAX_CANDIDATES) break;
                    ItemKey key = ItemKey.of(candidate);
                    if (key == null) continue;
                    candidates.appendTag(key.writeToNbt());
                    added++;
                }
                if (added == 0) continue;
                slot.setTag("cands", candidates);
                perSlot[index] = slot;
            } else {
                // 同一格位出现第二个材料位（理论上不该有）：取更大的单次用量
                perSlot[index]
                    .setInteger("count", Math.max(perSlot[index].getInteger("count"), perCraftCount(positioned)));
            }
        }

        for (NBTTagCompound slot : perSlot) {
            if (slot != null) {
                slotList.appendTag(slot);
            }
        }
        if (slotList.tagCount() == 0) return null;

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("slots", slotList);
        return root;
    }

    /** 这一个材料位每次合成消耗几个（NEI 把数量记在置换组的 stackSize 里）。 */
    private static int perCraftCount(PositionedStack positioned) {
        int count = positioned.item != null ? positioned.item.stackSize : 0;
        return Math.max(1, Math.min(count, 64));
    }
}
