package com.futa_gtnh.client.nei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.client.ClientStorageCache;
import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.client.StorageViewEntry;
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

    /** 每格最多带多少个 craftingTool 矿辞名。 */
    private static final int MAX_TOOL_ORES = 16;

    /** 全部共享存储中可用 craftingTool 矿辞的缓存；按库存修订号失效。 */
    private static int toolOreIndexRevision = Integer.MIN_VALUE;
    private static Set<String> availableStorageToolOres = Collections.emptySet();

    public SharedTerminalOverlayHandler() {
        super(OVERLAY_OFFSET_X, OVERLAY_OFFSET_Y);
    }

    // ==================================================================
    // 填入 / 自动合成
    // ==================================================================

    /**
     * NEI 的「填入合成栏」手势：<b>只把配方摆进合成栏，绝不动手合成。</b>
     *
     * <p>
     * 摆料和开做分成两步是刻意的：
     * <ol>
     * <li>「+」（按住 Shift 点）＝ 把这条配方从共享存储摆进 3×3，玩家先看清配方对不对；</li>
     * <li>在终端合成栏的<b>产物格上按 Shift</b> ＝ 才开始一直做下去
     * （{@code GuiSharedTerminal#handleCraftResultClick} → 服务端 {@code CraftFiller}）。</li>
     * </ol>
     * 点一下「+」就把材料一路做成成品、连配方长什么样都没看见，那不是
     * 「填入合成栏」该有的语义。
     *
     * <p>
     * NEI 给这个按钮定的规则是「按住 Shift 点才真的填」
     * （{@code GuiOverlayButton.requireShiftForOverlayRecipe()}）：不按 Shift 的点击
     * 只画一层幽灵材料，压根走不到我们这儿。真正填料时
     * {@code GuiOverlayButton#overlayRecipe} 传给 {@code fillCraftingGrid} 的倍率是
     * <b>写死的 0</b>，{@code fillCraftingGrid} 再按「{@code multiplier != 1}」算出
     * {@code maxTransfer} —— 所以 Shift 点过来永远是 {@code maxTransfer = true}；
     * 倍率 0 在这里只表示「尽量多填」（服务端按每格 64 封顶），不是「开做」。
     */
    @Override
    public void overlayRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        transferRecipe(gui, recipe, recipeIndex, maxTransfer ? 0 : 1);
    }

    /**
     * 发「填合成栏」请求。返回值语义沿用基类（实际倍率），
     * 但服务端是异步执行的，这里返回的是<b>请求</b>的倍率。
     *
     * <p>
     * <b>不管倍率是多少都只填栏。</b>NEI 自己的自动合成按钮走的是另一条路
     * （{@link #craft}，整个 NEI 里只有 {@code AutoCraftingManager} 会调它），
     * 玩家点「+」永远不该直接开做。
     */
    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (!(gui instanceof GuiSharedTerminal)) return 0;

        NBTTagCompound layout = buildLayout(recipe, recipeIndex);
        if (layout == null) return 0;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.craft(PacketStorageAction.FILL_CRAFT_MATRIX, layout, multiplier));

        // 「点了一下没反应」这种反馈只能靠日志分辨：这条能说清是哪条手势、要几格
        com.futa_gtnh.FutaGtnhMod.LOG.info(
            "共享存储：NEI 「填入合成栏」—— 只填栏（倍率 {}，布局 {} 格）",
            multiplier,
            layout.getTagList("slots", 10)
                .tagCount());
        return multiplier;
    }

    /**
     * NEI 的自动合成入口（{@code AutoCraftingManager} 调的）：请求服务端执行当前一步。
     *
     * <p>
     * 和「+」的区别就在这里 —— 这个是玩家明确要求「做」，那个只是「摆」。
     */
    @Override
    public boolean craft(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (!(gui instanceof GuiSharedTerminal)) return false;

        NBTTagCompound layout = buildLayout(recipe, recipeIndex);
        if (layout == null) return false;

        return NeiCraftStep.request(layout, multiplier);
    }

    /**
     * <b>不经过 NEI 的界面</b>，直接对着一个产物填合成栏 —— 终端界面里 Ctrl+左键点存储面板条目走的就是这里。
     *
     * <p>
     * 为什么需要它：NEI 那个「+」按钮在我们这个界面上永远显示 Mismatch Crafting Grid
     * （它判断「这个界面有没有 overlay」用的是 {@code RecipeInfo.hasOverlayHandler(gui, "crafting")}，
     * 我们注册了同样的键也取不到，埋点证明它压根没把问题问到我们的处理器上）。与其继续在 NEI
     * 的注册侧碰运气，不如自己拿配方。
     *
     * <p>
     * 实现上刻意<b>复用 NEI 的配方读取</b>：{@code ShapedRecipeHandler}/{@code ShapelessRecipeHandler}
     * 的缓存是按产物加载的（{@code loadCraftingRecipes("item", 产物)}），加载完索引 0 就是我们要的配方；
     * 拿到之后走的是和「+」完全相同的打包（{@link #buildLayout}）与发包路径 ——
     * 服务端 {@code CraftFiller} 那边一行都不用改。
     *
     * <p>
     * 之所以要 NEI 在场：配方读取用的是它的处理器（它顺带把矿辞、GT 的工具矿辞都归一化好了，
     * 这部分自己写一遍既长又容易漏）。NEI 不在时这个方法不会被调用（调用点已经判过）。
     *
     * @param output 要合成的产物（面板里点的那一条）
     * @return 是否已经把「填栏」请求发出去
     */
    public boolean fillCraftingGridFor(ItemStack output) {
        if (output == null) return false;

        codechicken.nei.recipe.TemplateRecipeHandler[] handlers = { new codechicken.nei.recipe.ShapedRecipeHandler(),
            new codechicken.nei.recipe.ShapelessRecipeHandler() };

        // 同一个产物 NEI 往往给出好几条配方（有序/无序、不同矿辞置换、不同模组各注册一份），
        // 所以不能无脑取第一个：优先挑「材料在共享存储里凑得齐」的那一条，
        // 都不齐时才退回第一个能打包的（让服务端那边去报缺料，而不是干脆没反应）。
        NBTTagCompound fallback = null;
        for (codechicken.nei.recipe.TemplateRecipeHandler handler : handlers) {
            handler.loadCraftingRecipes("item", output);
            for (int index = 0; index < handler.arecipes.size(); index++) {
                if (!materialsInStorage(handler, index)) continue;
                NBTTagCompound layout = buildLayout(handler, index);
                if (layout == null) continue;
                fallback = layout;
                break;
            }
            if (fallback != null) break;
        }

        if (fallback == null) {
            for (codechicken.nei.recipe.TemplateRecipeHandler handler : handlers) {
                if (handler.arecipes.isEmpty()) continue;
                fallback = buildLayout(handler, 0);
                if (fallback != null) break;
            }
        }
        if (fallback == null) return false;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.craft(PacketStorageAction.FILL_CRAFT_MATRIX, fallback, 1));
        return true;
    }

    /**
     * 这条配方要的材料，共享存储凑得齐吗。
     *
     * <p>
     * 只核共享存储，不管玩家背包 —— 这条路本来就是「从仓库取料」，
     * 判定口径和 {@code presenceOverlay}（绿/红提示）保持一致：每个材料位只要有
     * 任意一个候选（含 GT 的 craftingTool 矿辞工具）在库里就算齐。
     */
    private boolean materialsInStorage(IRecipeHandler recipe, int recipeIndex) {
        List<PositionedStack> ingredients = recipe.getIngredientStacks(recipeIndex);
        if (ingredients == null || ingredients.isEmpty()) return false;

        for (PositionedStack positioned : ingredients) {
            if (positioned == null || positioned.items == null) continue;

            boolean satisfied = false;
            for (ItemStack candidate : positioned.items) {
                if (candidate == null) continue;
                if (storageAvailabilityOf(candidate) != null || hasStoredCraftingTool(candidate)) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) return false;
        }
        return true;
    }

    /**
     * 我们从共享存储取料，不走 NEI 的点击模拟，所以「能不能填」永远是能。
     *
     * <p>
     * （NEI 那边最终没把问题问到这儿来 —— 「+」显示 Mismatch Crafting Grid 是因为
     * {@code RecipeInfo.hasOverlayHandler} 取不到我们注册的处理器，与这里的返回值无关。见 README。）
     */
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

        // 共享存储的那一份（每种参与配方的候选都查一次存量；不依赖合成站当前显示页）
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
        if (have > 0L) {
            ItemKey key = ItemKey.of(candidate);
            if (key == null) return null;
            // 封顶一个背包能装下的量：presence 判定按位核销，给多了没意义
            return key.prototype((int) Math.min(have, 36L * 64L));
        }

        // 锻造锤等 GT 工具的耐久属于实例状态：配方展示栈和仓库里的受损工具
        // ItemKey 不同，但 craftingToolHammer 等矿辞仍表示同一种配方工具。
        // 返回配方候选本身，让 NEI 用精确物品比较时也能显示为「有」。
        return hasStoredCraftingTool(candidate) ? candidate.copy() : null;
    }

    private static boolean hasStoredCraftingTool(ItemStack candidate) {
        try {
            for (int id : OreDictionary.getOreIDs(candidate)) {
                String name = OreDictionary.getOreName(id);
                if (name != null && name.startsWith("craftingTool") && availableStorageToolOres().contains(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 某个模组物品的矿辞查询失败时，保留普通的精确匹配结果。
        }
        return false;
    }

    private static Set<String> availableStorageToolOres() {
        int revision = ClientStorageCache.getRevision();
        if (toolOreIndexRevision == revision) return availableStorageToolOres;

        Set<String> names = new HashSet<>();
        if (ClientStorageCache.isReady()) {
            for (StorageViewEntry entry : ClientStorageCache.items()) {
                if (entry == null || entry.getAmount() <= 0L || entry.getItemKey() == null) continue;
                try {
                    for (int id : OreDictionary.getOreIDs(
                        entry.getItemKey()
                            .prototype())) {
                        String name = OreDictionary.getOreName(id);
                        if (name != null && name.startsWith("craftingTool")) names.add(name);
                    }
                } catch (Throwable ignored) {
                    // 个别条目矿辞查询失败不应影响其它工具的提示。
                }
            }
        }

        availableStorageToolOres = names;
        toolOreIndexRevision = revision;
        return availableStorageToolOres;
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
                List<ItemKey> candidateKeys = new ArrayList<>();
                List<String> toolOreNames = new ArrayList<>();
                for (ItemStack candidate : positioned.items) {
                    if (candidate == null) continue;
                    ItemKey key = ItemKey.of(candidate);
                    if (key == null) continue;

                    // GT 的 craftingToolSaw 等矿辞可能包含很多材料/NBT 变体，
                    // 不能只把最前面的少数候选发给服务端。候选列表仍有限长，
                    // 但工具矿辞会完整记录，服务端可按矿辞匹配实际存放的工具。
                    collectCraftingToolOreNames(candidate, toolOreNames);

                    if (candidateKeys.size() < MAX_CANDIDATES && !candidateKeys.contains(key)) {
                        candidateKeys.add(key);
                    }
                }
                if (candidateKeys.isEmpty() && toolOreNames.isEmpty()) continue;
                for (ItemKey key : candidateKeys) {
                    candidates.appendTag(key.writeToNbt());
                }
                slot.setTag("cands", candidates);
                if (!toolOreNames.isEmpty()) {
                    NBTTagList toolOres = new NBTTagList();
                    for (String oreName : toolOreNames) {
                        toolOres.appendTag(new NBTTagString(oreName));
                    }
                    slot.setTag("toolOres", toolOres);
                }
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

    /** 把 GT / Forge craftingTool 矿辞名附在槽位上，供服务端匹配工具的实际 NBT 变体。 */
    private static void collectCraftingToolOreNames(ItemStack stack, List<String> output) {
        try {
            for (int id : OreDictionary.getOreIDs(stack)) {
                String name = OreDictionary.getOreName(id);
                if (name != null && name.startsWith("craftingTool") && !output.contains(name)) {
                    if (output.size() >= MAX_TOOL_ORES) continue;
                    output.add(name);
                }
            }
        } catch (Throwable ignored) {
            // 某个模组物品的矿辞查询失败时，仍保留精确候选，不影响其它配方材料。
        }
    }
}
