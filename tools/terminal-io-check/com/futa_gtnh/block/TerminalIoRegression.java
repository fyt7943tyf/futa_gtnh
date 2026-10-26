package com.futa_gtnh.block;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.block.TerminalIoConfig.Mode;
import com.futa_gtnh.block.TerminalIoConfig.Preset;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.shared.StorageNetworkAccess;
import com.futa_gtnh.storagebus.StorageBusRegistry;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

import appeng.util.item.AEItemStack;
import appeng.util.prioitylist.OreFilteredList;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

/** Registry-only regression checks; no game world, network connection or player data is used. */
public final class TerminalIoRegression {

    private static final ForgeDirection EAST = ForgeDirection.EAST;
    private static final ForgeDirection WEST = ForgeDirection.WEST;
    private static int checks;

    private TerminalIoRegression() {}

    public static void main(String[] args) throws Exception {
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        Bootstrap.func_151354_b();
        Field namedMods = Loader.class.getDeclaredField("namedMods");
        namedMods.setAccessible(true);
        namedMods.set(Loader.instance(), Collections.emptyMap());
        cpw.mods.fml.common.LoadController controller = new cpw.mods.fml.common.LoadController(Loader.instance());
        Field activeContainer = cpw.mods.fml.common.LoadController.class.getDeclaredField("activeContainer");
        activeContainer.setAccessible(true);
        activeContainer.set(controller, new cpw.mods.fml.common.DummyModContainer(new cpw.mods.fml.common.ModMetadata()));
        Field modController = Loader.class.getDeclaredField("modController");
        modController.setAccessible(true);
        modController.set(Loader.instance(), controller);
        Field minecraftHome = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        minecraftHome.set(null, new File("build/terminal-io-check"));
        File aeConfigDirectory = new File("build/terminal-io-check/ae-config");
        aeConfigDirectory.mkdirs();
        appeng.core.AEConfig.instance = new appeng.core.AEConfig(new File(aeConfigDirectory, "AE2.cfg"));
        TileEntity.addMapping(TileEntitySharedTerminal.class, "terminal-io-regression");
        checkConfig();
        checkOreCardParity();
        checkLegacyOreMigration();
        checkPassiveItems();
        checkPassiveFluids();
        checkActiveTransfers();
        checkOutputPriority();
        checkManualSubtypeOutput();
        checkManualIdleRetry();
        checkBusAttachedFluidOutput();
        checkFluidPullCapacity();
        checkDirectFluidSelection();
        checkUncertainTransfers();
        System.out.println("Terminal IO regression passed: " + checks + " checks");
    }

    private static void checkConfig() {
        ItemStack iron = new ItemStack(Items.iron_ingot);
        ItemStack gold = new ItemStack(Items.gold_ingot);
        FluidStack water = new FluidStack(FluidRegistry.WATER, 1000);
        TerminalIoConfig config = new TerminalIoConfig();
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            check(!config.matchesOutput(face, iron) && !config.matchesOutput(face, water), "empty denies both types");
        }
        config.getOutputFilter(EAST).toggleItem(ItemKey.of(iron));
        config.getOutputFilter(WEST).toggleItem(ItemKey.of(gold));
        config.getOutputFilter(EAST).toggleFluid(FluidKey.of(water));
        check(config.matchesOutput(EAST, iron) && !config.matchesOutput(EAST, gold), "east item filter");
        check(config.matchesOutput(WEST, gold) && !config.matchesOutput(WEST, iron), "west item filter");
        check(config.matchesOutput(EAST, water) && !config.matchesOutput(WEST, water), "fluid filters independent");
        ItemStack tagged = iron.copy();
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound().setString("variant", "different");
        check(!config.matchesOutput(EAST, tagged), "exact item NBT filter");
        FluidStack taggedWater = water.copy();
        taggedWater.tag = tagged.getTagCompound();
        check(!config.matchesOutput(EAST, taggedWater), "exact fluid NBT filter");
        config.getOutputFilter(EAST).clear(false);
        check(!config.matchesOutput(EAST, iron) && config.matchesOutput(EAST, water), "clear only selected type");
        OreDictionary.registerOre("crushedTerminalIoCheck", new ItemStack(Items.apple));
        OreDictionary.registerOre("crushedPurifiedTerminalIoCheck", new ItemStack(Items.carrot));
        config.getOutputFilter(EAST).togglePreset(Preset.CRUSHED);
        check(config.matchesOutput(EAST, new ItemStack(Items.apple)), "ore preset matches");
        check(config.matchesOutput(EAST, new ItemStack(Items.carrot)), "AE2 crushed wildcard includes purified ores");
        check(!config.matchesOutput(WEST, new ItemStack(Items.apple)), "ore cache isolated by face");
        config.getOutputFilter(EAST).clear(false);
        check(!config.matchesOutput(EAST, new ItemStack(Items.apple)), "clearing invalidates ore cache");
        config.getOutputFilter(EAST).setOreFilter("crushedPurified*");
        check(config.matchesOutput(EAST, new ItemStack(Items.carrot)), "custom prefix matches");
        config.setMode(EAST, false, Mode.PUSH);
        TerminalIoConfig restored = new TerminalIoConfig();
        restored.readFromNbt(config.writeToNbt());
        check(restored.matchesOutput(EAST, new ItemStack(Items.carrot)), "NBT ore prefix round trip");
        check(restored.matchesOutput(WEST, gold) && !restored.matchesOutput(EAST, gold), "NBT face round trip");
        check(restored.matchesOutput(EAST, water) && restored.getMode(EAST, false) == Mode.PUSH, "NBT fluid and mode");
        NBTTagCompound legacy = config.getOutputFilter(WEST).writeToNbt();
        restored.readFromNbt(legacy);
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) check(restored.matchesOutput(face, gold), "legacy migration");
        restored.getOutputFilter(EAST).clear(false);
        check(restored.matchesOutput(WEST, gold) && !restored.matchesOutput(EAST, gold), "legacy filters deeply independent");
        NBTTagCompound missingFace = new NBTTagCompound();
        NBTTagList filters = new NBTTagList();
        filters.appendTag(legacy);
        missingFace.setTag("faceFilters", filters);
        restored.readFromNbt(missingFace);
        check(!restored.hasAnyFilter(), "malformed face cannot silently edit down face");
        restored.readFromNbt(null);
        check(!restored.hasAnyFilter() && !restored.hasAnyMode() && !restored.hasCustomRates(), "null read resets state");
    }

    private static void checkOreCardParity() {
        OreDictionary.registerOre("ingotTerminalIoCheck", new ItemStack(Items.apple));
        OreDictionary.registerOre("crushedCentrifugedTerminalIoCheck", new ItemStack(Items.potato));
        OreDictionary.registerOre("ingotIron", new ItemStack(Items.iron_ingot));
        OreDictionary.registerOre("ingotGold", new ItemStack(Items.gold_ingot));
        OreDictionary.registerOre("dustTerminalIoCheck", new ItemStack(Items.diamond));
        ItemStack tagged = new ItemStack(Items.apple);
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound().setString("ore-check", "variant");
        ItemStack[] stacks = { new ItemStack(Items.apple), new ItemStack(Items.carrot), new ItemStack(Items.potato),
            new ItemStack(Items.iron_ingot), new ItemStack(Items.gold_ingot), new ItemStack(Items.diamond),
            new ItemStack(Items.stick), tagged, new ItemStack(Items.iron_pickaxe, 1, 0),
            new ItemStack(Items.iron_pickaxe, 1, 20) };
        String[] expressions = { "", "   ", "ingot", "ingotIron", "INGOTIRON", "ingot*", "*Iron", "*Iron*", "*",
            "**", "crushed*", "crushed*&!crushedPurified*&!crushedCentrifuged*", "ingotIron|ingotGold",
            "crushed*&ingot*", "!ingot*", "!ingotIron&ingotIron", "ingotIron|ingotIron", "ingotIron|ingotGold&dust*",
            " ingotIron | ingotGold ", "ingotIron ingotGold", "ingotIron,ingotGold", "ingotIron;ingotGold", "*ot*Ir*",
            "ingot?", "^ingot(Iron|Gold)$", "(?i)^INGOTIRON$", "Iron$", "^crushed(?!Purified|Centrifuged)",
            "[", "(", "*Iron+", "!", "!!ingotIron", "|", "ingotIron||ingotGold", "ingotIron&", "!*" };
        TerminalOutputFilter filter = new TerminalOutputFilter();
        for (String expression : expressions) {
            filter.setOreFilter(expression);
            OreFilteredList card = new OreFilteredList(expression);
            for (int repeat = 0; repeat < 2; repeat++) {
                for (ItemStack stack : stacks) {
                    check(filter.matches(stack) == card.isListed(AEItemStack.create(stack)),
                        "AE2 card parity: " + expression + " / " + stack);
                }
            }
            TerminalOutputFilter restored = new TerminalOutputFilter();
            restored.readFromNbt(filter.writeToNbt());
            check(expression.equals(restored.getOreFilter()), "expression preserves exact text in NBT");
            for (ItemStack stack : stacks) {
                check(restored.matches(stack) == card.isListed(AEItemStack.create(stack)), "restored AE2 card parity");
            }
        }
        for (Preset preset : Preset.values()) {
            filter.clear();
            filter.togglePreset(preset);
            OreFilteredList card = new OreFilteredList(preset.getExpression());
            for (ItemStack stack : stacks) {
                check(filter.matches(stack) == card.isListed(AEItemStack.create(stack)), "preset AE2 card parity");
            }
        }
        filter.clear();
        filter.setOreFilter("ingotIron");
        check(filter.matches(new ItemStack(Items.iron_ingot)), "warm positive AE2 cache");
        filter.setOreFilter("ingotGold");
        check(!filter.matches(new ItemStack(Items.iron_ingot)) && filter.matches(new ItemStack(Items.gold_ingot)),
            "editing expression invalidates AE2 cache");
        filter.setOreFilter("[");
        filter.toggleItem(ItemKey.of(new ItemStack(Items.iron_ingot)));
        check(filter.matches(new ItemStack(Items.iron_ingot)) && !filter.matches(new ItemStack(Items.gold_ingot)),
            "invalid ore expression does not disable explicit item selection");
        check(!filter.matches((ItemStack) null), "null item denied");
        filter.clear(false);
        check(!filter.matches(new ItemStack(Items.iron_ingot)), "clearing expression and items invalidates cache");
        String longExpression = String.join("|", Collections.nCopies(80, "ingotIron"));
        filter.setOreFilter(longExpression);
        io.netty.buffer.ByteBuf payload = io.netty.buffer.Unpooled.buffer();
        try {
            cpw.mods.fml.common.network.ByteBufUtils.writeTag(payload, filter.writeToNbt());
            TerminalOutputFilter decoded = new TerminalOutputFilter();
            decoded.readFromNbt(cpw.mods.fml.common.network.ByteBufUtils.readTag(payload));
            check(longExpression.equals(decoded.getOreFilter()), "network NBT preserves expression longer than 64 chars");
            check(decoded.matches(new ItemStack(Items.iron_ingot)) && !decoded.matches(new ItemStack(Items.gold_ingot)),
                "network NBT preserves AE2 matching");
        } finally {
            payload.release();
        }
        ItemKey ironKey = ItemKey.of(new ItemStack(Items.iron_ingot));
        filter.setOreFilter("ingotIron");
        check(filter.matches(ironKey) && filter.matches(ironKey), "normalized key cache uses AE2 expression");
        ItemKey goldKey = ItemKey.of(new ItemStack(Items.gold_ingot));
        filter.toggleItem(goldKey);
        filter.toggleItem(ironKey);
        filter.removeItem(ironKey);
        check(!filter.matches(ironKey), "explicit cancellation overrides matching ore expression");
        TerminalOutputFilter cancelled = new TerminalOutputFilter();
        cancelled.readFromNbt(filter.writeToNbt());
        check(!cancelled.matches(ironKey), "expression and cancellation survive NBT together");
        cancelled.toggleItem(ironKey);
        check(cancelled.matches(ironKey), "reselecting cancelled item restores output");
        cancelled.removeItem(ironKey);
        cancelled.removeItem(goldKey);
        check(cancelled.getItems().isEmpty() && cancelled.matches(ironKey), "removing final manual entry restores pure ore matching");
        cancelled.toggleItem(goldKey);
        check(cancelled.matches(ironKey), "reselecting another item does not restore stale exclusions");
        cancelled.toggleItem(ironKey);
        cancelled.removeItem(ironKey);
        cancelled.toggleItem(goldKey);
        check(cancelled.matches(ironKey), "toggling off final manual entry clears exclusions");
        NBTTagCompound oldEmpty = filter.writeToNbt();
        oldEmpty.setTag("items", new NBTTagList());
        TerminalOutputFilter oldRestored = new TerminalOutputFilter();
        oldRestored.readFromNbt(oldEmpty);
        for (String expression : expressions) {
            oldRestored.setOreFilter(expression);
            OreFilteredList card = new OreFilteredList(expression);
            for (ItemStack stack : stacks) {
                check(oldRestored.matches(stack) == card.isListed(AEItemStack.create(stack)), "old empty manual list ignores persisted exclusions: " + expression);
            }
        }
        check(oldRestored.writeToNbt().getTagList("excludedItems", 10).tagCount() == 0, "empty manual list drops old exclusions on save");
    }

    private static void checkLegacyOreMigration() {
        String[][] prefixCases = { {}, { "crushed" }, { "crushedpurified" }, { "CRUSHED", "crushedpurifiedterminal" },
            { "crushed", "crushedpurified" }, { "ingot", "dust" }, { "crushedpurifiedterminal", "crushedpurifiedt" } };
        String[] names = { "crushedTerminalIoCheck", "crushedPurifiedTerminalIoCheck", "crushedCentrifugedTerminalIoCheck",
            "ingotTerminalIoCheck", "dustTerminalIoCheck", "ORETerminalIoCheck", "rawOreTerminalIoCheck", "unmatched" };
        net.minecraft.item.Item[] fixtures = new net.minecraft.item.Item[names.length];
        for (int i = 0; i < names.length; i++) {
            fixtures[i] = new net.minecraft.item.Item();
            net.minecraft.item.Item.itemRegistry.addObject(30000 + i, "terminal-ore-migration-" + i, fixtures[i]);
            OreDictionary.registerOre(names[i], new ItemStack(fixtures[i]));
        }
        for (int mask = 0; mask < (1 << Preset.values().length); mask++) {
            for (String[] prefixes : prefixCases) {
                NBTTagCompound legacy = new NBTTagCompound();
                NBTTagList presetTags = new NBTTagList();
                for (Preset preset : Preset.values()) {
                    if ((mask & (1 << preset.ordinal())) != 0) presetTags.appendTag(new NBTTagString(preset.name()));
                }
                legacy.setTag("presets", presetTags);
                NBTTagList prefixTags = new NBTTagList();
                for (String prefix : prefixes) prefixTags.appendTag(new NBTTagString(prefix));
                legacy.setTag("prefixes", prefixTags);
                TerminalOutputFilter migrated = new TerminalOutputFilter();
                migrated.readFromNbt(legacy);
                TerminalOutputFilter restored = new TerminalOutputFilter();
                restored.readFromNbt(migrated.writeToNbt());
                for (int i = 0; i < names.length; i++) {
                    boolean expected = legacyOreMatches(names[i], mask, prefixes);
                    check(migrated.matches(new ItemStack(fixtures[i])) == expected, "legacy longest-prefix migration");
                    check(restored.matches(new ItemStack(fixtures[i])) == expected, "legacy migration is idempotent");
                }
                check(!migrated.writeToNbt().hasKey("prefixes"), "new saves no longer contain legacy prefixes");
            }
        }
    }

    private static boolean legacyOreMatches(String name, int mask, String[] prefixes) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        String best = null;
        boolean enabled = false;
        for (Preset preset : Preset.values()) {
            for (String prefix : preset.getPrefixes()) {
                String candidate = prefix.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith(candidate) && (best == null || candidate.length() > best.length())) {
                    best = candidate;
                    enabled = (mask & (1 << preset.ordinal())) != 0;
                }
            }
        }
        for (String prefix : prefixes) {
            String candidate = prefix.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith(candidate) && (best == null || candidate.length() >= best.length())) {
                best = candidate;
                enabled = true;
            }
        }
        return best != null && enabled;
    }

    private static void checkPassiveItems() {
        SharedStorage storage = SharedStorageManager.getStorage();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        storage.insertItem(iron, 1000);
        storage.insertItem(gold, 1000);
        TileEntitySharedTerminal terminal = new TileEntitySharedTerminal();
        TerminalIoConfig config = terminal.getIo();
        check(terminal.getSizeInventory() == 1, "unconfigured terminal does not expand warehouse slots");
        config.setMode(EAST, false, Mode.PUSH);
        check(terminal.getAccessibleSlotsFromSide(EAST.ordinal()).length == 1, "PUSH plus empty list exposes input only");
        config.getOutputFilter(EAST).toggleItem(iron);
        config.getOutputFilter(WEST).toggleItem(gold);
        config.setMode(WEST, false, Mode.PUSH);
        int eastSlot = itemSlot(terminal, EAST, iron);
        int westSlot = itemSlot(terminal, WEST, gold);
        check(eastSlot != westSlot, "face output slots are distinct");
        check(itemSlot(terminal, EAST, gold) < 0 && itemSlot(terminal, WEST, iron) < 0, "no cross-face item leak");
        check(!terminal.canExtractItem(eastSlot, iron.prototype(), WEST.ordinal()), "wrong-side slot extraction denied");
        check(terminal.canExtractItem(eastSlot, iron.prototype(), EAST.ordinal()), "right-side extraction allowed");
        long before = storage.getItemAmount(iron);
        ItemStack original = terminal.getStackInSlot(eastSlot).copy();
        ItemStack extracted = terminal.decrStackSize(eastSlot, 1);
        check(extracted != null && extracted.stackSize == 1 && storage.getItemAmount(iron) == before - 1, "passive extraction conserves count");
        terminal.setInventorySlotContents(eastSlot, original);
        check(storage.getItemAmount(iron) == before, "failed hopper insertion restores exactly one item");
        original = terminal.getStackInSlot(eastSlot);
        original.stackSize -= 3;
        terminal.setInventorySlotContents(eastSlot, original);
        check(storage.getItemAmount(iron) == before - 3, "direct remaining-stack writeback conserves count");
        config.getOutputFilter(EAST).clear(false);
        check(terminal.getStackInSlot(eastSlot) == null && terminal.decrStackSize(eastSlot, 1) == null, "stale slot cannot bypass cleared filter");
        config.getOutputFilter(EAST).toggleItem(iron);
        config.setMode(EAST, false, Mode.OFF);
        check(terminal.getStackInSlot(eastSlot) == null, "OFF denies passive output even with whitelist");
        config.setMode(EAST, false, Mode.PULL);
        check(terminal.getStackInSlot(eastSlot) == null, "PULL denies passive output");
        before = storage.getItemAmount(gold);
        terminal.setInventorySlotContents(0, gold.prototype(7));
        check(storage.getItemAmount(gold) == before + 7, "input ignores output whitelist");
        check(terminal.canInsertItem(0, gold.prototype(), EAST.ordinal()), "input valid on every face");
        config.setMode(EAST, false, Mode.PUSH);
        config.getOutputFilter(EAST).toggleItem(gold);
        check(itemSlot(terminal, EAST, iron) >= 0 && itemSlot(terminal, EAST, gold) >= 0, "multiple matching passive item entries available");
        int stableSlot = itemSlot(terminal, EAST, gold);
        storage.extractItem(gold, storage.getItemAmount(gold));
        storage.insertItem(ItemKey.of(new ItemStack(Items.diamond)), 4);
        terminal.getSizeInventory();
        check(terminal.getStackInSlot(stableSlot) == null, "exhausted slot never retargets to a different item");
        storage.insertItem(gold, 1000);
        check(itemSlot(terminal, EAST, gold) == stableSlot, "restocked item retains stable slot");
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            config.setMode(face, false, Mode.PUSH);
            TerminalOutputFilter filter = config.getOutputFilter(face);
            if (!filter.containsItem(iron)) filter.toggleItem(iron);
            int slot = itemSlot(terminal, face, iron);
            check(slot >= 0 && terminal.canExtractItem(slot, iron.prototype(), face.ordinal()), "all six faces expose own slots");
            check(!terminal.canExtractItem(slot, iron.prototype(), face.getOpposite().ordinal()), "opposite face cannot extract alias");
        }
        config.getOutputFilter(WEST).clear(false);
        config.getOutputFilter(WEST).toggleItem(gold);
        NBTTagCompound tag = new NBTTagCompound();
        terminal.writeToNBT(tag);
        TileEntitySharedTerminal restored = new TileEntitySharedTerminal();
        restored.readFromNBT(tag);
        check(itemSlot(restored, WEST, gold) >= 0 && itemSlot(restored, WEST, iron) < 0, "terminal NBT preserves face gating");
    }

    private static void checkPassiveFluids() {
        SharedStorage storage = SharedStorageManager.getStorage();
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        FluidKey lava = FluidKey.of(new FluidStack(FluidRegistry.LAVA, 1));
        storage.insertFluid(water, 10000);
        storage.insertFluid(lava, 10000);
        TileEntitySharedTerminal terminal = new TileEntitySharedTerminal();
        TerminalIoConfig config = terminal.getIo();
        config.setMode(EAST, true, Mode.PUSH);
        check(terminal.drain(EAST, 1000, true) == null && !terminal.canDrain(EAST, null), "empty fluid whitelist denies passive output");
        config.getOutputFilter(EAST).toggleFluid(water);
        config.getOutputFilter(WEST).toggleFluid(lava);
        config.setMode(WEST, true, Mode.PUSH);
        long before = storage.getFluidAmount(water);
        FluidStack preview = terminal.drain(EAST, 500, false);
        check(preview.getFluid() == FluidRegistry.WATER && preview.amount == 500 && storage.getFluidAmount(water) == before, "fluid simulation is read only");
        check(terminal.drain(EAST, lava.prototype(500), true) == null, "specific drain cannot bypass face filter");
        NBTTagCompound fluidTag = new NBTTagCompound();
        fluidTag.setString("variant", "not allowed");
        check(terminal.drain(EAST, new FluidStack(FluidRegistry.WATER, 500, fluidTag), true) == null, "specific drain enforces NBT");
        FluidStack extracted = terminal.drain(EAST, 500, true);
        check(extracted.getFluid() == FluidRegistry.WATER && storage.getFluidAmount(water) == before - 500, "actual drain conserves fluid");
        check(terminal.drain(WEST, 500, false).getFluid() == FluidRegistry.LAVA, "west supplies its own fluid");
        check(terminal.drain(ForgeDirection.UNKNOWN, 1, true) == null, "unknown face cannot bypass filter");
        check(terminal.getTankInfo(EAST).length == 1 && terminal.getTankInfo(EAST)[0].fluid.getFluid() == FluidRegistry.WATER, "tank info only exposes allowed fluids");
        config.getOutputFilter(EAST).toggleFluid(lava);
        check(terminal.getTankInfo(EAST).length == 2, "all matching fluid entries exposed");
        config.getOutputFilter(EAST).clear(true);
        check(terminal.getTankInfo(EAST)[0].fluid == null && !terminal.canDrain(EAST, FluidRegistry.WATER), "cleared fluid filter hides output");
        before = storage.getFluidAmount(lava);
        check(terminal.fill(EAST, lava.prototype(37), false) == 37 && storage.getFluidAmount(lava) == before, "unfiltered input simulation");
        check(terminal.fill(EAST, lava.prototype(37), true) == 37 && storage.getFluidAmount(lava) == before + 37, "unfiltered input accepts non-whitelisted fluid");
        config.getOutputFilter(EAST).toggleFluid(water);
        config.setMode(EAST, true, Mode.OFF);
        check(terminal.drain(EAST, water.prototype(1), true) == null, "OFF denies typed fluid drain");
    }

    private static void checkActiveTransfers() throws Exception {
        SharedStorage storage = SharedStorageManager.getStorage();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        FluidKey lava = FluidKey.of(new FluidStack(FluidRegistry.LAVA, 1));
        TerminalIoConfig config = new TerminalIoConfig();
        InventoryBasic target = new InventoryBasic("target", true, 3);
        invoke("pushItems", new Class<?>[] { IInventory.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class }, target, WEST, config, EAST);
        check(target.getStackInSlot(0) == null, "active item output empty whitelist is blocked");
        config.getOutputFilter(EAST).toggleItem(iron);
        config.getOutputFilter(WEST).toggleItem(gold);
        long before = storage.getItemAmount(iron);
        invoke("pushItems", new Class<?>[] { IInventory.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class }, target, WEST, config, EAST);
        check(ItemKey.of(target.getStackInSlot(0)).equals(iron) && storage.getItemAmount(iron) == before - 16, "active east output respects filter and budget");
        target = new InventoryBasic("west", true, 3);
        invoke("pushItems", new Class<?>[] { IInventory.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class }, target, EAST, config, WEST);
        check(ItemKey.of(target.getStackInSlot(0)).equals(gold), "active west output does not use east whitelist");
        InventoryBasic source = new InventoryBasic("source", true, 1);
        source.setInventorySlotContents(0, gold.prototype(5));
        before = storage.getItemAmount(gold);
        invoke("pullItems", new Class<?>[] { IInventory.class, ForgeDirection.class, TerminalIoConfig.class }, source, WEST, config);
        check(source.getStackInSlot(0) == null && storage.getItemAmount(gold) == before + 5, "active input ignores mismatched item whitelist");
        TestTank tank = new TestTank();
        invoke("pushFluid", new Class<?>[] { IFluidHandler.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class }, tank, WEST, config, EAST);
        check(tank.tank.getFluid() == null, "active fluid output empty whitelist is blocked");
        config.getOutputFilter(EAST).toggleFluid(water);
        config.getOutputFilter(WEST).toggleFluid(lava);
        before = storage.getFluidAmount(water);
        invoke("pushFluid", new Class<?>[] { IFluidHandler.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class }, tank, WEST, config, EAST);
        check(tank.tank.getFluid().getFluid() == FluidRegistry.WATER && storage.getFluidAmount(water) == before - 1000, "active fluid output filter and budget");
        tank = new TestTank();
        tank.tank.fill(lava.prototype(900), true);
        before = storage.getFluidAmount(lava);
        invoke("pullFluid", new Class<?>[] { IFluidHandler.class, ForgeDirection.class, TerminalIoConfig.class }, tank, WEST, config);
        check(tank.tank.getFluid() == null && storage.getFluidAmount(lava) == before + 900, "active fluid input ignores output filter");
    }

    private static void checkBusAttachedFluidOutput() throws Exception {
        SharedStorage storage = SharedStorageManager.getStorage();
        storage.clear();
        StorageBusRegistry.clear();

        TestWorld world = new TestWorld();
        TestTank sourceTank = new TestTank();
        sourceTank.setWorldObj(world);
        sourceTank.xCoord = 1;
        sourceTank.yCoord = 0;
        sourceTank.zCoord = 0;
        sourceTank.tank.fill(new FluidStack(FluidRegistry.WATER, 3000), true);

        TileEntityStorageBus bus = new TileEntityStorageBus();
        bus.setWorldObj(world);
        bus.xCoord = 0;
        bus.yCoord = 0;
        bus.zCoord = 0;
        world.source = bus;
        world.target = sourceTank;
        bus.addFace(EAST);
        StorageBusRegistry.refresh(bus.getLink(EAST));

        TestTank target = new TestTank();
        TerminalIoConfig config = new TerminalIoConfig();
        config.getOutputFilter(WEST)
            .toggleFluid(FluidKey.of(new FluidStack(FluidRegistry.WATER, 1)));
        invoke(
            "pushFluid",
            new Class<?>[] { IFluidHandler.class, ForgeDirection.class, TerminalIoConfig.class,
                ForgeDirection.class },
            target,
            WEST,
            config,
            WEST);
        check(
            target.tank.getFluid() != null && target.tank.getFluidAmount() > 0
                && sourceTank.tank.getFluidAmount() < 3000,
            "active fluid output can drain a storage-bus-attached tank");

        bus.invalidate();
        StorageBusRegistry.clear();
        storage.clear();
    }

    private static void checkOutputPriority() throws Exception {
        SharedStorage storage = SharedStorageManager.getStorage();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        Class<?>[] pushTypes = { IInventory.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class };
        for (int selection = 0; selection < 2; selection++) {
            ItemKey manual = selection == 0 ? gold : iron;
            ItemKey ore = selection == 0 ? iron : gold;
            TerminalIoConfig config = new TerminalIoConfig();
            TerminalOutputFilter filter = config.getOutputFilter(EAST);
            filter.toggleItem(manual);
            filter.setOreFilter("ingot*");
            long manualBefore = storage.getItemAmount(manual);
            long oreBefore = storage.getItemAmount(ore);
            InventoryBasic target = new InventoryBasic("priority", true, 2);
            invoke("pushItems", pushTypes, target, WEST, config, EAST);
            check(manual.equals(ItemKey.of(target.getStackInSlot(0))) && target.getStackInSlot(0).stackSize == 16 && target.getStackInSlot(1) == null, "manual entries consume budget before ore entries in either warehouse order");
            check(storage.getItemAmount(manual) == manualBefore - 16 && storage.getItemAmount(ore) == oreBefore, "priority transfer conserves exact budget");

            TileEntitySharedTerminal terminal = new TileEntitySharedTerminal();
            TerminalIoConfig passive = terminal.getIo();
            passive.setMode(EAST, false, Mode.PUSH);
            passive.getOutputFilter(EAST).setOreFilter("ingot*");
            int oldManualSlot = itemSlot(terminal, EAST, manual);
            int oldOreSlot = itemSlot(terminal, EAST, ore);
            passive.getOutputFilter(EAST).toggleItem(manual);
            int[] slots = terminal.getAccessibleSlotsFromSide(EAST.ordinal());
            check(slots.length == 3 && slots[1] == oldManualSlot && slots[2] == oldOreSlot, "passive manual priority preserves stable slot identities");
            passive.getOutputFilter(EAST).removeItem(manual);
            check(itemSlot(terminal, EAST, manual) == oldManualSlot && itemSlot(terminal, EAST, ore) == oldOreSlot, "empty passive manual list exposes both ore matches with unchanged slots");
        }

        TerminalIoConfig config = new TerminalIoConfig();
        config.getOutputFilter(EAST).toggleItem(gold);
        config.getOutputFilter(EAST).setOreFilter("ingotIron");
        storage.extractItem(gold, storage.getItemAmount(gold));
        storage.insertItem(gold, 3);
        long ironBefore = storage.getItemAmount(iron);
        InventoryBasic target = new InventoryBasic("partial", true, 2);
        invoke("pushItems", pushTypes, target, WEST, config, EAST);
        check(gold.equals(ItemKey.of(target.getStackInSlot(0))) && target.getStackInSlot(0).stackSize == 3 && iron.equals(ItemKey.of(target.getStackInSlot(1))) && target.getStackInSlot(1).stackSize == 13, "manual shortage falls back to ore within remaining budget");
        check(storage.getItemAmount(gold) == 0 && storage.getItemAmount(iron) == ironBefore - 13, "partial priority transfer conserves storage");
        target = new InventoryBasic("missing", true, 1);
        invoke("pushItems", pushTypes, target, WEST, config, EAST);
        check(iron.equals(ItemKey.of(target.getStackInSlot(0))) && target.getStackInSlot(0).stackSize == 16, "missing manual stock does not block ore output");
        storage.insertItem(gold, 50);
        target = new InventoryBasic("reject-manual", true, 1);
        target.setInventorySlotContents(0, iron.prototype(63));
        ironBefore = storage.getItemAmount(iron);
        invoke("pushItems", pushTypes, target, WEST, config, EAST);
        check(target.getStackInSlot(0).stackSize == 64 && storage.getItemAmount(gold) == 50 && storage.getItemAmount(iron) == ironBefore - 1, "rejected manual item falls back without losing items or overspending budget");
        config.getOutputFilter(EAST).removeItem(gold);
        target = new InventoryBasic("pure-ore", true, 2);
        invoke("pushItems", pushTypes, target, WEST, config, EAST);
        check(iron.equals(ItemKey.of(target.getStackInSlot(0))) && target.getStackInSlot(1) == null && storage.getItemAmount(gold) == 50, "empty manual list outputs only expression matches");
    }

    private static void checkManualSubtypeOutput() throws Exception {
        net.minecraft.item.Item leaves = new net.minecraft.item.Item().setHasSubtypes(true);
        net.minecraft.item.Item.itemRegistry.addObject(30100, "cropsnh:materialLeaf-check", leaves);
        ItemStack[] selected = { new ItemStack(leaves, 1, 1), new ItemStack(leaves, 1, 9), new ItemStack(Items.coal, 1, 1) };
        SharedStorage storage = SharedStorageManager.getStorage();
        Class<?>[] pushTypes = { IInventory.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class };
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            TerminalIoConfig client = new TerminalIoConfig();
            client.setMode(face, false, Mode.PUSH);
            for (ItemStack stack : selected) {
                ItemKey key = ItemKey.of(stack);
                client.getOutputFilter(face).toggleItem(key);
                storage.insertItemManual(key, 64);
            }
            com.futa_gtnh.network.PacketStorageAction outgoing = com.futa_gtnh.network.PacketStorageAction.nbt(
                com.futa_gtnh.network.PacketStorageAction.SET_TERMINAL_IO, client.writeToNbt());
            com.futa_gtnh.network.PacketStorageAction incoming = new com.futa_gtnh.network.PacketStorageAction();
            io.netty.buffer.ByteBuf payload = io.netty.buffer.Unpooled.buffer();
            try { outgoing.toBytes(payload); incoming.fromBytes(payload); } finally { payload.release(); }
            TerminalIoConfig server = new TerminalIoConfig();
            server.readFromNbt(incoming.getLayoutTag());
            check(server.getOutputFilter(face).getOreFilter().isEmpty(), "manual-only packet has no ore expression");
            for (ItemStack stack : selected) {
                check(server.matchesOutput(face, stack), "manual subtype survives client packet and server read: " + stack.getItemDamage());
            }
            InventoryBasic target = new InventoryBasic("manual-subtype", true, 3);
            long before = storage.getItemTotal();
            invoke("pushItems", pushTypes, target, face.getOpposite(), server, face);
            check(target.getStackInSlot(0) != null && target.getStackInSlot(0).stackSize == 16, "manual-only subtype output consumes budget after network round trip");
            check(storage.getItemTotal() == before - 16, "manual-only subtype output conserves count");
            for (ItemStack stack : selected) {
                TerminalIoConfig one = new TerminalIoConfig();
                one.getOutputFilter(face).toggleItem(ItemKey.of(stack));
                TerminalIoConfig restored = new TerminalIoConfig();
                restored.readFromNbt(one.writeToNbt());
                IInventory bus = inputBus(face.getOpposite());
                long available = storage.getItemAmount(stack);
                invoke("pushItems", pushTypes, bus, face.getOpposite(), restored, face);
                check(bus.getStackInSlot(0) != null && ItemKey.of(bus.getStackInSlot(0)).equals(ItemKey.of(stack)), "real GT input bus accepts selected subtype without ore expression");
                check(storage.getItemAmount(stack) == available - 16, "real GT manual output conserves subtype amount");
            }
        }
    }

    private static IInventory inputBus(ForgeDirection front) {
        gregtech.api.metatileentity.implementations.MTEHatchInputBus bus = new gregtech.api.metatileentity.implementations.MTEHatchInputBus("terminal-check", 1, new String[0], null);
        bus.disableLimited = false;
        gregtech.api.interfaces.tileentity.IGregTechTileEntity tile = (gregtech.api.interfaces.tileentity.IGregTechTileEntity) java.lang.reflect.Proxy.newProxyInstance(
            IInventory.class.getClassLoader(), new Class<?>[] { gregtech.api.interfaces.tileentity.IGregTechTileEntity.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getFrontFacing": return front;
                    case "getMetaTileEntity": return bus;
                    case "getSizeInventory": return bus.getSizeInventory();
                    case "getInventoryStackLimit": return 64;
                    case "getAccessibleSlotsFromSide": return new int[0];
                    case "getStackInSlot": return bus.getStackInSlot((Integer) args[0]);
                    case "setInventorySlotContents": bus.setInventorySlotContents((Integer) args[0], (ItemStack) args[1]); return null;
                    case "canInsertItem": return bus.allowPutStack((gregtech.api.interfaces.tileentity.IGregTechTileEntity) proxy, (Integer) args[0], ForgeDirection.getOrientation((Integer) args[2]), (ItemStack) args[1]);
                    case "markDirty": return null;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return 0L;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
        bus.setBaseMetaTileEntity(tile);
        return tile;
    }

    private static void checkManualIdleRetry() throws Exception {
        boolean previousBackoff = com.futa_gtnh.Config.ioIdleBackoff;
        Field storageField = SharedStorageManager.class.getDeclaredField("storage");
        storageField.setAccessible(true);
        SharedStorage previousStorage = SharedStorageManager.getStorage();
        CountingStorage storage = new CountingStorage();
        storageField.set(null, storage);
        com.futa_gtnh.Config.ioIdleBackoff = true;
        try {
            for (boolean node : new boolean[] { false, true }) {
                for (boolean withOre : new boolean[] { false, true }) {
                    TestWorld world = new TestWorld();
                    TileEntity source = node ? new TileEntityIoNode() : new TileEntitySharedTerminal();
                    TerminalIoConfig io = node ? ((TileEntityIoNode) source).getIo() : ((TileEntitySharedTerminal) source).getIo();
                    TestInventory target = new TestInventory();
                    world.source = source;
                    world.target = target;
                    source.setWorldObj(world);
                    target.setWorldObj(world);
                    io.setMode(EAST, false, Mode.PUSH);
                    ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
                    SharedStorageManager.getStorage().insertItemManual(iron, 1000);
                    io.getOutputFilter(EAST).toggleItem(iron);
                    if (withOre) io.getOutputFilter(EAST).setOreFilter("ingotGold");
                    target.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond, 64));
                    for (int tick = 0; tick < 1000; tick++) source.updateEntity();
                    Field idle = source.getClass().getDeclaredField("idleRounds");
                    Field timer = source.getClass().getDeclaredField("ioTimer");
                    idle.setAccessible(true);
                    timer.setAccessible(true);
                    check(idle.getInt(source) == TerminalIoEngine.IDLE_BACKOFF_STEPS.length, "full target reaches maximum scan backoff");
                    int scansBeforeRetry = storage.scans;
                    timer.setInt(source, 0);
                    target.inventory.setInventorySlotContents(0, null);
                    for (int tick = 1; tick < io.getIntervalTicks(); tick++) {
                        source.updateEntity();
                        check(target.inventory.getStackInSlot(0) == null, "manual retry never exceeds configured frequency");
                    }
                    source.updateEntity();
                    check(target.inventory.getStackInSlot(0) != null && target.inventory.getStackInSlot(0).stackSize == 16, "manual output resumes within configured interval after target clears: node=" + node + ", ore=" + withOre);
                    check(idle.getInt(source) == 0, "successful manual retry wakes normal IO");
                    check(storage.scans == scansBeforeRetry, "manual idle retries do not scan warehouse or ore matches");
                    idle.setInt(source, 6);
                    timer.setInt(source, 20);
                    if (node) ((TileEntityIoNode) source).onIoChanged();
                    else ((TileEntitySharedTerminal) source).onIoChanged();
                    check(idle.getInt(source) == 0 && timer.getInt(source) == 0, "editing IO clears previous idle delay");
                }
            }
        } finally {
            com.futa_gtnh.Config.ioIdleBackoff = previousBackoff;
            storageField.set(null, previousStorage);
        }
    }

    private static final class CountingStorage extends SharedStorage {
        int scans;
        @Override public synchronized java.util.List<java.util.Map.Entry<ItemKey, Long>> snapshotItemsCached() {
            scans++;
            return super.snapshotItemsCached();
        }
    }

    private static final class TestWorld extends net.minecraft.world.World {
        TileEntity source;
        TileEntity target;
        TestWorld() {
            super(new net.minecraft.world.storage.SaveHandlerMP(), "terminal-io-check",
                new net.minecraft.world.WorldSettings(0, net.minecraft.world.WorldSettings.GameType.SURVIVAL, false, false, net.minecraft.world.WorldType.DEFAULT),
                new net.minecraft.world.WorldProviderSurface(), new net.minecraft.profiler.Profiler());
        }
        @Override protected net.minecraft.world.chunk.IChunkProvider createChunkProvider() { return null; }
        @Override protected int func_152379_p() { return 0; }
        @Override public net.minecraft.entity.Entity getEntityByID(int id) { return null; }
        @Override public TileEntity getTileEntity(int x, int y, int z) { return y == 0 && z == 0 ? x == 0 ? source : x == 1 ? target : null : null; }
        @Override public boolean blockExists(int x, int y, int z) { return true; }
        @Override public int getBlockMetadata(int x, int y, int z) { return 0; }
        @Override public net.minecraft.block.Block getBlock(int x, int y, int z) { return net.minecraft.init.Blocks.air; }
        @Override public void markBlockForUpdate(int x, int y, int z) {}
        @Override public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}
    }

    private static final class TestInventory extends TileEntity implements IInventory {
        final InventoryBasic inventory = new InventoryBasic("target", true, 1);
        boolean throwAfterWrite;
        boolean rejectWrites;
        public int getSizeInventory() { return inventory.getSizeInventory(); }
        public ItemStack getStackInSlot(int slot) { return inventory.getStackInSlot(slot); }
        public ItemStack decrStackSize(int slot, int amount) { return inventory.decrStackSize(slot, amount); }
        public ItemStack getStackInSlotOnClosing(int slot) { return inventory.getStackInSlotOnClosing(slot); }
        public void setInventorySlotContents(int slot, ItemStack stack) {
            if (!rejectWrites) inventory.setInventorySlotContents(slot, stack);
            if (throwAfterWrite) throw new IllegalStateException("fixture setter applied then failed");
        }
        public String getInventoryName() { return "target"; }
        public boolean hasCustomInventoryName() { return true; }
        public int getInventoryStackLimit() { return 64; }
        @Override public void markDirty() {}
        public boolean isUseableByPlayer(net.minecraft.entity.player.EntityPlayer player) { return true; }
        public void openInventory() {}
        public void closeInventory() {}
        public boolean isItemValidForSlot(int slot, ItemStack stack) { return true; }
    }

    private static int itemSlot(TileEntitySharedTerminal terminal, ForgeDirection face, ItemKey key) {
        for (int slot : terminal.getAccessibleSlotsFromSide(face.ordinal())) {
            if (key.equals(ItemKey.of(terminal.getStackInSlot(slot)))) return slot;
        }
        return -1;
    }

    private static void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = TerminalIoEngine.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void checkFluidPullCapacity() throws Exception {
        StorageBusRegistry.clear();
        SharedStorage storage = SharedStorageManager.getStorage();
        storage.clear();
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        TerminalIoConfig config = new TerminalIoConfig();
        TestTank tank = new TestTank();
        tank.allowFill = false;
        tank.tank.fill(water.prototype(1000), true);
        storage.setFluidLimit(water, 0);
        Class<?>[] signature = { IFluidHandler.class, ForgeDirection.class, TerminalIoConfig.class };
        invoke("pullFluid", signature, tank, WEST, config);
        check(tank.tank.getFluidAmount() == 1000 && storage.getFluidAmount(water) == 0,
            "full storage never drains a source that cannot be refilled");
        storage.setFluidLimit(water, 300);
        invoke("pullFluid", signature, tank, WEST, config);
        check(tank.tank.getFluidAmount() == 700 && storage.getFluidAmount(water) == 300,
            "partial capacity drains only the amount that can enter the pool");
        storage.clearFluidLimit(water);
        invoke("pullFluid", signature, tank, WEST, config);
        check(tank.tank.getFluidAmount() == 0 && storage.getFluidAmount(water) == 1000,
            "drain-only source still transfers normally when capacity is available");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("variant", "tagged-water");
        FluidKey tagged = FluidKey.of(FluidRegistry.WATER, tag);
        tank.tank.fill(tagged.prototype(1000), true);
        storage.setFluidLimit(tagged, 200);
        invoke("pullFluid", signature, tank, WEST, config);
        check(storage.getFluidAmount(tagged) == 200 && tank.tank.getFluidAmount() == 800
            && tagged.equals(FluidKey.of(tank.tank.getFluid())), "partial transfer preserves fluid NBT");
        storage.clearFluidLimit(tagged);
    }

    private static void checkDirectFluidSelection() throws Exception {
        StorageBusRegistry.clear();
        Field active = SharedStorageManager.class.getDeclaredField("storage");
        active.setAccessible(true);
        Object previous = active.get(null);
        ChannelCountingStorage storage = new ChannelCountingStorage();
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        FluidKey lava = FluidKey.of(FluidRegistry.LAVA, null);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        storage.insertItemManual(iron, 100);
        storage.insertFluidManual(water, 2000);
        storage.insertFluidManual(lava, 4000);
        for (int i = 0; i < 100; i++) {
            NBTTagCompound variant = new NBTTagCompound();
            variant.setInteger("variant", i);
            storage.insertFluidManual(FluidKey.of(FluidRegistry.WATER, variant), 1000);
        }
        try {
            active.set(null, storage);
            TerminalIoConfig config = new TerminalIoConfig();
            config.getOutputFilter(EAST).toggleFluid(water);
            TestTank target = new TestTank();
            Class<?>[] signature = { IFluidHandler.class, ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class };
            invoke("pushFluid", signature, target, WEST, config, EAST);
            check(target.tank.getFluidAmount() == 1000 && storage.getFluidAmount(water) == 1000,
                "direct fluid lookup retains throughput and conservation");
            check(storage.fluidSnapshots == 0, "one selected fluid never enumerates unrelated warehouse fluids");
            target.tank.fill(water.prototype(3000), true);
            invoke("pushFluid", signature, target, WEST, config, EAST);
            check(storage.getFluidAmount(water) == 1000 && storage.fluidSnapshots == 0,
                "full destination still avoids a full warehouse scan");
            config.getOutputFilter(EAST).toggleFluid(lava);
            TestTank multiple = new TestTank();
            invoke("pushFluid", signature, multiple, WEST, config, EAST);
            check(water.equals(FluidKey.of(multiple.tank.getFluid())) && multiple.tank.getFluidAmount() == 1000
                && storage.getFluidAmount(lava) == 4000, "multiple fluids preserve the previous warehouse priority");
            check(storage.fluidSnapshots == 1, "multiple fluids use the ordered snapshot fallback");
            TileEntitySharedTerminal terminal = new TileEntitySharedTerminal();
            terminal.getIo().setMode(EAST, false, Mode.PUSH);
            terminal.getIo().getOutputFilter(EAST).toggleItem(iron);
            terminal.getSizeInventory();
            int before = storage.itemQueries;
            storage.insertFluidManual(lava, 1);
            terminal.getSizeInventory();
            check(storage.itemQueries == before, "fluid changes do not rescan the terminal's item output index");
            storage.insertItemManual(iron, 1);
            terminal.getSizeInventory();
            check(storage.itemQueries == before + 1, "item changes still refresh the terminal's output index");
        } finally {
            active.set(null, previous);
        }
    }

    private static final class ChannelCountingStorage extends SharedStorage {
        private int itemQueries;
        private int fluidSnapshots;
        @Override
        public synchronized java.util.List<java.util.Map.Entry<ItemKey, Long>> snapshotItemsCached() {
            itemQueries++;
            return super.snapshotItemsCached();
        }
        @Override
        public synchronized java.util.List<java.util.Map.Entry<FluidKey, Long>> snapshotFluidsCached() {
            fluidSnapshots++;
            return super.snapshotFluidsCached();
        }
    }

    private static void checkUncertainTransfers() throws Exception {
        StorageBusRegistry.clear();
        SharedStorage storage = SharedStorageManager.getStorage();
        storage.setNetworkAccess(null);
        storage.clear();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        TerminalIoConfig config = new TerminalIoConfig();
        Class<?>[] itemPull = { IInventory.class, ForgeDirection.class, TerminalIoConfig.class };
        Class<?>[] fluidPull = { IFluidHandler.class, ForgeDirection.class, TerminalIoConfig.class };
        try {
            TestInventory source = new TestInventory();
            source.inventory.setInventorySlotContents(0, iron.prototype(10));
            source.throwAfterWrite = true;
            invoke("pullItems", itemPull, source, WEST, config);
            check(source.getStackInSlot(0) == null && storage.getItemAmountPool(iron) == 10
                && !storage.hasPendingItemTransfer(iron), "setter throwing after removal is reconciled before one-time delivery");
            storage.clear();
            source.throwAfterWrite = false;
            source.rejectWrites = true;
            source.inventory.setInventorySlotContents(0, iron.prototype(10));
            invoke("pullItems", itemPull, source, WEST, config);
            check(source.getStackInSlot(0).stackSize == 10 && storage.getItemAmountPool(iron) == 0
                && !storage.hasPendingItemTransfer(iron), "source refusing removal clears provisional escrow without creating stock");

            FaultNetwork network = new FaultNetwork();
            network.throwOnInsert = true;
            source.rejectWrites = false;
            storage.setNetworkAccess(network);
            invoke("pullItems", itemPull, source, WEST, config);
            check(source.getStackInSlot(0) == null && network.itemAccepted == 4 && network.itemCalls == 1
                && storage.getItemAmountPool(iron) == 0 && storage.snapshotCraftingItems().get(0).getValue() == 10,
                "uncertain AE acceptance stays quarantined without automatic source or pool refund");
            SharedStorage restored = new SharedStorage();
            restored.readFromNbt(storage.writeToNbt());
            check(restored.hasPendingItemTransfer(iron) && restored.getItemAmountPool(iron) == 0,
                "uncertain IO acceptance persists as unavailable escrow across reload");
            source.inventory.setInventorySlotContents(0, iron.prototype(5));
            invoke("pullItems", itemPull, source, WEST, config);
            check(source.getStackInSlot(0).stackSize == 5 && network.itemCalls == 1,
                "an unresolved item key is not retried by later IO rounds");

            storage.clear();
            network = new FaultNetwork();
            storage.setNetworkAccess(network);
            source.inventory.setInventorySlotContents(0, iron.prototype(10));
            invoke("pullItems", itemPull, source, WEST, config);
            check(network.itemCalls == 1 && network.itemAccepted == 4 && storage.getItemAmountPool(iron) == 6
                && !storage.hasPendingItemTransfer(iron), "known partial AE acceptance refunds only rejected items directly to raw pool");
            storage.clear();
            storage.poolInsertItem(iron, Long.MAX_VALUE, true);
            network = new FaultNetwork();
            network.rejectAll = true;
            storage.setNetworkAccess(network);
            source.inventory.setInventorySlotContents(0, iron.prototype(10));
            invoke("pullItems", itemPull, source, WEST, config);
            check(storage.getItemAmountPool(iron) == Long.MAX_VALUE
                && storage.snapshotCraftingItems().get(0).getValue() == 10 && source.getStackInSlot(0) == null,
                "known rejected items remain in escrow if durable raw recovery capacity is full");

            storage.clear();
            network = new FaultNetwork();
            network.throwOnInsert = true;
            storage.setNetworkAccess(network);
            TestTank fluidSource = new TestTank();
            fluidSource.tank.fill(water.prototype(10), true);
            invoke("pullFluid", fluidPull, fluidSource, WEST, config);
            check(fluidSource.tank.getFluidAmount() == 0 && network.fluidAccepted == 4 && network.fluidCalls == 1
                && storage.getFluidAmountPool(water) == 0 && storage.snapshotCraftingFluids().get(0).getValue() == 10,
                "uncertain AE fluid acceptance is not reinjected or returned to source");
            fluidSource.tank.fill(water.prototype(5), true);
            invoke("pullFluid", fluidPull, fluidSource, WEST, config);
            check(fluidSource.tank.getFluidAmount() == 5 && network.fluidCalls == 1,
                "unresolved fluid keys stop automatic retries");
            storage.clear();
            network = new FaultNetwork();
            storage.setNetworkAccess(network);
            fluidSource.tank.drain(4000, true);
            fluidSource.tank.fill(water.prototype(10), true);
            invoke("pullFluid", fluidPull, fluidSource, WEST, config);
            check(network.fluidAccepted == 4 && network.fluidCalls == 1 && storage.getFluidAmountPool(water) == 6
                && !storage.hasPendingFluidTransfer(water), "known fluid remainder is recovered raw without a second network callback");

            storage.setNetworkAccess(null);
            storage.clear();
            storage.poolInsertItem(iron, 10, true);
            TestInventory destination = new TestInventory();
            destination.throwAfterWrite = true;
            invoke("pushItem", new Class<?>[] { IInventory.class, ForgeDirection.class, SharedStorage.class, ItemKey.class, long.class, int.class },
                destination, WEST, storage, iron, 10L, 10);
            check(destination.getStackInSlot(0).stackSize == 10 && storage.getItemAmountPool(iron) == 0
                && storage.snapshotCraftingItems().get(0).getValue() == 10,
                "target throwing after accepting items does not cause an automatic duplicate refund");
            storage.clear();
            storage.poolInsertFluid(water, 10, true);
            TestTank fluidDestination = new TestTank();
            fluidDestination.throwAfterFill = true;
            invoke("pushFluid", new Class<?>[] { IFluidHandler.class, ForgeDirection.class, SharedStorage.class, FluidKey.class, long.class, int.class },
                fluidDestination, WEST, storage, water, 10L, 10);
            check(fluidDestination.tank.getFluidAmount() == 10 && storage.getFluidAmountPool(water) == 0
                && storage.snapshotCraftingFluids().get(0).getValue() == 10,
                "target throwing after accepting fluid preserves uncertainty rather than duplicating fluid");
        } finally {
            storage.setNetworkAccess(null);
            storage.clear();
        }
    }

    private static final class FaultNetwork implements StorageNetworkAccess {
        boolean throwOnInsert;
        boolean rejectAll;
        int itemCalls, fluidCalls;
        long itemAccepted, fluidAccepted;
        @Override public long itemAmount(ItemKey key) { return 0; }
        @Override public long fluidAmount(FluidKey key) { return 0; }
        @Override public long insertItem(ItemKey key, long amount, boolean manual, boolean direct) {
            itemCalls++;
            long accepted = rejectAll ? 0 : Math.min(4, amount);
            itemAccepted += accepted;
            if (throwOnInsert) throw new IllegalStateException("fixture AE item acceptance unknown");
            return accepted;
        }
        @Override public long insertFluid(FluidKey key, long amount, boolean manual, boolean direct) {
            fluidCalls++;
            long accepted = rejectAll ? 0 : Math.min(4, amount);
            fluidAccepted += accepted;
            if (throwOnInsert) throw new IllegalStateException("fixture AE fluid acceptance unknown");
            return accepted;
        }
        @Override public long extractItem(ItemKey key, long amount, TileEntity excluded) { return 0; }
        @Override public long extractFluid(FluidKey key, long amount, TileEntity excluded) { return 0; }
        @Override public long itemSpace(ItemKey key, boolean manual) { return 1000; }
        @Override public long fluidSpace(FluidKey key, boolean manual) { return 1000; }
        @Override public java.util.List<java.util.Map.Entry<ItemKey, Long>> items() { return Collections.emptyList(); }
        @Override public java.util.List<java.util.Map.Entry<FluidKey, Long>> fluids() { return Collections.emptyList(); }
        @Override public int itemRevision() { return 0; }
        @Override public int fluidRevision() { return 0; }
    }

    private static final class TestTank extends TileEntity implements IFluidHandler {
        private final FluidTank tank = new FluidTank(4000);
        private boolean allowFill = true;
        private boolean throwAfterFill;
        public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
            int result = allowFill ? tank.fill(resource, doFill) : 0;
            if (doFill && throwAfterFill) throw new IllegalStateException("fixture fill accepted then failed");
            return result;
        }
        public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
            return tank.getFluid() != null && tank.getFluid().isFluidEqual(resource) ? tank.drain(resource.amount, doDrain) : null;
        }
        public FluidStack drain(ForgeDirection from, int amount, boolean doDrain) { return tank.drain(amount, doDrain); }
        public boolean canFill(ForgeDirection from, Fluid fluid) { return allowFill; }
        public boolean canDrain(ForgeDirection from, Fluid fluid) { return true; }
        public FluidTankInfo[] getTankInfo(ForgeDirection from) { return new FluidTankInfo[] { tank.getInfo() }; }
    }
}
