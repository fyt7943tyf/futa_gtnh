package com.futa_gtnh.ae2;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageListener;

import appeng.api.config.Actionable;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import cpw.mods.fml.common.Loader;

/** Real production transfer/storage, with AE receiver contracts and registry-only MC fixtures. */
public final class CraftingDeliveryRegression {

    private static int assertions;

    private CraftingDeliveryRegression() {}

    public static void main(String[] args) throws Exception {
        Field home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); home.set(null, new File("build/ae-crafting-regression"));
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        Field side = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true); side.set(null, cpw.mods.fml.relauncher.Side.SERVER);
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Bootstrap.func_151354_b();
        NBTTagCompound variant = new NBTTagCompound(); variant.setString("grade", "pure");
        ItemStack item = new ItemStack(Items.diamond, 1, 0); item.setTagCompound(variant);
        FluidStack fluid = new FluidStack(FluidRegistry.WATER, 1, (NBTTagCompound) variant.copy());
        exercise(new ItemChannel(ItemKey.of(item)));
        exercise(new FluidChannel(FluidKey.of(fluid)));
        nestedSuppression();
        concurrentChangeQueue();
        System.out.println("AE crafting delivery regression: " + assertions + " assertions passed");
    }

    private abstract static class Channel<T extends IAEStack<T>> {
        SharedStorage storage = new SharedStorage();
        abstract long get();
        abstract long put(long amount);
        abstract long held();
        abstract T stack(long amount);
        abstract void deliver(T offer, IMEInventory<T> receiver);
        abstract void limit(long amount);
        abstract long space();
        abstract SharedStorage.CraftingReservation reserve(long amount);

        void reset(long amount) { storage = new SharedStorage(); put(amount); }

        void send(long amount, Function<T, T> receiver) {
            AeCellBridge.suppressed(() -> {
                deliver(stack(amount), inventory(receiver));
                return null;
            });
        }
    }

    private static final class ItemChannel extends Channel<IAEItemStack> {
        final ItemKey key;
        ItemChannel(ItemKey key) { this.key = key; }
        long get() { return storage.getItemAmount(key); }
        long put(long n) { return storage.insertItemManual(key, n); }
        long held() { return storage.snapshotCraftingItems().isEmpty() ? 0L : storage.snapshotCraftingItems().get(0).getValue(); }
        IAEItemStack stack(long n) { return fixture(IAEItemStack.class, key.prototype(), null, n); }
        void deliver(IAEItemStack s, IMEInventory<IAEItemStack> r) { CraftingDelivery.item(storage, key, s, r, null); }
        void limit(long n) { storage.setItemLimit(key, n); }
        long space() { return storage.itemManualSpaceLeft(key); }
        SharedStorage.CraftingReservation reserve(long n) { return storage.reserveItemForCrafting(key, n); }
    }

    private static final class FluidChannel extends Channel<IAEFluidStack> {
        final FluidKey key;
        FluidChannel(FluidKey key) { this.key = key; }
        long get() { return storage.getFluidAmount(key); }
        long put(long n) { return storage.insertFluidManual(key, n); }
        long held() { return storage.snapshotCraftingFluids().isEmpty() ? 0L : storage.snapshotCraftingFluids().get(0).getValue(); }
        IAEFluidStack stack(long n) { return fixture(IAEFluidStack.class, null, key.prototype(), n); }
        void deliver(IAEFluidStack s, IMEInventory<IAEFluidStack> r) { CraftingDelivery.fluid(storage, key, s, r, null); }
        void limit(long n) { storage.setFluidLimit(key, n); }
        long space() { return storage.fluidManualSpaceLeft(key); }
        SharedStorage.CraftingReservation reserve(long n) { return storage.reserveFluidForCrafting(key, n); }
    }

    private static <T extends IAEStack<T>> void exercise(Channel<T> c) throws Exception {
        AtomicLong cpu = new AtomicLong();
        c.reset(64);
        c.send(64, offer -> {
            check(c.get() == 0 && c.held() == 64, "real withdrawal precedes AE injection");
            check(!Thread.holdsLock(c.storage), "AE is invoked without holding the storage monitor");
            cpu.addAndGet(offer.getStackSize()); return null;
        });
        check(c.get() == 0 && cpu.get() == 64 && c.held() == 0, "full intermediate delivery conserves stock");

        c.reset(64); cpu.set(0);
        c.send(64, offer -> { cpu.addAndGet(16); return sized(offer, offer.getStackSize() - 16); });
        check(c.get() == 48 && cpu.get() == 16 && c.held() == 0, "partial intermediate: 48 shared plus 16 CPU");

        c.reset(64); c.limit(0);
        AtomicLong confirmed = new AtomicLong();
        c.send(64, offer -> { confirmed.addAndGet(offer.getStackSize()); return offer; });
        check(c.get() == 64 && confirmed.get() == 64 && c.held() == 0, "final output is acknowledged and returned above automation limit");
        check(c.storage.writeToNbt().getInteger("version") == 2, "settled storage retains v2 compatibility");

        c.reset(64);
        c.send(64, offer -> offer);
        check(c.get() == 64 && c.held() == 0, "rejected offer is returned once");
        c.send(64, offer -> sized(offer, offer.getStackSize() - 16));
        c.send(48, offer -> null);
        check(c.get() == 0, "two CPUs/networks consume each unit at most once");
        c.put(16); cpu.set(0);
        c.send(64, offer -> { cpu.addAndGet(offer.getStackSize()); return null; });
        check(cpu.get() == 16 && c.get() == 0, "smaller refill delivers without stale absolute watermark");
        AtomicLong calls = new AtomicLong();
        c.send(64, offer -> { calls.incrementAndGet(); return null; });
        check(calls.get() == 0, "empty warehouse cannot fabricate an offer");

        c.reset(Long.MAX_VALUE - 7); cpu.set(0);
        c.send(Long.MAX_VALUE, offer -> { cpu.addAndGet(offer.getStackSize()); return null; });
        check(cpu.get() == Long.MAX_VALUE - 7 && c.get() == 0, "long quantities use actual withdrawn amount without overflow");

        c.reset(64);
        c.send(64, offer -> {
            AtomicReference<Throwable> writerError = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try { check(c.put(Long.MAX_VALUE) == Long.MAX_VALUE - 64, "concurrent insertion preserves rollback capacity"); }
                catch (Throwable error) { writerError.set(error); }
            });
            writer.start();
            try { writer.join(5000); } catch (InterruptedException error) { throw new AssertionError(error); }
            if (writerError.get() != null) throw new AssertionError(writerError.get());
            check(!writer.isAlive(), "AE callback does not block concurrent storage writers");
            check(c.space() == 0, "manual simulation includes reserved rollback space");
            return offer;
        });
        check(c.get() == Long.MAX_VALUE && c.held() == 0, "rejected offer restores even after concurrent refill");

        c.reset(64); cpu.set(0);
        expectFailure(() -> c.send(64, offer -> { cpu.addAndGet(16); throw new IllegalStateException("after partial receiver mutation"); }));
        check(c.get() == 0 && cpu.get() == 16 && c.held() == 64, "uncertain exception is quarantined, never blindly refunded");
        c.put(16); calls.set(0);
        c.send(16, offer -> { calls.incrementAndGet(); return null; });
        check(calls.get() == 0 && c.get() == 16, "uncertain key cannot automatically retry");
        NBTTagCompound saved = c.storage.writeToNbt();
        check(saved.getInteger("version") == 3, "unresolved escrow requires escrow-aware format");
        c.storage = new SharedStorage(); c.storage.readFromNbt(saved);
        check(c.get() == 16 && c.held() == 64, "escrow survives NBT reload without becoming spendable");

        c.reset(64);
        expectFailure(() -> c.send(64, offer -> sized(offer, 65)));
        check(c.get() == 0 && c.held() == 64, "invalid oversized remainder cannot create stock");
        c.reset(64);
        expectFailure(() -> c.send(64, offer -> sized(offer, -1)));
        check(c.get() == 0 && c.held() == 64, "invalid negative remainder remains recoverable");

        c.reset(64);
        SharedStorage.CraftingReservation receipt = c.reserve(64);
        c.limit(0); receipt.finish(64);
        check(c.get() == 64 && c.held() == 0, "rollback ignores configured automation limit");
        expectFailure(() -> receipt.finish(64));
        check(c.get() == 64, "receipt cannot refund twice");
        c.reserve(64);
        c.storage = roundTrip(c.storage);
        c.storage.refundCraftingEscrow();
        check(c.get() == 64 && c.held() == 0, "explicit recovery of unreceived escrow returns exactly once");
        c.storage.refundCraftingEscrow();
        check(c.get() == 64, "repeated escrow recovery is idempotent");

        c.reset(64);
        SharedStorage.CraftingReservation stale = c.reserve(64);
        c.storage.clear();
        expectFailure(() -> stale.finish(64));
        check(c.get() == 0 && c.held() == 0, "clear invalidates in-flight receipts instead of resurrecting cleared stock");
    }

    private static SharedStorage roundTrip(SharedStorage storage) {
        SharedStorage next = new SharedStorage(); next.readFromNbt(storage.writeToNbt()); return next;
    }

    private static <T extends IAEStack<T>> T sized(T template, long amount) {
        T result = template.copy(); result.setStackSize(amount); return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends IAEStack<T>> IMEInventory<T> inventory(Function<T, T> receiver) {
        return (IMEInventory<T>) Proxy.newProxyInstance(IMEInventory.class.getClassLoader(), new Class<?>[] { IMEInventory.class }, (proxy, method, args) -> {
            if (method.getName().equals("injectItems")) {
                check(args[1] == Actionable.MODULATE, "real injection mode");
                return receiver.apply((T) args[0]);
            }
            throw new AssertionError("Unexpected inventory operation: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T fixture(Class<T> type, ItemStack item, FluidStack fluid, long amount) {
        AtomicLong size = new AtomicLong(amount);
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getStackSize": return size.get();
                case "setStackSize": size.set((Long) args[0]); return proxy;
                case "copy": return fixture(type, item == null ? null : item.copy(), fluid == null ? null : fluid.copy(), size.get());
                case "getItemStack": return item == null ? null : item.copy();
                case "getFluidStack": return fluid == null ? null : fluid.copy();
                case "isSameType":
                    if (type == IAEItemStack.class) return ItemKey.of(item).equals(ItemKey.of(((IAEItemStack) args[0]).getItemStack()));
                    return FluidKey.of(fluid).equals(FluidKey.of(((IAEFluidStack) args[0]).getFluidStack()));
                default: throw new AssertionError("Unexpected stack operation: " + method.getName());
            }
        });
    }

    private static void nestedSuppression() throws Exception {
        Field field = AeCellBridge.class.getDeclaredField("SUPPRESS"); field.setAccessible(true);
        ThreadLocal<?> flag = (ThreadLocal<?>) field.get(null);
        AeCellBridge.suppressed(() -> {
            AeCellBridge.suppressed(() -> null);
            check(Boolean.TRUE.equals(flag.get()), "nested suppression preserves outer transfer");
            return null;
        });
        check(Boolean.FALSE.equals(flag.get()), "suppression resets after success");
        expectFailure(() -> AeCellBridge.suppressed(() -> { throw new IllegalStateException("fixture"); }));
        check(Boolean.FALSE.equals(flag.get()), "suppression resets after exception");
    }

    private static void concurrentChangeQueue() throws Exception {
        Field installed = AeCellBridge.class.getDeclaredField("installed"); installed.setAccessible(true);
        Field active = AeCellBridge.class.getDeclaredField("anyActiveCells"); active.setAccessible(true);
        Field listenerField = AeCellBridge.class.getDeclaredField("LISTENER"); listenerField.setAccessible(true);
        installed.setBoolean(null, true); active.setBoolean(null, true);
        SharedStorageListener listener = (SharedStorageListener) listenerField.get(null);
        AeCellBridge.drainPendingChanges();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Set<ItemKey> observed = new HashSet<>();
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 1000; i++) listener.onItemChanged(ItemKey.of(Items.diamond, i, null));
            } catch (Throwable error) { failure.set(error); }
            finally { done.set(true); }
        });
        Thread consumer = new Thread(() -> {
            try {
                while (!done.get()) observed.addAll(AeCellBridge.drainPendingChanges().items);
                observed.addAll(AeCellBridge.drainPendingChanges().items);
            } catch (Throwable error) { failure.set(error); }
        });
        try {
            producer.start(); consumer.start(); producer.join(5000); consumer.join(5000);
            check(!producer.isAlive() && !consumer.isAlive(), "change queue drains without deadlock");
            if (failure.get() != null) throw new AssertionError(failure.get());
            check(observed.size() == 1000, "no worker change is lost when the tick swaps queues");
            listener.onFluidChanged(FluidKey.of(new FluidStack(FluidRegistry.WATER, 1)));
            check(AeCellBridge.drainPendingChanges().fluids.size() == 1, "fluid changes use the same queue handoff");
        } finally {
            AeCellBridge.reset();
        }
    }

    private static void expectFailure(Runnable action) {
        boolean failed = false;
        try { action.run(); } catch (IllegalStateException expected) { failed = true; }
        check(failed, "expected guarded failure");
    }

    private static synchronized void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
