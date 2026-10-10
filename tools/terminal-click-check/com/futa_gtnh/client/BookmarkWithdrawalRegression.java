package com.futa_gtnh.client;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.*;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.nei.SharedTerminalBookmarkHandler;
import com.futa_gtnh.exchange.BookmarkWithdrawal;
import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.exchange.StorageActionHandler;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import codechicken.nei.BookmarkPanel;
import codechicken.nei.api.API;
import codechicken.nei.api.IBookmarkContainerHandler;
import cpw.mods.fml.common.Loader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import sun.misc.Unsafe;

/** Exercises real NEI quantity calculations and packet serialization against an isolated warehouse. */
public final class BookmarkWithdrawalRegression {

    private static int assertions;
    private static ItemKey IRON;

    public static void main(String[] args) throws Exception {
        Loader.injectData(
            new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Bootstrap.func_151354_b();
        IRON = ItemKey.of(new ItemStack(Items.iron_ingot));
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
            new SaveHandlerMP(), "check",
            new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
            new WorldProviderSurface(), new Profiler()) {

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
        FutaGtnhMod.proxy = new CommonProxy() {

            public void notifyPlayer(EntityPlayer player, String key) {}
        };
        ContainerSharedTerminal container = new ContainerSharedTerminal(mc.thePlayer.inventory, null);
        container.windowId = 17;
        GuiSharedTerminal gui = new GuiSharedTerminal(container);
        gui.mc = mc;
        mc.currentScreen = gui;
        mc.thePlayer.openContainer = container;
        cacheAndNei(gui, mc.thePlayer);
        quantities(mc.thePlayer);
        malformed(mc.thePlayer);
        staleWindow(unsafe, container);
        batching();
        ClientStorageCache.clear();
        System.out.println("Bookmark withdrawal regression: " + assertions + " assertions passed");
    }

    private static void cacheAndNei(GuiSharedTerminal gui, EntityPlayer player) throws Exception {
        ClientStorageCache.clear();
        PacketStorageDelta delta = new PacketStorageDelta();
        delta.addItem(IRON, 1000);
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        delta.addItem(gold, Long.MAX_VALUE);
        ClientStorageCache.applyDelta(delta.getChanges());
        Field ready = ClientStorageCache.class.getDeclaredField("ready");
        ready.setAccessible(true);
        ready.setBoolean(null, true);
        SharedTerminalBookmarkHandler handler = new SharedTerminalBookmarkHandler();
        List<ItemStack> snapshot = handler.getStorageStacks(gui);
        check(snapshot.size() == 2, "all cache entries visible with an empty terminal display page");
        check(snapshot.get(0).stackSize == 1000, "quantity is not the display stack size");
        check(snapshot.get(1).stackSize == Integer.MAX_VALUE, "large inventory amount saturates without overflow");
        snapshot.get(0).stackSize = 1;
        check(ClientStorageCache.getItemAmount(IRON.prototype()) == 1000, "snapshot does not mutate cache");
        SharedStorage store = new SharedStorage();
        store.insertItem(IRON, 1000);
        API.registerBookmarkContainerHandler(GuiSharedTerminal.class, new IBookmarkContainerHandler() {

            public List<ItemStack> getStorageStacks(GuiContainer current) {
                return handler.getStorageStacks(current);
            }

            public void pullBookmarkItemsFromContainer(GuiContainer current, ArrayList<ItemStack> requested) {
                for (PacketStorageAction packet : BookmarkWithdrawal.requests(requested, current.inventorySlots.windowId)) {
                    execute(player, store, roundTrip(packet));
                }
            }
        });
        BookmarkPanel panel = new BookmarkPanel();
        panel.addGroup(Collections.singletonList(IRON.prototype(173)), BookmarkPanel.BookmarkViewMode.DEFAULT, false);
        player.inventory.mainInventory[0] = IRON.prototype(20);
        check(panel.pullBookmarkItems(1, false), "NEI normal pull reaches registered handler");
        check(count(player, IRON) == 193 && store.getItemAmount(IRON) == 827, "V adds the full 173");
        Arrays.fill(player.inventory.mainInventory, null);
        player.inventory.mainInventory[0] = IRON.prototype(20);
        check(panel.pullBookmarkItems(1, true), "NEI missing pull reaches registered handler");
        check(count(player, IRON) == 173 && store.getItemAmount(IRON) == 674, "Shift+V requests only missing 153");
    }

    private static void quantities(EntityPlayer player) {
        Arrays.fill(player.inventory.mainInventory, null);
        SharedStorage store = new SharedStorage();
        store.insertItem(IRON, 1000);
        PacketStorageAction packet = BookmarkWithdrawal.requests(Collections.singletonList(IRON.prototype(173)), 17).get(0);
        check(execute(player, store, roundTrip(packet)) == 173, "packet preserves counts above 64 and 127");
        check(count(player, IRON) + store.getItemAmount(IRON) == 1000, "total count conserved");
        for (int i = 0; i < 36; i++) player.inventory.mainInventory[i] = new ItemStack(Blocks.cobblestone, 64);
        player.inventory.mainInventory[0] = IRON.prototype(60);
        check(execute(player, store, packet) == 4, "full backpack fills only remaining same-item capacity");
        check(store.getItemAmount(IRON) == 823, "unplaced items stay in storage");
        check(execute(player, store, packet) == 0 && store.getItemAmount(IRON) == 823, "full backpack loses nothing");
        Arrays.fill(player.inventory.mainInventory, null);
        SharedStorage shortStore = new SharedStorage();
        shortStore.insertItem(IRON, 7);
        check(execute(player, shortStore, packet) == 7 && count(player, IRON) == 7, "server uses actual available inventory");
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("variant", "special");
        ItemStack tagged = IRON.prototype(3);
        tagged.setTagCompound(nbt);
        ItemKey special = ItemKey.of(tagged);
        shortStore.insertItem(special, 9);
        PacketStorageAction taggedPacket = BookmarkWithdrawal.requests(Collections.singletonList(tagged), 17).get(0);
        check(execute(player, shortStore, roundTrip(taggedPacket)) == 3, "NBT variant withdraws exactly requested");
        check(count(player, special) == 3 && count(player, IRON) == 7, "NBT variants are not mixed");
    }

    private static void malformed(EntityPlayer player) {
        SharedStorage store = new SharedStorage();
        store.insertItem(IRON, 100);
        PacketStorageAction valid = BookmarkWithdrawal.requests(Collections.singletonList(IRON.prototype(2)), 17).get(0);
        for (long invalid : new long[] { 0, -1, Long.MAX_VALUE }) {
            NBTTagCompound root = (NBTTagCompound) valid.getLayoutTag().copy();
            NBTTagList entries = root.getTagList("items", 10);
            NBTTagCompound bad = (NBTTagCompound) entries.getCompoundTagAt(0).copy();
            bad.setLong("amount", invalid);
            entries.appendTag(bad);
            check(BookmarkWithdrawal.withdraw(player, root, store, new DeltaRecorder(store, null)) == 0,
                "malformed batch rejected before any item moves");
            check(store.getItemAmount(IRON) == 100, "malformed quantity cannot mean withdraw all");
        }
    }

    private static void batching() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            ItemStack stack = IRON.prototype(2);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("variant", i);
            stack.setTagCompound(tag);
            items.add(stack);
        }
        List<PacketStorageAction> packets = BookmarkWithdrawal.requests(items, 17);
        check(packets.size() > 1, "large groups are split into bounded batches");
        int entries = 0;
        for (PacketStorageAction packet : packets) {
            PacketStorageAction copy = roundTrip(packet);
            check(copy.getAction() == PacketStorageAction.WITHDRAW_BOOKMARK_ITEMS && copy.getInvSlot() == 17,
                "batch action and window survive serialization");
            int count = copy.getLayoutTag().getTagList("items", 10).tagCount();
            check(count <= BookmarkWithdrawal.MAX_ENTRIES, "entry cap respected");
            entries += count;
        }
        check(entries == 300, "no entries lost when splitting");
        PacketStorageAction merged = BookmarkWithdrawal.requests(Arrays.asList(IRON.prototype(100), IRON.prototype(73)), 17).get(0);
        check(merged.getLayoutTag().getTagList("items", 10).getCompoundTagAt(0).getLong("amount") == 173,
            "duplicate keys aggregate exact counts");
        ItemStack oversized = IRON.prototype();
        NBTTagCompound bigTag = new NBTTagCompound();
        bigTag.setByteArray("payload", new byte[BookmarkWithdrawal.MAX_BYTES]);
        oversized.setTagCompound(bigTag);
        boolean rejected = false;
        try {
            BookmarkWithdrawal.requests(Arrays.asList(IRON.prototype(2), oversized), 17);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "oversized single item aborts before any partial request is sent");
    }

    private static void staleWindow(Unsafe unsafe, ContainerSharedTerminal container) throws Exception {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.inventory = new InventoryPlayer(player);
        player.openContainer = container;
        Field uuid = Entity.class.getDeclaredField("entityUniqueID");
        uuid.setAccessible(true);
        uuid.set(player, java.util.UUID.randomUUID());
        SharedStorage store = SharedStorageManager.getStorage();
        store.insertItem(IRON, 100);
        PacketStorageAction request = BookmarkWithdrawal.requests(Collections.singletonList(IRON.prototype(10)), 16).get(0);
        StorageActionHandler.handle(player, request);
        check(store.getItemAmount(IRON) == 100 && count(player, IRON) == 0, "stale window rejected by real server dispatcher");
        player.openContainer = null;
        StorageActionHandler.handle(player, request.withInvSlot(17));
        check(store.getItemAmount(IRON) == 100, "request without an open terminal rejected");
        StorageActionHandler.forget(player);
        store.extractItem(IRON, 100);
    }

    private static long execute(EntityPlayer player, SharedStorage store, PacketStorageAction packet) {
        return BookmarkWithdrawal.withdraw(player, packet.getLayoutTag(), store, new DeltaRecorder(store, null));
    }

    private static PacketStorageAction roundTrip(PacketStorageAction packet) {
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            PacketStorageAction copy = new PacketStorageAction();
            copy.fromBytes(bytes);
            return copy;
        } finally {
            bytes.release();
        }
    }

    private static long count(EntityPlayer player, ItemKey key) {
        long total = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && key.equals(ItemKey.of(stack))) total += stack.stackSize;
        }
        return total;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
