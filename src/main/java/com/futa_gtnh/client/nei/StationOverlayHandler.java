package com.futa_gtnh.client.nei;

import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.client.TinkersScreens;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;

import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.DefaultOverlayHandler;
import codechicken.nei.recipe.GuiOverlayButton.ItemOverlayState;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * 合成站的 NEI 配方转移：Shift 点配方里的「填入合成栏」，材料从<b>整个共享存储</b>取。
 *
 * <p>
 * <b>为什么需要它</b>：合成站旁边那块存储区一次只显示 27 格（一页）。匠魂原生的
 * {@code CraftingStationOverlayHandler} 只把它当成一个普通箱子 —— 材料不在当前这一页时，
 * 它就会判定「没有原料」，哪怕仓库里有几千个。这里换成我们自己的实现：
 * <ul>
 * <li>填栏走服务端直填（复用终端那套 {@code CraftFiller}，见 {@code station/StationCrafting}），
 * 客户端只把配方布局（每格想要哪些候选）发过去 —— 和材料在第几页、界面开没开都无关；</li>
 * <li>「材料够不够」的绿红提示（继承来的 {@code presenceOverlay}）把<b>背包 + 共享存储</b>
 * 算在一起，仓库里有就算够。</li>
 * </ul>
 *
 * <p>
 * <b>只对「挂着共享存储」的合成站生效</b>：旁边放的是真箱子（或者挂载失败）时，
 * 一律原样交回匠魂自带的那套 handler —— 那是玩家自己的箱子，我们的服务端直填
 * 够不着它，硬接过来只会把功能弄坏。
 *
 * <p>
 * 注册方式：只替换 NEI 的 overlay <b>handler</b>（{@code API.registerGuiOverlayHandler}），
 * 保留匠魂自己注册的 {@code CraftingStationStackPositioner} —— 它负责幽灵材料指引的坐标，
 * 和取料无关，没必要动。
 */
public class StationOverlayHandler extends SharedTerminalOverlayHandler {

    /**
     * 合成站的 3×3 在界面里的位置和原版工作台一致（{@code positionMainSlots} 里就是
     * 30 + col*18 / 17 + row*18），所以幽灵材料指引的偏移也用工作台那一组 (5, 11)。
     */
    public static final int OFFSET_X = 5;
    public static final int OFFSET_Y = 11;

    /** 没有共享存储的合成站：整套交回匠魂的 handler（含它自己那套模拟点击转移）。 */
    private final DefaultOverlayHandler stationFallback = new tconstruct.plugins.nei.CraftingStationOverlayHandler();

    /** 「材料够不够」的默认判定（只看这个界面里的槽位），用于上面那种情况。 */
    private final IOverlayHandler defaultPresence = new DefaultOverlayHandler();

    public StationOverlayHandler() {
        this.offsetx = OFFSET_X;
        this.offsety = OFFSET_Y;
    }

    /** @return 这个界面是不是「挂着共享存储的匠魂合成站」 */
    private static boolean applies(GuiContainer gui) {
        return TinkersScreens.sharedChestStation(gui) != null;
    }

    @Override
    public void overlayRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        if (!applies(gui)) {
            stationFallback.overlayRecipe(gui, recipe, recipeIndex, maxTransfer);
            return;
        }
        transferRecipe(gui, recipe, recipeIndex, maxTransfer ? 0 : 1);
    }

    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (!applies(gui)) return stationFallback.transferRecipe(gui, recipe, recipeIndex, multiplier);

        NBTTagCompound layout = buildLayout(recipe, recipeIndex);
        if (layout == null) return 0;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.craft(PacketStorageAction.FILL_CRAFT_MATRIX, layout, multiplier));
        return multiplier;
    }

    @Override
    public boolean craft(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        if (!applies(gui)) return stationFallback.craft(gui, recipe, recipeIndex, multiplier);

        NBTTagCompound layout = buildLayout(recipe, recipeIndex);
        if (layout == null) return false;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.craft(PacketStorageAction.AUTOCRAFT, layout, multiplier));
        return true;
    }

    @Override
    public boolean canFillCraftingGrid(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        if (!applies(gui)) return stationFallback.canFillCraftingGrid(gui, recipe, recipeIndex);
        return true;
    }

    @Override
    public boolean canCraft(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        if (!applies(gui)) return stationFallback.canCraft(gui, recipe, recipeIndex);
        return true;
    }

    /**
     * 「材料够不够」的绿红提示。
     *
     * <p>
     * 有共享存储时用父类那套（把仓库存量并进来，仓库里有就算够）；
     * 没有共享存储时退回 NEI 的默认判定 —— 不能把够不着的仓库算成「你有」。
     */
    @Override
    public List<ItemOverlayState> presenceOverlay(GuiContainer gui, IRecipeHandler recipe, int recipeIndex) {
        if (!applies(gui)) return defaultPresence.presenceOverlay(gui, recipe, recipeIndex);
        return super.presenceOverlay(gui, recipe, recipeIndex);
    }
}
