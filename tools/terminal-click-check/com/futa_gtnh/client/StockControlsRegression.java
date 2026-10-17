package com.futa_gtnh.client;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.block.TerminalOutputFilter;
import com.futa_gtnh.network.PacketStorageSync;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageFile;

import cpw.mods.fml.common.Loader;

/** Checks real disk upgrades, automation gates, manual deposits and both IO screens' shared selection model. */
public final class StockControlsRegression {

    private static int assertions;
    private static int transfer;

    public static void main(String[] args) throws Exception {
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field mods = Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        mods.set(Loader.instance(), new HashMap<>());
        Field side = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, cpw.mods.fml.relauncher.Side.CLIENT);
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        Bootstrap.func_151354_b();
        new gregtech.common.items.ItemFluidDisplay();
        testPersistenceAndAutomation();
        testLimitView();
        testWhitelist();
        check(GuiStorageLimits.parseLimit("9223372036854775807") == Long.MAX_VALUE, "full long input range");
        check(GuiStorageLimits.parseLimit("0") == 0 && GuiStorageLimits.parseLimit(" ") == -1, "zero and unlimited are distinct");
        for (String invalid : new String[] { "-1", "1.5", "9223372036854775808" }) {
            try {
                GuiStorageLimits.parseLimit(invalid);
                throw new AssertionError("Invalid limit accepted: " + invalid);
            } catch (NumberFormatException expected) {
                assertions++;
            }
        }
        System.out.println("Stock controls regression checks passed: " + assertions);
    }

    private static void testPersistenceAndAutomation() throws Exception {
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        SharedStorage store = new SharedStorage();
        store.insertItemManual(iron, 5000);
        store.insertFluidManual(water, 64000);
        store.setItemLimit(iron, 100);
        store.setFluidLimit(water, 1000);
        check(store.getItemAmount(iron) == 5000 && store.getFluidAmount(water) == 64000, "lowering limits never deletes stock");
        File root = new File("build/stock-controls-regression");
        root.mkdirs();
        File world = Files.createTempDirectory(root.toPath(), "world-").toFile();
        check(SharedStorageFile.save(world, store), "save over-limit world");
        SharedStorage restored = SharedStorageFile.load(world);
        check(restored.getItemAmount(iron) == 5000 && restored.getFluidAmount(water) == 64000, "restart retains every excess item and mB");
        check(restored.insertItem(iron, 64) == 0 && restored.insertFluid(water, 1000) == 0, "automation remains blocked after restart");
        check(restored.itemSpaceLeft(iron) == 0 && restored.fluidSpaceLeft(water) == 0, "simulation also reports no capacity");
        check(restored.insertItemManual(iron, 5) == 5 && restored.insertFluidManual(water, 7) == 7, "manual over-limit deposits remain allowed");
        check(restored.extractItem(iron, 4905) == 4905 && restored.extractFluid(water, 63007) == 63007, "over-limit stock can be withdrawn down to ceiling");
        check(restored.insertItem(iron, 1) == 0 && restored.insertFluid(water, 1) == 0, "equal to ceiling still rejects");
        restored.extractItem(iron, 3);
        restored.extractFluid(water, 10);
        check(restored.insertItem(iron, 64) == 3 && restored.insertFluid(water, 1000) == 10, "automation only accepts remaining capacity");
        restored.setItemLimit(iron, 0);
        restored.setFluidLimit(water, 0);
        check(SharedStorageFile.save(world, restored), "save zero-limit world");
        restored = SharedStorageFile.load(world);
        check(restored.getItemAmount(iron) == 100 && restored.getFluidAmount(water) == 1000, "zero limit does not erase stock on reload");
        check(restored.insertItem(iron, 1) == 0 && restored.insertFluid(water, 1) == 0, "zero limit blocks automation");
        NBTTagCompound legacy = store.writeToNbt();
        legacy.setInteger("version", 1);
        legacy.removeTag("itemLimits");
        legacy.removeTag("fluidLimits");
        SharedStorage upgraded = new SharedStorage();
        upgraded.readFromNbt(legacy);
        check(upgraded.getItemAmount(iron) == 5000 && upgraded.getFluidAmount(water) == 64000, "old save upgrade retains stock");
        check(!upgraded.hasItemLimit(iron) && !upgraded.hasFluidLimit(water), "old save defaults to unlimited");
    }

    private static void sync(SharedStorage store) {
        byte[] payload = PacketStorageSync.createPayload(store);
        ClientStorageCache.receiveChunk(++transfer, 0, 1, payload);
    }

    private static void testLimitView() {
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        SharedStorage store = new SharedStorage();
        store.setItemLimit(iron, 0);
        store.setFluidLimit(water, 0);
        store.insertItem(gold, 20);
        sync(store);
        List<StorageViewEntry> items = ClientStorageCache.limitEntries(false, false);
        check(items.size() == 2 && ClientStorageCache.limitEntries(false, true).size() == 1, "all view combines stock and empty configured limits without duplicates");
        check(ClientStorageCache.limitEntries(false, true).get(0).getAmount() == 0, "empty limited item is still editable");
        check(ClientStorageCache.limitEntries(true, true).size() == 1, "empty limited fluid is still editable");
        check(ClientStorageCache.isItemFull(iron) && ClientStorageCache.isFluidFull(water), "zero-stock zero-limit displays blocked");
        store.insertItemManual(iron, 700);
        store.setItemLimit(iron, 100);
        sync(store);
        check(ClientStorageCache.limitEntries(false, false).size() == 2, "stock and setting for same item appear once");
        StorageViewEntry target = ClientStorageCache.itemEntry(iron);
        check(GuiStorageLimits.amountOf(target) == 700 && GuiStorageLimits.limitOf(target) == 100, "editor displays excess stock unchanged");
        store.extractItem(iron, 700);
        sync(store);
        check(GuiStorageLimits.amountOf(target) == 0, "selected editor does not retain stale stock after withdrawal");
        ClientStorageCache.clear();
        check(ClientStorageCache.limitEntries(false, true).isEmpty() && ClientStorageCache.limitEntries(true, true).isEmpty(), "disconnect clears both limit tables");
    }

    private static void testWhitelist() {
        TerminalIoConfig config = new TerminalIoConfig();
        TerminalOutputFilter south = config.getOutputFilter(ForgeDirection.SOUTH);
        TerminalOutputFilter north = config.getOutputFilter(ForgeDirection.NORTH);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        south.toggleItem(iron);
        north.toggleItem(iron);
        south.toggleFluid(water);
        OutputWhitelistPanel panel = new OutputWhitelistPanel();
        panel.refresh(south, false);
        check(panel.size() == 1 && panel.entryAt(1, 1, 0, 0).getAmount() == 0, "selected whitelist includes empty stock independent of search");
        check(!panel.removeAt(180, 1, 0, 0) && !panel.removeAt(1, 18, 0, 0), "outside selected strip cannot change output");
        check(panel.removeAt(1, 1, 0, 0) && !south.matches(iron.prototype()), "click cancels explicit item output");
        check(north.containsItem(iron) && south.containsFluid(water), "remove affects only current face and type");
        check(!panel.removeAt(1, 1, 0, 0), "empty strip click never re-adds removed entry");
        panel.refresh(south, true);
        check(panel.size() == 1 && panel.removeAt(1, 1, 0, 0), "empty-stock fluid can also be removed");
        check(!south.matches(water.prototype()), "fluid output is cancelled");
        for (int variant = 0; variant < 25; variant++) {
            ItemStack stack = new ItemStack(Items.iron_ingot);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("variant", variant);
            stack.setTagCompound(tag);
            south.toggleItem(ItemKey.of(stack));
        }
        panel.refresh(south, false);
        check(panel.size() == 25 && panel.pageCount() == 3, "selection pagination never truncates at ten or forty");
        panel.movePage(2);
        for (int count = 0; count < 5; count++) check(panel.removeAt(1, 1, 0, 0), "last page entry can be removed");
        check(panel.size() == 20 && panel.page() == 1, "last page removal clamps to previous valid page");
        TerminalIoConfig saved = new TerminalIoConfig();
        saved.readFromNbt(config.writeToNbt());
        check(saved.getOutputFilter(ForgeDirection.SOUTH).getItems().size() == 20 && saved.getOutputFilter(ForgeDirection.NORTH).containsItem(iron), "whitelist edits persist with face isolation");
        panel.refresh(north, false);
        check(panel.size() == 1 && panel.page() == 0, "changing faces resets independent selection page");

        OreDictionary.registerOre("crushedStockControlIron", iron.prototype());
        north.togglePreset(TerminalIoConfig.Preset.CRUSHED);
        check(north.matches(iron.prototype()), "item is allowed by both exact whitelist and ore preset");
        check(panel.removeAt(1, 1, 0, 0) && north.getItems().isEmpty() && north.writeToNbt().getTagList("excludedItems", 10).tagCount() == 0, "removing final manual selection clears ore exclusions");
        TerminalOutputFilter reloaded = new TerminalOutputFilter();
        reloaded.readFromNbt(north.writeToNbt());
        check(reloaded.getItems().isEmpty() && reloaded.getPresets().contains(TerminalIoConfig.Preset.CRUSHED) && reloaded.writeToNbt().getTagList("excludedItems", 10).tagCount() == 0, "empty manual list retains ore rules without exclusions after restart");
        reloaded.toggleItem(iron);
        check(reloaded.matches(iron.prototype()), "selecting cancelled entry again restores output");
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
