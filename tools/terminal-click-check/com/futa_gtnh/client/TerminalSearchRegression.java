package com.futa_gtnh.client;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.futa_gtnh.Config;
import com.futa_gtnh.client.nei.FutaNeiGuiHandler;
import com.futa_gtnh.client.nei.StoragePanelInput;
import com.futa_gtnh.client.nei.TerminalSearchInput;
import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerSharedTerminal;

import codechicken.nei.ItemPanel;
import codechicken.nei.PanelWidget;
import codechicken.nei.api.API;
import codechicken.nei.guihook.GuiContainerManager;
import cpw.mods.fml.common.Loader;
import sun.misc.Unsafe;

/** Uses actual NEI dispatch and drag completion with device/font rendering replaced by fixtures. */
public final class TerminalSearchRegression {

    private static int assertions;

    public static void main(String[] args) throws Exception {
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Bootstrap.func_151354_b();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        singleton.set(null, mc);
        mc.gameSettings = new GameSettings();
        mc.thePlayer = (EntityClientPlayerMP) unsafe.allocateInstance(EntityClientPlayerMP.class);
        mc.thePlayer.inventory = new InventoryPlayer(mc.thePlayer);
        mc.fontRenderer = (TestFont) unsafe.allocateInstance(TestFont.class);
        org.lwjgl.input.Keyboard.shift = false;
        TestGui gui = new TestGui(new ContainerSharedTerminal(mc.thePlayer.inventory, null));
        gui.mc = mc;
        mc.currentScreen = gui;
        FutaSearchField search = new FutaSearchField(mc.fontRenderer, 108, 57, 116, 12);
        set(gui, "searchField", search);
        Method changed = GuiSharedTerminal.class.getDeclaredMethod("onSearchTextChanged", String.class);
        changed.setAccessible(true);
        search.setChangeListener(text -> {
            try { changed.invoke(gui, text); } catch (Exception error) { throw new AssertionError(error); }
        });
        List<String> synced = new ArrayList<>();
        Config.terminalSearchMode = Config.TerminalSearchMode.NEI_SYNC;
        NeiSearchBridge.install(new NeiSearchBridge.Impl() {
            public boolean searchFieldExists() { return true; }
            public void pushSearchText(String text) { synced.add(text); }
        });
        GuiContainerManager.inputHandlers.clear();
        Shortcut shortcut = new Shortcut();
        GuiContainerManager.addInputHandler(shortcut);
        TerminalSearchInput.register();
        TerminalSearchInput.register();
        check(GuiContainerManager.inputHandlers.size() == 2, "priority registration is idempotent");
        GuiContainerManager manager = new GuiContainerManager(gui);
        search.setFocused(true);
        check(manager.firstKeyTyped('v', 47), "focused V is consumed before NEI shortcut");
        check(search.getText().equals("v") && shortcut.toggles == 0, "V types text without belt toggle");
        check(synced.equals(Collections.singletonList("v")), "typed text synchronizes exactly once");
        check(manager.firstKeyTyped('铁', 0) && search.getText().equals("v铁"), "IME character with key zero reaches search");
        search.setMaxStringLength(2);
        check(manager.firstKeyTyped('v', 47) && shortcut.toggles == 0, "full search still blocks shortcuts");
        check(search.getText().equals("v铁"), "full search cannot append text");
        search.setMaxStringLength(64);
        check(!manager.firstKeyTyped('\0', 1), "Escape retains GUI close path");
        int before = synced.size();
        check(!manager.mouseClicked(109, 58, 1), "passive right click leaves NEI drag handling available");
        gui.mouseClicked(109, 58, 1);
        check(search.getText().isEmpty() && search.isFocused(), "right click clears and focuses");
        check(synced.size() == before + 1 && synced.get(before).isEmpty(), "right clear synchronizes once across both click paths");
        check(manager.mouseClicked(400, 100, 0), "NEI consumes outside click in fixture");
        check(!search.isFocused(), "outside click unfocuses despite NEI consumption");
        check(manager.firstKeyTyped('v', 47) && shortcut.toggles == 1, "unfocused shortcut remains available");
        gui.mouseClicked(109, 58, 0);
        gui.keyTyped('e', 18);
        check(search.getText().equals("e") && search.isFocused(), "direct GUI path consumes inventory binding as text");
        org.lwjgl.input.Keyboard.shift = true;
        gui.mouseClicked(111, 92, 0);
        check(!search.isFocused() && gui.clicks == 1, "shared Shift click early return also unfocuses search");
        org.lwjgl.input.Keyboard.shift = false;
        search.setFocused(false);
        check(!FutaNeiGuiHandler.INSTANCE.handleDragNDrop(gui, 224, 58, new ItemStack(Items.iron_ingot), 0), "right edge is outside search drop target");
        check(!FutaNeiGuiHandler.INSTANCE.handleDragNDrop(gui, 109, 69, new ItemStack(Items.iron_ingot), 0), "bottom edge is outside search drop target");
        check(!gui.acceptSearchDrop(109, 58, null), "null drag ignored");
        ItemStack real = new ItemStack(Items.iron_ingot, 32);
        mc.thePlayer.inventory.mainInventory[9] = real;
        ItemStack ghost = real.copy().setStackDisplayName("§a精炼铁锭");
        ItemPanel panel = (ItemPanel) unsafe.allocateInstance(ItemPanel.class);
        Field dragged = PanelWidget.class.getDeclaredField("draggedStack");
        dragged.setAccessible(true);
        dragged.set(panel, ghost);
        API.registerNEIGuiHandler(FutaNeiGuiHandler.INSTANCE);
        Method drop = PanelWidget.class.getDeclaredMethod("handleDraggedClick", int.class, int.class, int.class);
        drop.setAccessible(true);
        before = synced.size();
        manager.mouseClicked(109, 58, 0);
        check((Boolean) drop.invoke(panel, 109, 58, 0), "actual NEI panel accepts search drop");
        check(search.getText().equals("精炼铁锭") && search.isFocused(), "drag fills plain display name and focuses");
        check(synced.size() == before + 1 && synced.get(before).equals("精炼铁锭"), "drag uses normal search synchronization callback");
        check(dragged.get(panel) == null, "actual NEI drag finishes instead of leaving item attached to cursor");
        check(real.stackSize == 32 && mc.thePlayer.inventory.getItemStack() == null, "drag preserves real inventory and cursor");
        check((Boolean) get(gui, "viewDirty") && (Boolean) get(gui, "pendingResort"), "search changes refresh filter and ordering");
        set(gui, "limitTarget", StorageViewEntry.ofItem(com.futa_gtnh.shared.ItemKey.of(real), 32));
        check(!gui.acceptSearchDrop(109, 58, ghost), "modal blocks search drop through dialog");
        check(!gui.handleSearchClick(109, 58, 1) && search.getText().equals("精炼铁锭"), "modal blocks search click through dialog");
        System.out.println("Terminal search regression: " + assertions + " assertions passed");
    }

    private static void set(Object gui, String name, Object value) throws Exception {
        Field field = GuiSharedTerminal.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(gui, value);
    }

    private static Object get(Object gui, String name) throws Exception {
        Field field = GuiSharedTerminal.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(gui);
    }

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        assertions++;
    }

    private static final class Shortcut extends StoragePanelInput {
        int toggles;
        public boolean keyTyped(GuiContainer gui, char typedChar, int keyCode) {
            if (keyCode != 47) return false;
            toggles++;
            return true;
        }
        public boolean mouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) { return mouseX >= 400; }
    }

    private static final class TestGui extends GuiSharedTerminal {
        int clicks;
        TestGui(ContainerSharedTerminal container) {
            super(container);
            guiLeft = 100;
            guiTop = 50;
        }
        protected void handleMouseClick(net.minecraft.inventory.Slot slot, int slotId, int button, int mode) { clicks++; }
    }

    private static final class TestFont extends FontRenderer {
        TestFont() { super(new GameSettings(), new ResourceLocation("textures/font/ascii.png"), null, false); }
        public String trimStringToWidth(String text, int width) { return text.substring(0, Math.min(text.length(), Math.max(0, width / 6))); }
        public int getStringWidth(String text) { return text.length() * 6; }
        public int getCharWidth(char character) { return 6; }
    }
}
