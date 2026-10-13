package com.futa_gtnh.medock;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.network.ServerActions;
import com.futa_gtnh.network.Utf8Text;

import appeng.api.config.Actionable;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import cpw.mods.fml.common.Loader;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.tileentities.storage.MTEQuantumChest;
import gregtech.common.tileentities.storage.MTEQuantumTank;

public final class AeIntegrationRegression {
    private static int assertions;
    private static MTEAeSuperDock dock;

    public static void main(String[] args) throws Exception {
        Field home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); home.set(null, new File("build/ae-regression"));
        Loader.injectData(new Object[] {"7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList()});
        Field mods = Loader.class.getDeclaredField("namedMods"); mods.setAccessible(true); mods.set(Loader.instance(), new HashMap<>());
        Bootstrap.func_151354_b();
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        Field side = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side"); side.setAccessible(true); side.set(null, cpw.mods.fml.relauncher.Side.SERVER);
        Constructor<MTEAeSuperDock> constructor = MTEAeSuperDock.class.getDeclaredConstructor(String.class, int.class, String[].class, ITexture[][][].class);
        constructor.setAccessible(true); dock = constructor.newInstance("regression", 1, new String[0], null);
        IGregTechTileEntity tile = (IGregTechTileEntity) Proxy.newProxyInstance(IGregTechTileEntity.class.getClassLoader(), new Class<?>[] {IGregTechTileEntity.class}, (p, method, values) -> {
            if (method.getName().equals("getMetaTileEntity")) return dock;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        });
        dock.setBaseMetaTileEntity(tile);
        // Registry fixture keeps real GT digital-storage capacities without starting a world.
        GregTechAPI.sBlockMachines = Blocks.stone;
        GregTechAPI.METATILEENTITIES[30000] = new MTEQuantumTank("tank", 1, new String[0], null);
        GregTechAPI.METATILEENTITIES[30001] = new MTEQuantumChest("chest", 1, new String[0], null);
        fluids(); items(); textAndQueue();
        check(dock.getInventoryStackLimit() == 1, "container slot cannot merge machines");
        System.out.println("AE integration regression: " + assertions + " assertions passed");
    }

    @SuppressWarnings("unchecked")
    private static <T> T inventory(String type, ItemStack stack) throws Exception {
        dock.mInventory[0] = stack;
        Constructor<?> constructor = Class.forName(MTEAeSuperDock.class.getName() + "$" + type).getDeclaredConstructor(MTEAeSuperDock.class, ItemStack.class);
        constructor.setAccessible(true); return (T) constructor.newInstance(dock, stack);
    }

    private static IAEFluidStack fluid(FluidStack content) {
        return stack(IAEFluidStack.class, null, content, content.amount);
    }

    private static IAEItemStack item(ItemStack content) {
        return stack(IAEItemStack.class, content, null, content.stackSize);
    }

    // AE input-contract fixtures avoid initializing every optional AE block in a registry-only test.
    // All transfer, capacity, equality and NBT mutation code under test is the real dock implementation.
    @SuppressWarnings("unchecked")
    private static <T> T stack(Class<T> type, ItemStack item, FluidStack fluid, long amount) {
        java.util.concurrent.atomic.AtomicLong size = new java.util.concurrent.atomic.AtomicLong(amount);
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, values) -> {
            switch (method.getName()) {
                case "getStackSize": return size.get();
                case "setStackSize": size.set((Long) values[0]); return proxy;
                case "getItemStack": return item;
                case "getFluidStack": return fluid;
                case "getFluid": return fluid.getFluid();
                case "copy": return stack(type, item == null ? null : item.copy(), fluid == null ? null : fluid.copy(), size.get());
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
            }
        });
    }

    private static void fluids() throws Exception {
        ItemStack stack = new ItemStack(Blocks.stone, 1, 30000);
        stack.setTagCompound(new NBTTagCompound()); stack.stackTagCompound.setBoolean("mLockFluid", true);
        IMEInventory<IAEFluidStack> inventory = inventory("TankContentInventory", stack);
        NBTTagCompound grade = new NBTTagCompound(); grade.setString("grade", "pure");
        IAEFluidStack input = fluid(new FluidStack(FluidRegistry.WATER, 1000, grade));
        check(inventory.injectItems(input, Actionable.SIMULATE, null) == null, "simulated fill fits");
        check(!stack.stackTagCompound.hasKey("mFluid") && !stack.stackTagCompound.hasKey("lockedFluidName"), "simulation writes no content or lock");
        check(inventory.injectItems(input, Actionable.MODULATE, null) == null, "real fill consumes exact quantity");
        check(stored(stack).amount == 1000 && stored(stack).tag.getString("grade").equals("pure"), "first fill retains fluid NBT");
        check(stack.stackTagCompound.getString("lockedFluidName").equals("water"), "first real fill establishes requested lock");
        grade.setString("grade", "changed");
        check(stored(stack).tag.getString("grade").equals("pure"), "stored NBT is independent of caller");
        IAEFluidStack plain = fluid(new FluidStack(FluidRegistry.WATER, 400));
        check(inventory.injectItems(plain, Actionable.MODULATE, null) == plain, "same fluid with different tags cannot merge");
        check(inventory.extractItems(plain, Actionable.MODULATE, null) == null, "different tags cannot extract");
        IAEFluidStack correct = fluid(stored(stack)); correct.setStackSize(400);
        check(inventory.extractItems(correct, Actionable.SIMULATE, null).getStackSize() == 400 && stored(stack).amount == 1000, "simulated extraction preserves amount");
        check(inventory.extractItems(correct, Actionable.MODULATE, null).getStackSize() == 400 && stored(stack).amount == 600, "real extraction conserves fluid");
        long capacity = ((MTEQuantumTank) GregTechAPI.METATILEENTITIES[30000]).getRealCapacity();
        IAEFluidStack huge = fluid(stored(stack)); huge.setStackSize(capacity + 200);
        check(inventory.injectItems(huge, Actionable.MODULATE, null).getStackSize() == 800 && stored(stack).amount == capacity, "overflow returns exact remainder");
        dock.mInventory[0] = null;
        check(inventory.extractItems(correct, Actionable.MODULATE, null) == null && inventory.injectItems(correct, Actionable.MODULATE, null) == correct, "detached cached tank cannot transfer");
        check(stored(stack).amount == capacity, "detached tank item retains its contents");
        dock.mInventory[0] = stack; stack.stackSize = 2;
        check(inventory.extractItems(correct, Actionable.MODULATE, null) == null, "invalid stacked tanks cannot duplicate fluid");
    }

    private static FluidStack stored(ItemStack stack) {
        return FluidStack.loadFluidStackFromNBT(stack.stackTagCompound.getCompoundTag("mFluid"));
    }

    private static void items() throws Exception {
        ItemStack stack = new ItemStack(Blocks.stone, 1, 30001);
        IMEInventory<IAEItemStack> inventory = inventory("ChestContentInventory", stack);
        IAEItemStack input = item(new ItemStack(Items.diamond)); input.setStackSize(1000);
        check(inventory.injectItems(input, Actionable.SIMULATE, null) == null && !stack.hasTagCompound(), "chest simulation is read only");
        check(inventory.injectItems(input, Actionable.MODULATE, null) == null && count(stack) == 1000, "chest inserts large count");
        IAEItemStack other = item(new ItemStack(Items.emerald));
        check(inventory.injectItems(other, Actionable.MODULATE, null) == other, "single type chest rejects second item");
        input.setStackSize(1200);
        check(inventory.extractItems(input, Actionable.SIMULATE, null).getStackSize() == 1000 && count(stack) == 1000, "chest extraction simulation clamps to count");
        check(inventory.extractItems(input, Actionable.MODULATE, null).getStackSize() == 1000 && count(stack) == 0 && !stack.stackTagCompound.hasKey("mItemStack"), "chest drains exactly once");
        long capacity = ((MTEQuantumChest) GregTechAPI.METATILEENTITIES[30001]).getMaxItemCount(); input.setStackSize(capacity + 123);
        check(inventory.injectItems(input, Actionable.MODULATE, null).getStackSize() == 123 && count(stack) == capacity, "chest full capacity conservation");
        dock.mInventory[0] = stack.copy();
        check(inventory.extractItems(input, Actionable.MODULATE, null) == null, "replacement invalidates old chest handle");
    }

    private static int count(ItemStack stack) { return stack.stackTagCompound.getInteger("mItemCount"); }

    private static void textAndQueue() throws Exception {
        String source = "中文信号😀abc";
        String boundary = "abcdefghijklmnopqrstuvwxyzabcde😀";
        String limited = Utf8Text.limitLength(boundary, 32);
        check(limited.length() == 31 && new String(limited.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(limited), "name length does not split emoji pair");
        com.futa_gtnh.aewireless.TileAeSignalTransmitter transmitter = new com.futa_gtnh.aewireless.TileAeSignalTransmitter();
        transmitter.setSignalName("foo\u0001bar\u001Fbaz");
        check(transmitter.getSignalName().equals("foobarbaz"), "name cannot inject sender-list separators");
        for (int max = 0; max <= source.getBytes(StandardCharsets.UTF_8).length + 2; max++) {
            String result = Utf8Text.limit(source, max);
            check(result.getBytes(StandardCharsets.UTF_8).length <= max && source.startsWith(result) && !result.contains("\uFFFD"), "UTF-8 boundary " + max);
        }
        AtomicInteger ran = new AtomicInteger(); Thread owner = Thread.currentThread();
        Thread receiver = new Thread(() -> ServerActions.enqueue(() -> { check(Thread.currentThread() == owner, "action runs on server thread"); ran.incrementAndGet(); }));
        receiver.start(); receiver.join(); check(ran.get() == 0, "Netty callback does not execute world mutations");
        ServerActions.tick(); check(ran.get() == 1, "server tick applies queued mutation");
        for (int i = 0; i < 300; i++) ServerActions.enqueue(ran::incrementAndGet);
        ServerActions.tick(); check(ran.get() == 257, "network queue is bounded");
        ServerActions.enqueue(ran::incrementAndGet); ServerActions.clear(); ServerActions.tick(); check(ran.get() == 257, "server stop clears queued world actions");
    }

    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
