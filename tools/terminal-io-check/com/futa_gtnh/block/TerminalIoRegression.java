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
        filter.toggleItem(ironKey);
        filter.removeItem(ironKey);
        check(!filter.matches(ironKey), "explicit cancellation overrides matching ore expression");
        TerminalOutputFilter cancelled = new TerminalOutputFilter();
        cancelled.readFromNbt(filter.writeToNbt());
        check(!cancelled.matches(ironKey), "expression and cancellation survive NBT together");
        cancelled.toggleItem(ironKey);
        check(cancelled.matches(ironKey), "reselecting cancelled item restores output");
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

    private static final class TestTank implements IFluidHandler {
        private final FluidTank tank = new FluidTank(4000);
        public int fill(ForgeDirection from, FluidStack resource, boolean doFill) { return tank.fill(resource, doFill); }
        public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
            return tank.getFluid() != null && tank.getFluid().isFluidEqual(resource) ? tank.drain(resource.amount, doDrain) : null;
        }
        public FluidStack drain(ForgeDirection from, int amount, boolean doDrain) { return tank.drain(amount, doDrain); }
        public boolean canFill(ForgeDirection from, Fluid fluid) { return true; }
        public boolean canDrain(ForgeDirection from, Fluid fluid) { return true; }
        public FluidTankInfo[] getTankInfo(ForgeDirection from) { return new FluidTankInfo[] { tank.getInfo() }; }
    }
}
