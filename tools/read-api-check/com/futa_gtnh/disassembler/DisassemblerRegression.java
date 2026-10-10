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
        File minecraftHome = new File("build/shimmer-regression"); minecraftHome.mkdirs();
        java.lang.reflect.Field home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); home.set(null, minecraftHome);
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        java.lang.reflect.Field namedMods = Loader.class.getDeclaredField("namedMods"); namedMods.setAccessible(true);
        namedMods.set(Loader.instance(), new java.util.HashMap<>());
        Bootstrap.func_151354_b();
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        java.lang.reflect.Field logSide = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side");
        logSide.setAccessible(true);
        logSide.set(null, cpw.mods.fml.relauncher.Side.SERVER);
        // GTRecipe only needs the proxy's owner-capture flags here. Forge normally injects the
        // proxy; allocate that minimal fixture without starting GT's world/event subsystems.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field unsafeField = unsafeType.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        java.lang.reflect.Field proxyField = gregtech.GTMod.class.getField("proxy");
        proxyField.set(null, unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, proxyField.getType()));
        batchesAndIdentity();
        capacityAndAtomicProcessing();
        shimmerRoutes();
        shimmerMaterials();
        shimmerCrafting();
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
        check(divisible.inputCount == 4 && divisible.itemOutputs().get(0).stackSize == 8 && divisible.fluidOutputs().get(0).amount == 144, "original Shimmer batch retained even when all amounts divide");
        DisassemblyRecipe catalyst = recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.iron_ingot, 2), new ItemStack(Items.redstone, 0)), Collections.<FluidStack>emptyList());
        check(catalyst.itemOutputs().size() == 1, "nonconsumable omitted");
        check(recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.water_bucket)), Collections.<FluidStack>emptyList()).itemOutputs().get(0).getItem() == Items.water_bucket, "raw assembly line refunds retain containers like Shimmer");
        DisassemblyRecipe wildcard = recipe(new ItemStack(Blocks.wool, 1, OreDictionary.WILDCARD_VALUE), Arrays.asList(new ItemStack(Items.wooden_sword, 1, 32767)), Collections.<FluidStack>emptyList());
        check(wildcard.matches(new ItemStack(Blocks.wool, 1, 14), false, true), "wildcard input accepts concrete metadata like Shimmer");
        check(wildcard.itemOutputs().get(0).getItemDamage() == 32767, "raw durable wildcard is preserved");
        ItemStack emptyTagged = new ItemStack(Items.diamond); emptyTagged.setTagCompound(new NBTTagCompound());
        DisassemblyRecipe emptyPattern = recipe(emptyTagged, Arrays.asList(new ItemStack(Items.iron_ingot)), Collections.<FluidStack>emptyList());
        check(emptyPattern.matches(emptyTagged, false, true) && !emptyPattern.matches(new ItemStack(Items.diamond), false, true), "empty NBT pattern remains distinct from null as in GTUtility");
        check(DisassemblyRecipe.readFromNbt(emptyPattern.writeToNbt()).matches(emptyTagged, false, true), "empty NBT pattern persists");
        DisassemblyRecipe big = recipe(new ItemStack(Items.diamond, 256), Arrays.asList(new ItemStack(Items.iron_ingot, 512)), Collections.<FluidStack>emptyList());
        check(big.inputCount == 256 && big.itemOutputs().size() == 8, "large original batch is supported without reducing materials");
        buffer.inventory[0] = new ItemStack(Items.diamond, 320);
        check(buffer.start(big) && buffer.inventory[0].stackSize == 64 && buffer.inventory[1].stackSize == 256, "one original batch removed and remainder left in input");
        DisassemblyBuffer restored = new DisassemblyBuffer(36, 8, 64000); restored.readFromNbt(buffer.writeToNbt());
        check(restored.inventory[1].stackSize == 256 && restored.inventory[0].stackSize == 64, "large escrow counts persist beyond NBT byte limit");
        for (int tick = 0; tick < 40; tick++) restored.advance(true);
        check(!restored.hasTask() && restored.inventory[0].stackSize == 64, "large original batch finishes once after 40 ticks");
        DisassemblyBuffer collecting = new DisassemblyBuffer(36, 8, 64000);
        collecting.inventory[0] = new ItemStack(Items.diamond, 64);
        check(collecting.collect(big) && !collecting.canAdvance() && !collecting.advance(true), "partial large batch waits without paid processing ticks");
        NBTTagCompound partial = collecting.writeToNbt(); collecting.readFromNbt(partial);
        check(collecting.hasTask() && collecting.inventory[1].stackSize == 64, "partial batch resumes after world save");
        collecting.inventory[0] = new ItemStack(Items.emerald, 64);
        check(!collecting.collect(null) && collecting.inventory[0].stackSize == 64, "different item cannot enter collected batch");
        for (int i = 0; i < 3; i++) { collecting.inventory[0] = new ItemStack(Items.diamond, 64); check(collecting.collect(null), "legal input stacks accumulate without GUI overflow"); }
        check(collecting.canAdvance() && collecting.inventory[1].stackSize == 256, "entire original batch required before processing");
        DisassemblyBuffer dismantled = new DisassemblyBuffer(36, 8, 64000); dismantled.readFromNbt(collecting.writeToNbt()); dismantled.prepareDrops();
        int dropped = 0;
        for (ItemStack stack : dismantled.inventory) if (stack != null) { check(stack.stackSize <= stack.getMaxStackSize(), "escrow drops split into legal world stacks"); dropped += stack.stackSize; }
        check(dropped == 256 && !dismantled.hasTask(), "dismantling returns original escrow exactly once, no materials");
        for (int i = 0; i < 40; i++) collecting.advance(true);
        check(!collecting.hasTask() && collecting.inventory[1] == null, "collected original batch completes in exactly two seconds");

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
        DisassemblyBuffer grown = new DisassemblyBuffer(36, 8, 64000); grown.readFromNbt(saved);
        check(grown.growTo(70, 12, 256000), "early/old machine storage grows to final catalog capacity");
        check(grown.inventory.length == 72 && grown.tanks.length == 12 && grown.tanks[0].getCapacity() == 256000, "every new output slot/tank is available");
        check(grown.hasTask() && grown.progress == 17 && grown.inventory[1].getItem() == Items.diamond, "storage growth preserves pending task and escrow");
        check(!grown.growTo(36, 8, 64000), "storage never shrinks after recipe/catalog changes");
        DisassemblyBuffer fluidsOnly = new DisassemblyBuffer(36, 8, 64000);
        NBTTagCompound machineItem = new NBTTagCompound(); restored.writeFluids(machineItem);
        fluidsOnly.readFromNbt(machineItem);
        check(fluidsOnly.inventory[1] == null && !fluidsOnly.hasTask() && fluidsOnly.tanks[2].getFluidAmount() == 90000, "machine item carries fluids only, no escrow duplication");
    }

    private static void shimmerRoutes() {
        DisassemblyCatalog.Builder builder = new DisassemblyCatalog.Builder();
        DisassemblyRecipe four = recipe(new ItemStack(Items.diamond, 4), Arrays.asList(new ItemStack(Items.iron_ingot, 8)), Collections.<FluidStack>emptyList());
        builder.add(four);
        check(builder.contains(new ItemStack(Items.diamond, 4)), "existing conversion masks later route with sufficient input");
        check(!builder.contains(new ItemStack(Items.diamond)), "smaller production batch may add a second conversion");
        DisassemblyRecipe one = recipe(new ItemStack(Items.diamond), Arrays.asList(new ItemStack(Items.gold_ingot)), Collections.<FluidStack>emptyList());
        builder.add(one);
        builder.skip(new ItemStack(Items.diamond), "GT crafting", "invalid raw recipe");
        check(builder.finish().size() == 2, "failed/conflicting routes never veto accepted conversions");
        DisassemblyCatalog.publish(builder.finish());
        check(DisassemblyCatalog.find(new ItemStack(Items.diamond, 4)) == four, "first registered matching route wins");
        check(DisassemblyCatalog.find(new ItemStack(Items.diamond)) == one, "smaller stack selects later smaller batch");
        check(DisassemblyCatalog.usages(new ItemStack(Items.diamond)).size() == 2, "NEI use queries show larger batches from a one-item query");
        ItemStack stateful = new ItemStack(Items.diamond, 4); NBTTagCompound tag = new NBTTagCompound(); tag.setInteger("energy", 200); stateful.setTagCompound(tag);
        check(builder.contains(stateful), "route registration ignores NBT like isInConversions");
        check(DisassemblyCatalog.find(stateful) == null, "execution checks exact NBT like getConversionResult");
        gregtech.api.util.GTRecipe chance = forward(new ItemStack(Items.emerald), new ItemStack(Items.iron_ingot, 3));
        chance.mEnabled = false; chance.mFakeRecipe = true; chance.mOutputChances = new int[] {5000}; chance.mInputChances = new int[] {2000};
        chance.mFluidInputs = new FluidStack[] {new FluidStack(FluidRegistry.WATER, 144)};
        chance.mFluidOutputs = new FluidStack[] {new FluidStack(FluidRegistry.LAVA, 250)};
        chance.mAltFluidInputs = new FluidStack[][] {{new FluidStack(FluidRegistry.LAVA, 144)}};
        builder = new DisassemblyCatalog.Builder();
        DisassemblyCatalog.loadAssemblers(builder, Arrays.asList(chance, forward(new ItemStack(Items.emerald), new ItemStack(Items.gold_ingot, 99))));
        check(builder.finish().size() == 1 && builder.finish().get(0).itemOutputs().get(0).stackSize == 3, "assembler uses first route without old chance/disabled/fake/global-conflict exclusions");
        check(builder.finish().get(0).fluidOutputs().get(0).getFluid() == FluidRegistry.WATER && builder.finish().get(0).fluidOutputs().get(0).amount == 144, "assembler returns consumed first-route fluid only");
        chance.mOutputs = new ItemStack[] {new ItemStack(Items.emerald), new ItemStack(Items.gold_ingot)};
        builder = new DisassemblyCatalog.Builder(); DisassemblyCatalog.loadAssemblers(builder, Arrays.asList(chance));
        check(builder.finish().isEmpty(), "assembler still requires one product like Shimmer");
        DisassemblyCatalog.loadRawRecipes(builder, Arrays.asList(chance), "assembly line visual");
        check(builder.finish().size() == 1, "visual assembly line selects first product and retains raw ingredients");
        check(builder.finish().get(0).itemOutputs().get(0).stackSize == 3, "raw assembly line material count retained");
        ShimmerDisassemblyRules.inputBlacklist.add(new gregtech.api.objects.GTItemStack(new ItemStack(Items.emerald)));
        check(!ShimmerDisassemblyRules.shouldDisassembleItemStack(new ItemStack(Items.emerald)), "Shimmer explicit input blacklist honored");
        builder = new DisassemblyCatalog.Builder(); DisassemblyCatalog.loadAssemblers(builder, Arrays.asList(forward(new ItemStack(Items.emerald), new ItemStack(Items.iron_ingot))));
        check(builder.finish().isEmpty(), "automatic assembler honors blacklist");
        DisassemblyCatalog.loadRawRecipes(builder, Arrays.asList(forward(new ItemStack(Items.emerald), new ItemStack(Items.water_bucket))), "space assembler");
        check(builder.finish().get(0).itemOutputs().get(0).getItem() == Items.water_bucket, "raw space assembler bypasses automatic blacklist/container transformation like Shimmer");
        ShimmerDisassemblyRules.inputBlacklist.clear();
        DisassemblyCatalog.publish(Collections.emptyList());
    }

    private static gregtech.api.util.GTRecipe forward(ItemStack output, ItemStack... inputs) {
        return new gregtech.api.util.GTRecipe(false, inputs, new ItemStack[] {output}, null, null, null, null, null, null, null, 300, 30, 0);
    }

    private static ItemStack material(int id, OrePrefixes prefix, Materials material) {
        Item item = new Item(); Item.itemRegistry.addObject(id, "futa_test:material_" + id, item);
        ItemStack stack = new ItemStack(item, 3);
        GTOreDictUnificator.set(prefix, material, stack, true, false);
        GTOreDictUnificator.addAssociation(prefix, material, stack);
        return stack;
    }

    private static void shimmerMaterials() {
        ItemStack iron = material(31000, OrePrefixes.plate, Materials.Iron);
        ItemStack aluminium = material(31001, OrePrefixes.plate, Materials.Aluminium);
        ItemStack steel = material(31002, OrePrefixes.plate, Materials.Steel);
        ItemStack copper = material(31003, OrePrefixes.plate, Materials.Copper);
        ItemStack annealed = material(31004, OrePrefixes.plate, Materials.AnnealedCopper);
        ItemStack magnetic = material(31005, OrePrefixes.plate, Materials.IronMagnetic);
        it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<ItemStack[]> alternatives = new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>();
        alternatives.add(new ItemStack[] {iron});
        List<ItemStack> outputs = ShimmerDisassemblyRules.handleRecipeTransformation(new ItemStack[] {aluminium}, alternatives);
        check(outputs.get(0).getItem() == iron.getItem() && outputs.get(0).stackSize == 3, "aluminium route downgrades to iron by corresponding-slot material");
        outputs = ShimmerDisassemblyRules.handleRecipeTransformation(new ItemStack[] {steel}, alternatives);
        check(outputs.get(0).getItem() == iron.getItem(), "steel route downgrades to iron");
        outputs = ShimmerDisassemblyRules.handleRecipeTransformation(new ItemStack[] {copper}, alternatives);
        check(outputs.get(0).getItem() == copper.getItem(), "unrelated routes retain first material instead of rejecting product");
        outputs = ShimmerDisassemblyRules.handleRecipeTransformation(new ItemStack[] {annealed, magnetic}, null);
        check(outputs.get(0).getItem() == copper.getItem() && outputs.get(1).getItem() == iron.getItem(), "annealed and magnetic materials refund their unprocessed forms");
        check(aluminium.stackSize == 3 && aluminium.getItemDamage() == 0, "forward recipe ingredients are not modified");
        ItemStack wildGlass = new ItemStack(Blocks.glass, 2, OreDictionary.WILDCARD_VALUE);
        ItemStack wildTool = new ItemStack(Items.wooden_sword, 1, OreDictionary.WILDCARD_VALUE);
        ItemStack nbt = new ItemStack(Items.blaze_rod, 2); NBTTagCompound content = new NBTTagCompound(); content.setString("grade", "special"); nbt.setTagCompound(content);
        outputs = ShimmerDisassemblyRules.handleRecipeTransformation(new ItemStack[] {wildGlass, wildTool, nbt, new ItemStack(Items.water_bucket), new ItemStack(Items.iron_ingot, 0)}, null);
        check(outputs.size() == 4 && outputs.get(0).getItemDamage() == 0 && outputs.get(0).stackSize == 2, "wildcards canonicalize to zero; containers alone are filtered");
        check(outputs.get(1).getItemDamage() == OreDictionary.WILDCARD_VALUE, "damageable wildcard remains a raw Shimmer output");
        check(outputs.get(2).getTagCompound().getString("grade").equals("special"), "unassociated material NBT retained");
        check(wildGlass.getItemDamage() == OreDictionary.WILDCARD_VALUE, "wildcard source not mutated");
        outputs = ShimmerDisassemblyRules.handleRecipeTransformation(new ItemStack[] {new ItemStack(Blocks.trapped_chest, 3)}, null);
        check(outputs.get(0).getItem() == Item.getItemFromBlock(Blocks.chest) && outputs.get(0).stackSize == 1, "trapped chest follows upstream replacement including its one-chest count");
        ItemStack exoticPlanks = new ItemStack(Blocks.wool, 5, 14); OreDictionary.registerOre("plankWood", exoticPlanks);
        outputs = ShimmerDisassemblyRules.handleRecipeTransformation(new ItemStack[] {exoticPlanks}, null);
        check(outputs.get(0).getItem() == Item.getItemFromBlock(Blocks.planks) && outputs.get(0).stackSize == 5 && outputs.get(0).getItemDamage() == 0, "wood ore aliases become vanilla defaults with quantity retained");
        check(ShimmerDisassemblyRules.getCheapestCircuitOrNull(Materials.LV).stackSize == 1, "actual NHCore get(int) bridge resolves a cheapest circuit");
        check(ShimmerDisassemblyRules.getCheapestCircuitOrNull(Materials.LuV).getItemDamage() == 6, "LuV circuit field spelling matches NHCore");
        check(ShimmerDisassemblyRules.getCheapestCircuitOrNull(Materials.MAX).getItemDamage() == 14, "MAX circuit field resolves through optional bridge");
        ItemStack circuit = material(31006, OrePrefixes.circuit, Materials.LV);
        ItemStack ore = material(31007, OrePrefixes.crushedPurified, Materials.Iron);
        check(!ShimmerDisassemblyRules.shouldDisassembleItemStack(circuit), "automatic circuit product exclusion");
        check(!ShimmerDisassemblyRules.shouldDisassembleItemStack(ore), "automatic crushed ore product exclusion");
        check(ShimmerDisassemblyRules.replaceCheaperOrNull(Materials.Diamond, Materials.Glass) == Materials.Glass, "diamond downgrade mapping");
        check(ShimmerDisassemblyRules.replaceCheaperOrNull(Materials.Polytetrafluoroethylene, Materials.Polyethylene) == Materials.Polyethylene, "polymer downgrade mapping");
        check(ShimmerDisassemblyRules.replaceCheaperOrNull(Materials.Polyethylene, Materials.Wood) == Materials.Wood, "polymer to wood mapping");
        check(ShimmerDisassemblyRules.replaceCheaperOrNull(Materials.CertusQuartz, Materials.Quartzite) == Materials.Quartzite, "quartz downgrade mapping");
        check(ShimmerDisassemblyRules.getUnprocessedMaterials(Materials.NeodymiumMagnetic) == Materials.Neodymium, "remaining magnetic fallback mapping");
    }

    private static void shimmerCrafting() {
        Object[] grid = new Object[] {"xxx", "xxx", "xxx", 'x', new ItemStack(Blocks.glass, 1, OreDictionary.WILDCARD_VALUE)};
        ShimmerCraftingRegistry.Entry entry = new ShimmerCraftingRegistry.Entry(new ItemStack(Items.diamond), grid, true);
        gregtech.api.util.GTRecipe reversed = entry.reverse().get();
        List<ItemStack> outputs = ShimmerDisassemblyRules.handleRecipeTransformation(reversed.mOutputs, null);
        check(outputs.get(0).stackSize == 9 && outputs.get(0).getItemDamage() == 0, "real GT reversal counts all nine slots and resolves wildcard glass");
        ItemStack taggedOutput = new ItemStack(Items.diamond); NBTTagCompound outputTag = new NBTTagCompound(); outputTag.setInteger("mode", 4); taggedOutput.setTagCompound(outputTag);
        entry = new ShimmerCraftingRegistry.Entry(taggedOutput, new Object[] {"xx", "xx", 'x', Items.iron_ingot}, true);
        check(entry.reverse().get().mOutputs[0].stackSize == 4, "real GT 2x2 reversal retains shape count");
        check(entry.reverse().get().mInputs[0].getTagCompound().getInteger("mode") == 4, "captured output NBT retained without virtual crafting-result veto");
        entry = new ShimmerCraftingRegistry.Entry(new ItemStack(Items.diamond), new Object[] {"hx", 'h', "craftingToolHardHammer", 'x', Items.iron_ingot}, true);
        check(entry.reverse().get().mOutputs.length == 1 && entry.reverse().get().mOutputs[0].stackSize == 1, "GT crafting-tool ore omitted from raw capture");
        OreDictionary.registerOre("futaFallbackPipe", new ItemStack(Items.redstone, 7));
        entry = new ShimmerCraftingRegistry.Entry(new ItemStack(Items.diamond), new Object[] {"xx", 'x', "futaFallbackPipe"}, true);
        check(entry.reverse().get().mOutputs[0].getItem() == Items.redstone && entry.reverse().get().mOutputs[0].stackSize == 2, "ore with no GT representative falls back to dictionary candidate");
        entry = new ShimmerCraftingRegistry.Entry(new ItemStack(Items.diamond), new Object[] {"xy", 'x', Items.iron_ingot, 'y', "futaMissingOre"}, true);
        check(entry.reverse().get().mOutputs.length == 1, "missing ore ingredient omitted without rejecting valid materials");
        entry = new ShimmerCraftingRegistry.Entry(new ItemStack(Items.diamond), new Object[] {Items.iron_ingot, Items.water_bucket, "craftingToolHardHammer"}, false);
        outputs = ShimmerDisassemblyRules.handleRecipeTransformation(entry.reverse().get().mOutputs, null);
        check(outputs.size() == 1 && outputs.get(0).getItem() == Items.iron_ingot, "shapeless tool and container omitted individually");
        int before = ShimmerCraftingRegistry.snapshot().size();
        ShimmerCraftingRegistry.capture(new ItemStack(Items.diamond), grid, true);
        check(ShimmerCraftingRegistry.snapshot().size() == before, "actual capture rejects non-GT-machine products like GTNL mixin");
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
