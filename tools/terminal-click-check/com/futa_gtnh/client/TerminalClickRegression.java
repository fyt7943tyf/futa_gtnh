package com.futa_gtnh.client;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.*;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;

import com.futa_gtnh.Config;
import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.exchange.InventoryExchange;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

import cpw.mods.fml.common.Loader;
import sun.misc.Unsafe;

/** Runs real GUI down/up methods; only keyboard/time and outgoing action transport are fixtures. */
public final class TerminalClickRegression {

    public static void main(String[] args) throws Exception {
        Loader.injectData(
            new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
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
        mc.thePlayer.worldObj = new World(
            new SaveHandlerMP(),
            "check",
            new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
            new WorldProviderSurface(),
            new Profiler()) {

            protected IChunkProvider createChunkProvider() {
                return null;
            }

            protected int func_152379_p() {
                return 0;
            }

            public Entity getEntityByID(int id) {
                return null;
            }
        };
        Config.shiftClickWithdrawAmount = 64;
        Config.displayItemBecomesFluid = false;
        scenario(mc, "five separated Shift clicks", new long[] { 10000, 11000, 12000, 13000, 14000 }, 5);
        scenario(
            mc,
            "five Shift clicks including one double-click",
            new long[] { 20000, 20100, 21000, 22000, 23000 },
            5);
        scenario(mc, "five fast Shift clicks", new long[] { 30000, 30100, 30200, 30300, 30400 }, 5);
        releaseAndOrdinaryClicks(mc);
        slotEdges(mc);
        backpackDoubleClick(mc);
        System.out.println(
            "Terminal click regression: rapid Shift clicks, release handling, ordinary cursor collection and backpack double-click passed.");
    }

    private static void scenario(Minecraft mc, String label, long[] times, int expected) throws Exception {
        org.lwjgl.input.Keyboard.shift = true;
        java.util.Arrays.fill(mc.thePlayer.inventory.mainInventory, null);
        mc.thePlayer.inventory.setItemStack(null);
        ContainerSharedTerminal terminal = new TestContainer(mc);
        ItemKey key = ItemKey.of(new ItemStack(Items.iron_ingot));
        terminal.setPageDisplay(
            Collections.singletonList(new ItemStack(Items.iron_ingot)),
            Collections.singletonList(key),
            Collections.singletonList(null));
        SharedStorage store = new SharedStorage();
        store.insertItem(key, 1024);
        TestGui gui = new TestGui(terminal, store, key);
        gui.mc = mc;
        Slot slot = terminal.getSlot(0);
        for (long time : times) {
            org.lwjgl.Sys.now = time;
            gui.releasing = false;
            gui.mouseClicked(slot.xDisplayPosition + 4, slot.yDisplayPosition + 4, 0);
            gui.releasing = true;
            gui.mouseMovedOrUp(slot.xDisplayPosition + 4, slot.yDisplayPosition + 4, 0);
        }
        long backpack = 0;
        for (ItemStack stack : mc.thePlayer.inventory.mainInventory) if (stack != null) backpack += stack.stackSize;
        if (gui.actions != expected || gui.releaseActions != 0
            || backpack != expected * 64
            || store.getItemAmount(key) + backpack != 1024) throw new AssertionError(label);
        System.out.println(
            label + ": presses=5, requests="
                + gui.actions
                + ", release_requests="
                + gui.releaseActions
                + ", backpack="
                + backpack
                + ", warehouse="
                + store.getItemAmount(key));
    }

    private static TestGui fresh(Minecraft mc) {
        java.util.Arrays.fill(mc.thePlayer.inventory.mainInventory, null);
        mc.thePlayer.inventory.setItemStack(null);
        org.lwjgl.input.Keyboard.shift = true;
        org.lwjgl.Sys.now += 1000;
        TestContainer terminal = new TestContainer(mc);
        ItemKey key = ItemKey.of(new ItemStack(Items.iron_ingot));
        terminal.setPageDisplay(
            Collections.singletonList(new ItemStack(Items.iron_ingot)),
            Collections.singletonList(key),
            Collections.singletonList(null));
        SharedStorage store = new SharedStorage();
        store.insertItem(key, 1024);
        terminal.store = store;
        TestGui gui = new TestGui(terminal, store, key);
        gui.mc = mc;
        return gui;
    }

    private static void press(TestGui gui, int slotId, int button) {
        Slot slot = gui.inventorySlots.getSlot(slotId);
        gui.releasing = false;
        gui.mouseClicked(slot.xDisplayPosition + 4, slot.yDisplayPosition + 4, button);
    }

    private static void release(TestGui gui, int slotId, int button) {
        Slot slot = gui.inventorySlots.getSlot(slotId);
        gui.releasing = true;
        gui.mouseMovedOrUp(slot.xDisplayPosition + 4, slot.yDisplayPosition + 4, button);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void releaseAndOrdinaryClicks(Minecraft mc) {
        TestGui gui = fresh(mc);
        press(gui, 0, 0);
        org.lwjgl.input.Keyboard.shift = false;
        // Async inventory updates or releasing Shift must not make a handled gesture place a cursor stack.
        mc.thePlayer.inventory.setItemStack(new ItemStack(Items.diamond));
        Slot main = gui.inventorySlots.getSlot(ContainerSharedTerminal.MAIN_START);
        gui.mouseClickMove(main.xDisplayPosition + 4, main.yDisplayPosition + 4, 0, 20);
        release(gui, ContainerSharedTerminal.MAIN_START, 0);
        check(
            gui.actions == 1 && gui.releaseActions == 0
                && mc.thePlayer.inventory.getItemStack()
                    .getItem() == Items.diamond,
            "release elsewhere and key/cursor changes do not redispatch");

        gui = fresh(mc);
        press(gui, 0, 0); // Deliberately omit its release, as if it happened outside the window.
        org.lwjgl.input.Keyboard.shift = false;
        org.lwjgl.Sys.now += 1000;
        press(gui, 0, 0);
        release(gui, 0, 0);
        check(
            gui.actions == 2 && mc.thePlayer.inventory.getItemStack().stackSize == 64,
            "ordinary click after missed release still withdraws to cursor");
        org.lwjgl.Sys.now += 100;
        press(gui, 0, 0);
        release(gui, 0, 0);
        check(gui.collectActions == 1, "ordinary double-click collection preserved");

        gui = fresh(mc);
        press(gui, 0, 1);
        release(gui, 0, 1);
        check(
            gui.actions == 1 && gui.releaseActions == 0 && gui.store.getItemAmount(gui.key) == 0,
            "Shift right-click withdraw-all executes once");

        gui = fresh(mc);
        mc.thePlayer.inventory.setItemStack(new ItemStack(Items.iron_ingot, 16));
        press(gui, 0, 0);
        release(gui, 0, 0);
        check(
            gui.actions == 1 && mc.thePlayer.inventory.getItemStack() == null
                && gui.store.getItemAmount(gui.key) == 1040,
            "held-cursor Shift deposit preserved");
    }

    private static void backpackDoubleClick(Minecraft mc) throws Exception {
        TestGui gui = fresh(mc);
        TestContainer terminal = (TestContainer) gui.inventorySlots;
        mc.thePlayer.inventory.mainInventory[9] = new ItemStack(Items.iron_ingot, 16);
        mc.thePlayer.inventory.mainInventory[10] = new ItemStack(Items.iron_ingot, 32);
        press(gui, ContainerSharedTerminal.MAIN_START, 0);
        release(gui, ContainerSharedTerminal.MAIN_START, 0);
        // Keep the app's wall-clock based double-click window deterministic without sleeping.
        Field last = GuiSharedTerminal.class.getDeclaredField("lastShiftClickTime");
        last.setAccessible(true);
        last.setLong(gui, System.currentTimeMillis());
        press(gui, ContainerSharedTerminal.MAIN_START, 0);
        release(gui, ContainerSharedTerminal.MAIN_START, 0);
        check(
            terminal.matchingDeposits == 1 && mc.thePlayer.inventory.mainInventory[9] == null
                && mc.thePlayer.inventory.mainInventory[10] == null
                && gui.store.getItemAmount(gui.key) == 1072,
            "backpack Shift double-click still deposits matching stacks");
    }

    private static void slotEdges(Minecraft mc) {
        int[][] offsets = { { -1, -1 }, { -1, 4 }, { 4, -1 }, { 16, 16 }, { 16, 4 }, { 4, 16 } };
        for (int[] offset : offsets) {
            TestGui gui = fresh(mc);
            Slot slot = gui.inventorySlots.getSlot(0);
            for (int i = 0; i < 5; i++) {
                org.lwjgl.Sys.now += 100;
                gui.releasing = false;
                gui.mouseClicked(slot.xDisplayPosition + offset[0], slot.yDisplayPosition + offset[1], 0);
                gui.releasing = true;
                gui.mouseMovedOrUp(slot.xDisplayPosition + offset[0], slot.yDisplayPosition + offset[1], 0);
            }
            check(
                gui.actions == 5 && gui.releaseActions == 0 && gui.store.getItemAmount(gui.key) == 704,
                "slot edge Shift clicks execute exactly once");
        }
    }

    private static final class TestContainer extends ContainerSharedTerminal {

        final Minecraft mc;
        SharedStorage store;
        int matchingDeposits;

        TestContainer(Minecraft mc) {
            super(mc.thePlayer.inventory, null);
            this.mc = mc;
        }

        @Override
        public void requestDepositMatching(ItemKey key, long amount) {
            matchingDeposits++;
            InventoryExchange.depositMatching(mc.thePlayer, key, amount, store, new DeltaRecorder(store, null));
        }
    }

    private static final class TestGui extends GuiSharedTerminal {

        final SharedStorage store;
        final ItemKey key;
        int actions, releaseActions, collectActions;
        boolean releasing;

        TestGui(ContainerSharedTerminal terminal, SharedStorage store, ItemKey key) {
            super(terminal);
            this.store = store;
            this.key = key;
        }

        protected void handleMouseClick(Slot slot, int slotId, int button, int mode) {
            actions++;
            if (releasing) releaseActions++;
            DeltaRecorder recorder = new DeltaRecorder(store, null);
            if (slotId == 0) {
                if (mode == 6) {
                    collectActions++;
                    InventoryExchange.collectToCursor(
                        mc.thePlayer,
                        ((ContainerSharedTerminal) inventorySlots).getCraftMatrix(),
                        key,
                        0,
                        store,
                        recorder);
                } else if (mc.thePlayer.inventory.getItemStack() != null) {
                    InventoryExchange.depositCursor(mc.thePlayer, 0, store, recorder);
                } else if (mode == 1) {
                    InventoryExchange.withdrawItem(mc.thePlayer, key, button == 0 ? 64 : 0, store, recorder);
                } else if (mode == 0) {
                    InventoryExchange.withdrawToCursor(mc.thePlayer, key, 64, store, recorder);
                } else throw new AssertionError("unexpected shared click mode " + mode);
            } else if (mode == 1) {
                InventoryExchange.depositSlot(mc.thePlayer, slot.getSlotIndex(), 0, store, recorder);
            } else throw new AssertionError("unexpected click " + slotId + "/" + mode);
        }
    }
}
