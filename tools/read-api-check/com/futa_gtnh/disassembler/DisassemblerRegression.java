package com.futa_gtnh.disassembler;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.Config;
import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.exchange.InventoryExchange;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.Loader;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.util.GTOreDictUnificator;

public final class DisassemblerRegression {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Bootstrap.func_151354_b();
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        java.lang.reflect.Field logSide = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side");
        logSide.setAccessible(true);
        logSide.set(null, cpw.mods.fml.relauncher.Side.SERVER);
        batchesAndIdentity();
        capacityAndAtomicProcessing();
        conflicts();
        oreRefunds();
        craftingGrids();
        machineInterfaces();
        mainInventoryTransfer();
        System.out.println("Disassembler/terminal regression: " + assertions + " assertions passed");
    }

    private static DisassemblyRecipe recipe(ItemStack output, List<ItemStack> items, List<FluidStack> fluids) {
        return new DisassemblyRecipe(output, items, fluids, "regression");
    }
    private static void batchesAndIdentity() {
        DisassemblyRecipe batch = recipe(new ItemStack(Items.diamond, 4), Arrays.asList(new ItemStack(Items.iron_ingot, 1)), Arrays.asList(new FluidStack(FluidRegistry.WATER, 144)));
        check(batch.inputCount == 4, "fractional component keeps whole batch");
        check(batch.itemOutputs().get(0).stackSize == 1 && batch.fluidOutputs().get(0).amount == 144, "exact materials");
        DisassemblyBuffer buffer = new DisassemblyBuffer(36, 8, 64000);
        buffer.inventory[0] = new ItemStack(Items.diamond, 3);
        check(!buffer.start(batch) && buffer.inventory[0].stackSize == 3, "insufficient batch untouched");
        NBTTagCompound content = new NBTTagCompound(); content.setInteger("energy", 1000);
        buffer.inventory[0] = new ItemStack(Items.diamond, 4); buffer.inventory[0].setTagCompound(content);
        check(!buffer.start(batch), "extra state NBT rejected");
        DisassemblyRecipe divisible = recipe(new ItemStack(Items.diamond, 4), Arrays.asList(new ItemStack(Items.iron_ingot, 8)), Arrays.asList(new FluidStack(FluidRegistry.WATER, 144)));
        check(divisible.inputCount == 1 && divisible.itemOutputs().get(0).stackSize == 2 && divisible.fluidOutputs().get(0).amount == 36, "exact gcd normalization");
        DisassemblyRecipe catalyst = recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.iron_ingot, 2), new ItemStack(Items.redstone, 0)), Collections.<FluidStack>emptyList());
        check(catalyst.itemOutputs().size() == 1, "nonconsumable omitted");
        expectRejected(() -> recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.water_bucket)), Collections.<FluidStack>emptyList()), "container remainder rejected");
        expectRejected(() -> recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.iron_ingot, 1, 32767)), Collections.<FluidStack>emptyList()), "wildcard rejected");
    }

    private static void capacityAndAtomicProcessing() {
        List<ItemStack> ingredients = new ArrayList<>();
        ingredients.add(new ItemStack(Items.iron_ingot, 130));
        ingredients.add(new ItemStack(Items.ender_pearl, 33));
        ingredients.add(new ItemStack(Items.wooden_sword, 4));
        for (int i = 0; i < 40; i++) ingredients.add(new ItemStack(Items.dye, 1, i));
        NBTTagCompound alternate = new NBTTagCompound(); alternate.setString("grade", "special");
        List<FluidStack> fluids = Arrays.asList(new FluidStack(FluidRegistry.WATER, 80000), new FluidStack(FluidRegistry.LAVA, 144), new FluidStack(FluidRegistry.WATER, 90000, alternate));
        DisassemblyRecipe large = recipe(new ItemStack(Items.diamond), ingredients, fluids);
        check(large.requiredItemSlots() == 50, "1/16/64 stack limits and more than 36 real slots");
        DisassemblyBuffer buffer = new DisassemblyBuffer(50, 10, 90000);
        buffer.inventory[0] = new ItemStack(Items.diamond);
        for (int i = 2; i < buffer.inventory.length; i++) buffer.inventory[i] = new ItemStack(Blocks.cobblestone, 64);
        check(!buffer.start(large) && buffer.inventory[0] != null && buffer.inventory[1] == null, "full items rejects before consuming");
        Arrays.fill(buffer.inventory, 2, buffer.inventory.length, null);
        for (int i = 0; i < buffer.tanks.length; i++) buffer.tanks[i].setFluid(new FluidStack(FluidRegistry.LAVA, 90000));
        check(!buffer.start(large) && buffer.inventory[0] != null, "full fluids rejects before consuming");
        for (int i = 0; i < buffer.tanks.length; i++) buffer.tanks[i].setFluid(null);
        check(buffer.start(large) && buffer.inventory[0] == null && buffer.inventory[1] != null, "input escrowed once");
        check(!buffer.advance(false) && buffer.progress == 0, "power loss pauses");
        for (int i = 0; i < 17; i++) check(buffer.advance(true), "processing tick");
        check(buffer.inventory[2] == null && buffer.tanks[0].getFluid() == null, "no partial output");
        NBTTagCompound saved = buffer.writeToNbt();
        DisassemblyBuffer restored = new DisassemblyBuffer(36, 8, 64000);
        restored.readFromNbt(saved);
        check(restored.inventory.length == 52 && restored.tanks.length == 10 && restored.tankCapacity == 90000, "persisted sizes never shrink");
        check(restored.progress == 17 && restored.inventory[1] != null, "pending task and escrow restored");
        restored.inventory[2] = new ItemStack(Blocks.cobblestone, 64);
        check(!restored.advance(true) && restored.progress == 17, "blocked completion pauses without partial commit");
        restored.inventory[2] = null;
        for (int i = 17; i < 40; i++) check(restored.advance(true), "restored processing tick");
        check(!restored.hasTask() && restored.inventory[1] == null && restored.progress == 0, "one complete batch at exactly 40 paid ticks");
        check(!restored.advance(true), "cannot settle twice");
        long iron = 0, pearls = 0, swords = 0;
        for (int i = 2; i < restored.inventory.length; i++) {
            ItemStack stack = restored.inventory[i]; if (stack == null) continue;
            check(stack.stackSize <= stack.getMaxStackSize(), "legal output stack");
            if (stack.getItem() == Items.iron_ingot) iron += stack.stackSize;
            if (stack.getItem() == Items.ender_pearl) pearls += stack.stackSize;
            if (stack.getItem() == Items.wooden_sword) swords += stack.stackSize;
        }
        check(iron == 130 && pearls == 33 && swords == 4, "all large and unstackable outputs conserved");
        check(restored.tanks[0].getFluidAmount() == 80000 && restored.tanks[1].getFluidAmount() == 144 && restored.tanks[2].getFluidAmount() == 90000, "all fluids conserved including distinct NBT");
        DisassemblyBuffer fluidsOnly = new DisassemblyBuffer(36, 8, 64000);
        NBTTagCompound machineItem = new NBTTagCompound(); restored.writeFluids(machineItem);
        fluidsOnly.readFromNbt(machineItem);
        check(fluidsOnly.inventory[1] == null && !fluidsOnly.hasTask() && fluidsOnly.tanks[2].getFluidAmount() == 90000, "machine item carries fluids only, no escrow duplication");
    }

    private static void conflicts() {
        DisassemblyRecipe a = recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.iron_ingot, 1)), Collections.<FluidStack>emptyList());
        DisassemblyRecipe b = recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.gold_ingot, 2)), Collections.<FluidStack>emptyList());
        DisassemblyCatalog.Builder builder = new DisassemblyCatalog.Builder();
        builder.add(a); builder.add(a);
        check(builder.finish().size() == 1, "identical routes merge");
        builder.add(b); check(builder.finish().isEmpty(), "conflicting routes skipped");
        builder = new DisassemblyCatalog.Builder(); builder.add(a); builder.block(new ItemStack(Items.diamond), "custom", "unknown route");
        check(builder.finish().isEmpty(), "unsupported known output blocks unsafe refund");
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(new ItemStack(Items.iron_ingot), new ItemStack(Items.gold_ingot))), "unrelated alternatives skipped");
    }

    private static void oreRefunds() {
        Item standard = new Item();
        Item alternate = new Item();
        Item outside = new Item();
        Item.itemRegistry.addObject(31000, "futa_test:plate_standard", standard);
        Item.itemRegistry.addObject(31001, "futa_test:plate_alternate", alternate);
        Item.itemRegistry.addObject(31002, "futa_test:outside", outside);
        ItemStack preferred = new ItemStack(standard, 3);
        ItemStack other = new ItemStack(alternate, 3);
        GTOreDictUnificator.set(OrePrefixes.plate, Materials.Iron, preferred, true, false);
        GTOreDictUnificator.registerOre(OrePrefixes.plate, Materials.Iron, other);
        GTOreDictUnificator.addAssociation(OrePrefixes.plate, Materials.Iron, other);
        ItemStack resolved = DisassemblyIngredients.resolve(Arrays.asList(other, preferred));
        check(resolved.getItem() == standard && resolved.stackSize == 3, "GT material representative and count");
        check(ItemKey.of(resolved).equals(ItemKey.of(DisassemblyIngredients.resolve(Arrays.asList(preferred, other)))), "ore refund independent of alternative order");
        check(preferred.stackSize == 3 && other.stackSize == 3 && resolved != preferred, "recipe alternatives never mutated");
        check(DisassemblyIngredients.resolve(other).getItem() == standard, "fixed material matches ore route");
        net.minecraftforge.oredict.ShapedOreRecipe fixedAlternate = new net.minecraftforge.oredict.ShapedOreRecipe(new ItemStack(Items.diamond), "xxx", "xxx", "xxx", 'x', other);
        DisassemblyRecipe fixedReverse = DisassemblyCrafting.reverse(fixedAlternate);
        check(fixedReverse.itemOutputs().get(0).getItem() == standard && fixedReverse.itemOutputs().get(0).stackSize == 9,
            "fixed alternative matches original 3x3 while refund uses standard material");
        check(DisassemblyIngredients.resolve(new ItemStack(alternate, 0)).stackSize == 0, "nonconsumable quantity preserved");
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(preferred, new ItemStack(alternate, 2))), "alternative quantities cannot be rounded");
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(preferred, new ItemStack(outside, 3))), "no arbitrary ore fallback");
        NBTTagCompound nbt = new NBTTagCompound(); nbt.setString("contents", "valuable");
        ItemStack stateful = other.copy(); stateful.setTagCompound(nbt);
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(preferred, stateful)), "stateful alternatives not erased");
        check(ItemKey.of(DisassemblyIngredients.resolve(stateful)).equals(ItemKey.of(stateful)), "fixed NBT untouched");
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(preferred, new ItemStack(alternate, 3, OreDictionary.WILDCARD_VALUE))), "wildcard alternatives not guessed");
        GTOreDictUnificator.getName2StackMap().put("plateIron", new ItemStack(outside));
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(preferred, other)), "standard outside allowed alternatives rejected");
        GTOreDictUnificator.getName2StackMap().put("plateIron", preferred.copy());
        ItemStack basic = new ItemStack(Items.comparator);
        ItemStack advanced = new ItemStack(Items.repeater);
        String circuitOre = OrePrefixes.circuit.get(Materials.LV).toString();
        OreDictionary.registerOre(circuitOre, basic);
        OreDictionary.registerOre(circuitOre, advanced);
        ItemList.Circuit_Basic.set(basic);
        // Even a different GT unification preference must not turn a basic circuit refund into a costly alternative.
        GTOreDictUnificator.getName2StackMap().put(circuitOre, advanced);
        check(DisassemblyIngredients.resolve(Arrays.asList(advanced, basic)).getItem() == Items.comparator, "LV circuits refund the declared basic circuit");
        check(DisassemblyIngredients.resolve(Arrays.asList(basic, advanced)).getItem() == Items.comparator, "circuit refund order independent");
        DisassemblyRecipe a = recipe(new ItemStack(Items.diamond), Arrays.asList(DisassemblyIngredients.resolve(other)), Collections.<FluidStack>emptyList());
        DisassemblyRecipe b = recipe(new ItemStack(Items.diamond), Arrays.asList(DisassemblyIngredients.resolve(Arrays.asList(other, preferred))), Collections.<FluidStack>emptyList());
        DisassemblyCatalog.Builder builder = new DisassemblyCatalog.Builder(); builder.add(a); builder.add(b);
        check(builder.finish().size() == 1, "normalized crafting and assembler routes agree");
        builder.add(recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(outside, 3)), Collections.<FluidStack>emptyList()));
        check(builder.finish().isEmpty(), "real material conflicts still rejected");
        DisassemblyBuffer buffer = new DisassemblyBuffer(36, 8, 64000);
        buffer.inventory[0] = new ItemStack(Items.diamond);
        check(buffer.start(a), "normalized recipe starts");
        for (int i = 0; i < 40; i++) buffer.advance(true);
        check(buffer.inventory[2].getItem() == standard && buffer.inventory[2].stackSize == 3 && !buffer.hasTask(), "normalized refund settles once after two seconds");
        OreDictionary.registerOre("plateGold", preferred);
        OreDictionary.registerOre("plateGold", other);
        GTOreDictUnificator.getName2StackMap().put("plateGold", other.copy());
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(preferred, other)), "conflicting shared ore standards rejected");
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(other, preferred)), "ambiguous standards rejected in either order");
    }

    private static void craftingGrids() {
        net.minecraft.item.crafting.ShapedRecipes vanilla = new net.minecraft.item.crafting.ShapedRecipes(3, 3,
            new ItemStack[] { new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot),
                new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot),
                new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot) }, new ItemStack(Items.diamond));
        DisassemblyRecipe reverse = DisassemblyCrafting.reverse(vanilla);
        check(reverse.itemOutputs().get(0).stackSize == 9, "full vanilla 3x3 refunds all nine slots");
        net.minecraftforge.oredict.ShapedOreRecipe two = new net.minecraftforge.oredict.ShapedOreRecipe(new ItemStack(Items.diamond), "xx", "xx", 'x', Items.iron_ingot);
        check(DisassemblyCrafting.reverse(two).itemOutputs().get(0).stackSize == 4, "2x2 placed using actual row width");
        net.minecraft.item.crafting.ShapedRecipes vanillaTwo = new net.minecraft.item.crafting.ShapedRecipes(2, 2,
            new ItemStack[] { new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot), new ItemStack(Items.iron_ingot) }, new ItemStack(Items.diamond));
        check(DisassemblyCrafting.reverse(vanillaTwo).itemOutputs().get(0).stackSize == 4, "vanilla 2x2 retains shape");
        Item tool = new Item() {
            @Override public boolean hasContainerItem(ItemStack stack) { return true; }
            @Override public ItemStack getContainerItem(ItemStack stack) { return stack.copy(); }
        };
        Item secondTool = new Item() {
            @Override public boolean hasContainerItem(ItemStack stack) { return true; }
            @Override public ItemStack getContainerItem(ItemStack stack) { return stack.copy(); }
        };
        Item.itemRegistry.addObject(31003, "futa_test:wrench", tool);
        Item.itemRegistry.addObject(31004, "futa_test:other_wrench", secondTool);
        OreDictionary.registerOre("craftingToolWrench", new ItemStack(tool));
        OreDictionary.registerOre("craftingToolWrench", new ItemStack(secondTool));
        gregtech.api.util.GTShapedRecipe gt = new gregtech.api.util.GTShapedRecipe(new ItemStack(Items.diamond), false, false, null, null,
            "wxw", "xxx", "xxx", 'w', "craftingToolWrench", 'x', Items.iron_ingot);
        reverse = DisassemblyCrafting.reverse(gt);
        check(reverse.itemOutputs().size() == 1 && reverse.itemOutputs().get(0).getItem() == Items.iron_ingot && reverse.itemOutputs().get(0).stackSize == 7,
            "GT full 3x3 counts repeated materials and never refunds either reusable tool");
        check(((List<?>)gt.getInput()[0]).size() == 2, "tool alternatives remain intact");
        net.minecraftforge.oredict.ShapedOreRecipe direct = new net.minecraftforge.oredict.ShapedOreRecipe(new ItemStack(Items.diamond), "wx", 'w', new ItemStack(tool), 'x', Items.iron_ingot);
        check(DisassemblyCrafting.reverse(direct).itemOutputs().size() == 1, "direct reusable crafting tool excluded");
        expectRejected(() -> DisassemblyCrafting.reverse(new net.minecraftforge.oredict.ShapedOreRecipe(new ItemStack(Items.diamond), "bx", 'b', Items.water_bucket, 'x', Items.iron_ingot)), "container input remains unsafe");
        expectRejected(() -> DisassemblyIngredients.resolve(Arrays.asList(new ItemStack(tool), new ItemStack(Items.iron_ingot))), "mixed tool/material alternatives never treated as catalyst");
        Object[] universalInputs = { "xxx", "xxx", "xxx", 'x', Items.iron_ingot };
        reverse = DisassemblyCrafting.reverse(new com.dreammaster.recipes.ShapedUniversalRecipe(new ItemStack(Items.diamond), universalInputs));
        check(reverse.itemOutputs().get(0).getItem() == Items.iron_ingot && reverse.itemOutputs().get(0).stackSize == 9, "NHCore full 3x3 reads override, not parent placeholder");
        reverse = DisassemblyCrafting.reverse(new com.dreammaster.recipes.ShapelessUniversalRecipe(new ItemStack(Items.diamond), Items.iron_ingot, Items.iron_ingot));
        check(reverse.itemOutputs().get(0).stackSize == 2, "NHCore shapeless reads real ingredient override");
        expectRejected(() -> DisassemblyCrafting.reverse(new net.minecraftforge.oredict.ShapedOreRecipe(new ItemStack(Items.diamond), "x", 'x', Items.iron_ingot) {}), "unaudited custom subclasses remain excluded");
        expectRejected(() -> DisassemblyCrafting.reverse(new gregtech.api.util.GTShapedRecipe(new ItemStack(Items.diamond), false, true, null, null, "x", 'x', Items.iron_ingot)), "dynamic GT NBT still excluded");
        DisassemblyCatalog.Builder builder = new DisassemblyCatalog.Builder();
        builder.add(DisassemblyCrafting.reverse(gt));
        check(builder.finish().size() == 1, "tool-bearing 3x3 reaches catalog");
    }

    private static void machineInterfaces() throws Exception {
        check(Arrays.stream(MetaTileEntityIDs.values()).noneMatch(id -> id.ID == Config.disassemblerMetaTileId), "default ID does not collide with GT/GT++ registry");
        Constructor<MTELVDisassembler> constructor = MTELVDisassembler.class.getDeclaredConstructor(String.class, String[].class, ITexture[][][].class);
        constructor.setAccessible(true);
        MTELVDisassembler machine = constructor.newInstance("regression", new String[0], null);
        final AtomicLong energy = new AtomicLong(1280);
        IGregTechTileEntity tile = (IGregTechTileEntity) Proxy.newProxyInstance(IGregTechTileEntity.class.getClassLoader(), new Class<?>[] { IGregTechTileEntity.class }, (proxy, method, values) -> {
            switch (method.getName()) {
                case "getStoredEU": return energy.get();
                case "decreaseStoredEnergyUnits": check(((Long) values[0]) == 32, "exact tick EU"); energy.addAndGet(-32); return true;
                case "getFrontFacing": return ForgeDirection.NORTH;
                case "getBackFacing": return ForgeDirection.SOUTH;
                case "isServerSide": case "isAllowedToWork": return true;
                case "getMetaTileEntity": return machine;
                default: if (method.getReturnType() == boolean.class) return false; if (method.getReturnType() == long.class) return 0L; if (method.getReturnType() == int.class) return 0; return null;
            }
        });
        machine.setBaseMetaTileEntity(tile);
        check(machine.maxEUInput() == 32 && machine.maxAmperesIn() == 1, "physical input limited to LV 1A");
        DisassemblyBuffer work = new DisassemblyBuffer(36, 8, 64000);
        work.inventory[0] = new ItemStack(Items.diamond);
        DisassemblyRecipe recipe = recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.iron_ingot, 130)), Arrays.asList(new FluidStack(FluidRegistry.WATER, 80000), new FluidStack(FluidRegistry.LAVA, 2000)));
        check(work.start(recipe), "machine work prepared");
        NBTTagCompound saved = new NBTTagCompound(); saved.setTag("futaDisassembly", work.writeToNbt());
        machine.loadNBTData(saved);
        for (int tick = 1; tick <= 40; tick++) machine.onPostTick(tile, tick);
        check(energy.get() == 0 && machine.getStackInSlot(1) == null, "40 ticks cost exactly 1280 EU and clear escrow");
        check(machine.getStackInSlot(2).stackSize == 64 && machine.getStackInSlot(4).stackSize == 2, "GT inventory exposes every real output");
        check(!machine.allowPutStack(tile, 2, ForgeDirection.SOUTH, new ItemStack(Items.iron_ingot)) && !machine.getInventoryHandler().isItemValid(2, new ItemStack(Items.iron_ingot)), "external and GUI output insertion denied");
        check(!machine.allowPullStack(tile, 1, ForgeDirection.SOUTH, new ItemStack(Items.diamond)), "escrow extraction denied");
        check(machine.drain(ForgeDirection.SOUTH, new FluidStack(FluidRegistry.WATER, 70000), false).amount == 70000, "typed simulated drain combines tanks");
        check(machine.drain(ForgeDirection.NORTH, 1000, true) == null, "non-output face cannot drain");
        check(machine.drain(ForgeDirection.SOUTH, 1000, true).getFluid() == FluidRegistry.WATER, "untyped first tank");
        check(machine.drain(ForgeDirection.SOUTH, 1000, true).getFluid() == FluidRegistry.WATER, "untyped next same-fluid tank");
        check(machine.drain(ForgeDirection.SOUTH, 1000, true).getFluid() == FluidRegistry.LAVA, "round robin reaches later fluid");
        check(machine.drain(ForgeDirection.SOUTH, new FluidStack(FluidRegistry.WATER, 100000), true).amount == 78000, "typed exact drain across all remaining tanks");
        NBTTagCompound dropped = new NBTTagCompound(); machine.setItemNBT(dropped);
        check(!dropped.getCompoundTag("futaDisassembly").hasKey("inventory") && !dropped.getCompoundTag("futaDisassembly").hasKey("task"), "drops never embed a second item or task copy");
    }

    private static void mainInventoryTransfer() {
        World world = new World(new SaveHandlerMP(), "check", new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT), new WorldProviderSurface(), new Profiler()) {
            @Override protected IChunkProvider createChunkProvider() { return null; }
            @Override protected int func_152379_p() { return 0; }
            @Override public Entity getEntityByID(int id) { return null; }
        };
        EntityPlayer player = new EntityPlayer(world, new GameProfile(UUID.randomUUID(), "check")) {
            @Override public void addChatMessage(IChatComponent message) {}
            @Override public boolean canCommandSenderUseCommand(int permission, String command) { return false; }
            @Override public ChunkCoordinates getPlayerCoordinates() { return new ChunkCoordinates(0, 0, 0); }
        };
        Config.displayItemBecomesFluid = false;
        player.inventory.currentItem = 4;
        for (int i = 0; i < 36; i++) player.inventory.mainInventory[i] = new ItemStack(Items.iron_ingot, i + 1);
        player.inventory.armorInventory[0] = new ItemStack(Items.iron_boots);
        player.inventory.setItemStack(new ItemStack(Items.diamond));
        SharedStorage store = new SharedStorage();
        long count = InventoryExchange.depositAll(player, InventoryExchange.SCOPE_MAIN, store, new DeltaRecorder(store, null));
        check(count == 621 && store.getItemAmount(ItemKey.of(new ItemStack(Items.iron_ingot))) == 621, "all 27 main slots transferred exactly");
        for (int i = 0; i < 9; i++) check(player.inventory.mainInventory[i].stackSize == i + 1, "every hotbar slot preserved");
        for (int i = 9; i < 36; i++) check(player.inventory.mainInventory[i] == null, "main slot emptied");
        check(player.inventory.armorInventory[0] != null && player.inventory.getItemStack().getItem() == Items.diamond, "armor and cursor preserved");
    }

    private static void expectRejected(Runnable action, String description) {
        try { action.run(); throw new AssertionError(description); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        assertions++;
    }
}
