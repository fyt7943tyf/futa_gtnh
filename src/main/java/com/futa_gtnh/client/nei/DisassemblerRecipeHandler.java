package com.futa_gtnh.client.nei;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.MinecraftForge;

import com.futa_gtnh.disassembler.DisassemblerRegistration;
import com.futa_gtnh.disassembler.DisassemblyCatalog;
import com.futa_gtnh.disassembler.DisassemblyRecipe;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import codechicken.nei.NEIClientConfig;
import codechicken.nei.PositionedStack;
import codechicken.nei.api.API;
import codechicken.nei.event.NEIConfigsLoadedEvent;
import codechicken.nei.recipe.TemplateRecipeHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.util.GTUtility;

/** Each NEI entry is one numbered page of the same complete batch, never a separate machine operation. */
public final class DisassemblerRecipeHandler extends TemplateRecipeHandler {

    public static final String ID = "futa_gtnh.disassembly";

    public static void register() {
        DisassemblerRecipeHandler handler = new DisassemblerRecipeHandler();
        API.registerRecipeHandler(handler);
        API.registerUsageHandler(handler);
        API.addRecipeCatalyst(DisassemblerRegistration.stack(), handler);
        MinecraftForge.EVENT_BUS.register(handler);
    }

    /** NEI assigns unknown handlers zero, placing them ahead of GT's explicitly ordered machines. */
    public static void applyDefaultOrder() {
        String key = DisassemblerRecipeHandler.class.getName();
        Integer overlayOrder = NEIClientConfig.handlerOrdering.get(ID);
        Integer handlerOrder = NEIClientConfig.handlerOrdering.get(key);
        if ((overlayOrder == null || overlayOrder == 0) && handlerOrder != null && handlerOrder != 0) {
            NEIClientConfig.handlerOrdering.remove(ID);
            return;
        }
        if ((overlayOrder == null || overlayOrder == 0) && (handlerOrder == null || handlerOrder == 0))
            NEIClientConfig.handlerOrdering.put(ID, 10000);
    }

    @SubscribeEvent
    public void onNeiConfigsLoaded(NEIConfigsLoadedEvent event) {
        // NEI reads handlerordering.csv after mod registration. Apply the default once it has loaded.
        applyDefaultOrder();
    }

    @Override
    public String getRecipeName() {
        return StatCollector.translateToLocal("futa_gtnh.disassembler.name");
    }

    /**
     * NEI 会<b>无条件</b>拿这个字符串去 {@code GuiDraw.changeTexture}，所以绝不能返回空串。
     *
     * <p>
     * 原来这里写的是 {@code ""}（作者的意图是「这个配方页不用贴图，我自己画背景」），
     * 但 NEI 的 {@code TemplateRecipeHandler.drawForeground} 里是这么写的：
     *
     * <pre>
     * GuiDraw.changeTexture(getGuiTexture()); // 没有判空
     * drawExtras(recipe);
     * </pre>
     *
     * 空串会变成 {@code new ResourceLocation("")}，加载出来的图是 null，
     * 于是 {@code TextureUtil.uploadTextureImageAllocate} 直接 NPE ——
     * 崩在「打开拆解机配方」的那一刻，而且崩溃报告里只会写
     * {@code Resource location: minecraft:} 这种看不出所以然的东西。
     *
     * <p>
     * 这里给一张一定存在的原版贴图：{@link #drawBackground} 会用自己的灰底盖满整个
     * 配方页（166×140），所以这张图其实<b>看不见</b>，绑定它只是为了不让 NEI 拿到空串。
     */
    @Override
    public String getGuiTexture() {
        return "textures/gui/container/generic_54.png";
    }

    @Override
    public String getOverlayIdentifier() {
        return ID;
    }

    @Override
    public int getRecipeHeight(int recipe) {
        return 140;
    }

    @Override
    public int recipiesPerPage() {
        return 1;
    }

    /**
     * 配方页上「点这里把材料搬进机器」的那块区域。
     *
     * <p>
     * 不实现它的话，NEI 在注册合成催化剂时会打一条
     * {@code failed to load catalyst handler, implement `loadTransferRects` for your handler}
     * —— 而且「从背包一键填料」这个功能也就没了。区域画在入口格和产出格之间那道空隙上，
     * 和别的配方的习惯一致。
     */
    @Override
    public void loadTransferRects() {
        transferRects.add(new RecipeTransferRect(new Rectangle(22, 24, 8, 18), ID));
    }

    @Override
    public void drawBackground(int recipe) {
        Gui.drawRect(0, 0, 166, 140, 0xffc6c6c6);
        Page page = (Page) arecipes.get(recipe);
        slot(page.getIngredient());
        for (PositionedStack output : page.outputs) slot(output);
    }

    private void slot(PositionedStack stack) {
        Gui.drawRect(stack.relx - 1, stack.rely - 1, stack.relx + 17, stack.rely + 17, 0xff888888);
    }

    @Override
    public void drawExtras(int recipe) {
        Page page = (Page) arecipes.get(recipe);
        Minecraft.getMinecraft().fontRenderer.drawString("32 EU/t · LV 1A · 2s", 4, 118, 0x404040);
        Minecraft.getMinecraft().fontRenderer.drawString(
            StatCollector.translateToLocalFormatted("futa_gtnh.disassembler.nei_page", page.page + 1, page.pages),
            4,
            130,
            0x404040);
    }

    @Override
    public void loadCraftingRecipes(String id, Object... results) {
        if (ID.equals(id)) {
            for (DisassemblyRecipe recipe : DisassemblyCatalog.all()) add(recipe);
        } else super.loadCraftingRecipes(id, results);
    }

    @Override
    public void loadCraftingRecipes(ItemStack result) {
        ItemKey key = ItemKey.of(result);
        FluidKey fluidKey = FluidKey.of(GTUtility.getFluidFromDisplayStack(result));
        for (DisassemblyRecipe recipe : DisassemblyCatalog.all()) {
            boolean matches = recipe.itemOutputs()
                .stream()
                .anyMatch(stack -> key.equals(ItemKey.of(stack)))
                || recipe.fluidOutputs()
                    .stream()
                    .anyMatch(
                        fluid -> FluidKey.of(fluid)
                            .equals(fluidKey));
            if (matches) add(recipe);
        }
    }

    @Override
    public void loadUsageRecipes(ItemStack ingredient) {
        if (ItemKey.of(ingredient)
            .equals(ItemKey.of(DisassemblerRegistration.stack()))) {
            for (DisassemblyRecipe recipe : DisassemblyCatalog.all()) add(recipe);
        } else {
            DisassemblyRecipe recipe = DisassemblyCatalog.find(ingredient);
            if (recipe != null) add(recipe);
        }
    }

    private void add(DisassemblyRecipe recipe) {
        List<ItemStack> items = recipe.itemOutputs();
        List<ItemStack> fluids = new ArrayList<>();
        recipe.fluidOutputs()
            .forEach(fluid -> fluids.add(GTUtility.getFluidDisplayStack(fluid, true)));
        int pages = Math.max(1, Math.max((items.size() + 27) / 28, (fluids.size() + 6) / 7));
        for (int page = 0; page < pages; page++) arecipes.add(new Page(recipe, items, fluids, page, pages));
    }

    private final class Page extends CachedRecipe {

        final PositionedStack input;
        final List<PositionedStack> outputs = new ArrayList<>();
        final int page, pages;

        Page(DisassemblyRecipe recipe, List<ItemStack> items, List<ItemStack> fluids, int page, int pages) {
            this.page = page;
            this.pages = pages;
            input = new PositionedStack(recipe.input.prototype(recipe.inputCount), 3, 24, false);
            for (int offset = 0; offset < 28 && page * 28 + offset < items.size(); offset++) outputs.add(
                new PositionedStack(items.get(page * 28 + offset), 31 + offset % 7 * 18, 6 + offset / 7 * 18, false));
            for (int offset = 0; offset < 7 && page * 7 + offset < fluids.size(); offset++)
                outputs.add(new PositionedStack(fluids.get(page * 7 + offset), 31 + offset * 18, 92, false));
        }

        @Override
        public PositionedStack getIngredient() {
            return input;
        }

        @Override
        public List<PositionedStack> getIngredients() {
            return Collections.singletonList(input);
        }

        @Override
        public PositionedStack getResult() {
            return outputs.isEmpty() ? null : outputs.get(0);
        }

        @Override
        public List<PositionedStack> getOtherStacks() {
            return outputs.size() <= 1 ? Collections.emptyList() : outputs.subList(1, outputs.size());
        }
    }
}
