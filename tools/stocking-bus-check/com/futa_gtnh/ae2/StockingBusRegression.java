package com.futa_gtnh.ae2;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;

/**
 * Real transformation of GT's stocking bus and multiblock base, then the transformed code is driven with a fake AE grid:
 * power-capped simulation, exact pulls, short pulls inside and outside a recipe check, and multi-slot refunds.
 */
public final class StockingBusRegression {

    private static int checks;

    private static final Map<Item, Long> SIMULATED = new HashMap<Item, Long>();
    private static final Map<Item, Long> REAL = new HashMap<Item, Long>();
    private static final List<IAEItemStack> INJECTED = new ArrayList<IAEItemStack>();
    private static double power = Double.MAX_VALUE;
    private static boolean STOPPED;

    public static void main(String[] args) throws Exception {
        Thread.currentThread()
            .setContextClassLoader(net.minecraft.launchwrapper.Launch.classLoader);
        Loader.injectData(
            new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        Bootstrap.func_151354_b();
        // GT static initialisers ask Loader.isModLoaded(); an empty mod list is enough for the fixtures.
        Field named = Loader.class.getDeclaredField("namedMods");
        named.setAccessible(true);
        named.set(Loader.instance(), new HashMap<String, Object>());
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", Boolean.TRUE);

        transformed();
        powerCap();
        exactPull();
        shortPullInsideCheck();
        shortPullOutsideCheck();
        shortSecondSlotRefundsBoth();
        System.out.println("GT stocking bus guard: " + checks + " checks passed (real Mixin and fake AE grid)");
    }

    private static void transformed() {
        check(
            hasMethod(MTEHatchInputBusME.class, "futa$capSimulatedAmount"),
            "bus gets the power-capped simulation hook");
        check(hasMethod(MTEHatchInputBusME.class, "futa$trackPull"), "bus redirects the powered extraction");
        check(hasMethod(MTEHatchInputBusME.class, "futa$refundPulled"), "bus gets the refund hook");
        check(hasMethod(MTEMultiBlockBase.class, "futa$enterRecipeCheck"), "multiblock base marks checkRecipe()");
    }

    private static void powerCap() throws Exception {
        MTEHatchInputBusME bus = newBus(new ItemStack(Items.apple));
        SIMULATED.put(Items.apple, 1000L);

        power = 50;
        simulate(bus);
        check(slot(bus, 0).extractedAmount == 50, "simulated amount is capped to the affordable 50");
        check(slot(bus, 0).extracted.stackSize == 50, "the stack handed to the machine is capped too");

        power = 1.0e12;
        simulate(bus);
        check(slot(bus, 0).extractedAmount == 1000, "plenty of power leaves the simulated amount alone");

        power = 0;
        simulate(bus);
        check(slot(bus, 0).extracted == null && slot(bus, 0).extractedAmount == 0, "no power means nothing offered");
        power = Double.MAX_VALUE;
    }

    private static void exactPull() throws Exception {
        MTEHatchInputBusME bus = newBus(new ItemStack(Items.apple));
        prepare(bus, 0, 100, 60);
        REAL.put(Items.apple, 100L);
        INJECTED.clear();
        StockingBusGuard.enterRecipeCheck();
        CheckRecipeResult result = bus.endRecipeProcessing(controller());
        StockingBusGuard.leaveRecipeCheck();
        check(result.wasSuccessful(), "an exact pull still succeeds");
        check(INJECTED.isEmpty(), "an exact pull refunds nothing");
    }

    private static void shortPullInsideCheck() throws Exception {
        MTEHatchInputBusME bus = newBus(new ItemStack(Items.apple));
        prepare(bus, 0, 100, 60);
        REAL.put(Items.apple, 30L);
        INJECTED.clear();
        STOPPED = false;
        StockingBusGuard.enterRecipeCheck();
        CheckRecipeResult result = bus.endRecipeProcessing(controller());
        StockingBusGuard.leaveRecipeCheck();
        check(!result.wasSuccessful() && STOPPED, "a short pull still fails the check and stops the machine");
        check(INJECTED.size() == 1 && INJECTED.get(0).getStackSize() == 30, "the 30 pulled items go back to the network");
    }

    private static void shortPullOutsideCheck() throws Exception {
        MTEHatchInputBusME bus = newBus(new ItemStack(Items.apple));
        prepare(bus, 0, 100, 60);
        REAL.put(Items.apple, 30L);
        INJECTED.clear();
        StockingBusGuard.leaveRecipeCheck();
        CheckRecipeResult result = bus.endRecipeProcessing(controller());
        check(!result.wasSuccessful(), "a short pull outside a recipe check still fails");
        check(INJECTED.isEmpty(), "outside checkRecipe() nothing is refunded (fuel machines could duplicate)");
    }

    private static void shortSecondSlotRefundsBoth() throws Exception {
        MTEHatchInputBusME bus = newBus(new ItemStack(Items.apple), new ItemStack(Items.stick));
        prepare(bus, 0, 100, 60);
        prepare(bus, 1, 10, 0);
        REAL.put(Items.apple, 100L);
        REAL.put(Items.stick, 4L);
        INJECTED.clear();
        StockingBusGuard.enterRecipeCheck();
        CheckRecipeResult result = bus.endRecipeProcessing(controller());
        StockingBusGuard.leaveRecipeCheck();
        long total = 0;
        for (IAEItemStack stack : INJECTED) total += stack.getStackSize();
        check(!result.wasSuccessful(), "second slot short fails the check");
        check(total == 44, "the successful first pull (40) and the partial second pull (4) both go back");
    }

    // ---- fixtures ----

    private static MTEHatchInputBusME newBus(ItemStack... configs) throws Exception {
        MTEHatchInputBusME bus = (MTEHatchInputBusME) unsafe().allocateInstance(MTEHatchInputBusME.class);
        MTEHatchInputBusME.Slot[] slots = new MTEHatchInputBusME.Slot[MTEHatchInputBusME.SLOT_COUNT];
        for (int i = 0; i < configs.length; i++) slots[i] = new MTEHatchInputBusME.Slot(configs[i]);
        set(MTEHatchInputBusME.class, bus, "slots", slots);
        set(MTEHatchInputBusME.class, bus, "processingRecipe", true);
        set(MTEHatchInputBusME.class, bus, "cachedActivity", true);
        set(MTEHatchInputBusME.class, bus, "requestSource", new BaseActionSource());
        set(MTEHatchInputBusME.class, bus, "gridProxy", unsafe().allocateInstance(FakeProxy.class));
        return bus;
    }

    private static void prepare(MTEHatchInputBusME bus, int index, int planned, int left) throws Exception {
        MTEHatchInputBusME.Slot slot = slot(bus, index);
        slot.extracted = slot.config.copy();
        slot.extracted.stackSize = left;
        slot.extractedAmount = planned;
    }

    private static MTEHatchInputBusME.Slot slot(MTEHatchInputBusME bus, int index) throws Exception {
        Field slots = MTEHatchInputBusME.class.getDeclaredField("slots");
        slots.setAccessible(true);
        return ((MTEHatchInputBusME.Slot[]) slots.get(bus))[index];
    }

    private static void simulate(MTEHatchInputBusME bus) throws Exception {
        Field ready = MTEHatchInputBusME.class.getDeclaredField("futa$capReady");
        ready.setAccessible(true);
        ready.setBoolean(bus, false);
        bus.updateInformationSlot(0);
    }

    private static MTEMultiBlockBase controller() throws Exception {
        return (MTEMultiBlockBase) unsafe().allocateInstance(TestController.class);
    }

    /** Never constructed (Unsafe skips the constructor); only gives {@code stopMachine} a real receiver. */
    private static class TestController extends MTEMultiBlockBase {

        TestController() {
            super(0, "test", "test");
        }

        @Override
        public gregtech.api.interfaces.ITexture[] getTexture(gregtech.api.interfaces.tileentity.IGregTechTileEntity tile,
            net.minecraftforge.common.util.ForgeDirection side, net.minecraftforge.common.util.ForgeDirection facing,
            int colorIndex, boolean active, boolean redstone) {
            return null;
        }

        @Override
        public void stopMachine(gregtech.api.util.shutdown.ShutDownReason reason) {
            STOPPED = true;
        }

        public String[] getDescription() {
            return new String[0];
        }

        public gregtech.api.interfaces.metatileentity.IMetaTileEntity newMetaEntity(
            gregtech.api.interfaces.tileentity.IGregTechTileEntity tile) {
            return null;
        }

        public boolean checkMachine(gregtech.api.interfaces.tileentity.IGregTechTileEntity tile, ItemStack stack) {
            return false;
        }

        public int getMaxEfficiency(ItemStack stack) {
            return 10000;
        }

        public int getPollutionPerSecond(ItemStack stack) {
            return 0;
        }

        public int getDamageToComponent(ItemStack stack) {
            return 0;
        }

        public boolean explodesOnComponentBreak(ItemStack stack) {
            return false;
        }
    }

    private static final class FakeProxy extends AENetworkProxy {

        FakeProxy() {
            super(null, null, null, false);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public IEnergyGrid getEnergy() throws GridAccessException {
            return proxy(IEnergyGrid.class, new InvocationHandler() {

                @Override
                public Object invoke(Object self, Method method, Object[] args) {
                    if (method.getName()
                        .equals("extractAEPower") && args.length == 3) {
                        return Math.min(((Number) args[0]).doubleValue(), power);
                    }
                    return fallback(method);
                }
            });
        }

        @Override
        public IStorageGrid getStorage() throws GridAccessException {
            final IMEMonitor<?> monitor = proxy(IMEMonitor.class, new InvocationHandler() {

                @Override
                @SuppressWarnings("unchecked")
                public Object invoke(Object self, Method method, Object[] args) {
                    String name = method.getName();
                    if (name.equals("extractItems")) {
                        IAEItemStack request = (IAEItemStack) args[0];
                        Item item = request.getItemStack()
                            .getItem();
                        Map<Item, Long> source = args[1] == Actionable.SIMULATE ? SIMULATED : REAL;
                        Long stock = source.get(item);
                        long give = stock == null ? 0 : Math.min(stock, request.getStackSize());
                        return give <= 0 ? null : request.copy()
                            .setStackSize(give);
                    }
                    if (name.equals("injectItems")) {
                        INJECTED.add(((IAEItemStack) args[0]).copy());
                        return null;
                    }
                    return fallback(method);
                }
            });
            return proxy(IStorageGrid.class, new InvocationHandler() {

                @Override
                public Object invoke(Object self, Method method, Object[] args) {
                    if (method.getName()
                        .equals("getItemInventory")) return monitor;
                    return fallback(method);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object fallback(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0D;
        if (type == float.class) return 0.0F;
        return null;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName()
                .contains(name)) return true;
        }
        return false;
    }

    private static void check(boolean success, String description) {
        if (!success) throw new AssertionError(description);
        checks++;
    }

    // Keeps the import used when the harness is trimmed.
    @SuppressWarnings("unused")
    private static IAEItemStack unused(ItemStack stack) {
        return AEItemStack.create(stack);
    }
}
