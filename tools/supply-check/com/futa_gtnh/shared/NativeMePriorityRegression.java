package com.futa_gtnh.shared;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.Config;
import com.futa_gtnh.me.NativeMeCellArray;

import appeng.api.config.Actionable;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.me.cache.SecurityCache;
import appeng.me.storage.CellInventory;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.NetworkInventoryHandler;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import appeng.util.prioitylist.OreFilteredList;

/** Real native cells, routing engine and ore-card partition; no substitute quantity engine. */
public final class NativeMePriorityRegression {

    private static int checks;
    private static final BaseActionSource SOURCE = new BaseActionSource();

    @SuppressWarnings("unchecked")
    public static void run(Item itemMedia, Item fluidMedia) {
        int previous = Config.publicMeStoragePriority;
        try {
            Config.publicMeStoragePriority = -100;
            NativeMeCellArray cells = new NativeMeCellArray(fluid -> new ItemStack(fluid ? fluidMedia : itemMedia));
            cells.setPublished(true);
            ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
            ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
            cells.insertItem(iron, 40);
            cells.insertItem(gold, 30);
            OreDictionary.registerOre("futaPriorityIron", new ItemStack(Items.iron_ingot));
            OreDictionary.registerOre("futaPriorityCopper", new ItemStack(Items.diamond));
            IMEInventoryHandler<IAEItemStack> child = (IMEInventoryHandler<IAEItemStack>) CellInventory.getCell(new ItemStack(itemMedia), null, AEItemStackType.ITEM_STACK_TYPE);
            MEInventoryHandler<IAEItemStack> bus = new MEInventoryHandler<>(child, AEItemStackType.ITEM_STACK_TYPE);
            bus.setWhitelist(IncludeExclude.WHITELIST);
            bus.setPartitionList(new OreFilteredList("futaPriority*"));
            bus.setPriority(0);
            NetworkInventoryHandler<IAEItemStack> network = network(cells, AEItemStackType.ITEM_STACK_TYPE, bus);
            IAEItemStack request = item(Items.iron_ingot, 16);
            check(network.injectItems(request.copy(), Actionable.SIMULATE, SOURCE) == null && amount(child, request) == 0 && cells.itemView().get(iron) == 40, "simulation advertises routing without changing either inventory");
            check(network.injectItems(request.copy(), Actionable.MODULATE, SOURCE) == null && amount(child, request) == 16 && cells.itemView().get(iron) == 40, "ore bus receives known stock before the populated public cell");
            IAEItemStack fresh = item(Items.diamond, 7);
            check(network.injectItems(fresh.copy(), Actionable.MODULATE, SOURCE) == null && amount(child, fresh) == 7 && !cells.itemView().containsKey(ItemKey.of(new ItemStack(Items.diamond))), "ore bus also receives types absent from public storage");
            check(network.injectItems(item(Items.gold_ingot, 9), Actionable.MODULATE, SOURCE) == null && amount(child, item(Items.gold_ingot, 1)) == 0 && cells.itemView().get(gold) == 39, "ore mismatch falls back to public storage");
            child.injectItems(item(Items.iron_ingot, Long.MAX_VALUE), Actionable.MODULATE, SOURCE);
            long full = amount(child, request);
            check(full > 16 && child.injectItems(request.copy(), Actionable.SIMULATE, SOURCE) != null, "destination is a genuinely full native cell");
            check(child.extractItems(item(Items.iron_ingot, 10), Actionable.MODULATE, SOURCE).getStackSize() == 10, "make exactly ten spaces in the destination");
            check(network.injectItems(request.copy(), Actionable.SIMULATE, SOURCE) == null && amount(child, request) == full - 10 && cells.itemView().get(iron) == 40, "partial destination simulation preserves exact stock");
            check(network.injectItems(request.copy(), Actionable.MODULATE, SOURCE) == null && amount(child, request) == full && cells.itemView().get(iron) == 46, "ten enter the child and only six return to public cells");
            check(network.injectItems(item(Items.iron_ingot, 3), Actionable.MODULATE, SOURCE) == null && amount(child, request) == full && cells.itemView().get(iron) == 49, "full destination safely falls back to public storage");

            FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
            cells.insertFluid(water, 1000);
            IMEInventoryHandler<IAEFluidStack> fluidChild = (IMEInventoryHandler<IAEFluidStack>) CellInventory.getCell(new ItemStack(fluidMedia), null, AEFluidStackType.FLUID_STACK_TYPE);
            MEInventoryHandler<IAEFluidStack> fluidBus = new MEInventoryHandler<>(fluidChild, AEFluidStackType.FLUID_STACK_TYPE);
            fluidBus.setPriority(0);
            NetworkInventoryHandler<IAEFluidStack> fluidNetwork = network(cells, AEFluidStackType.FLUID_STACK_TYPE, fluidBus);
            IAEFluidStack fluid = AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 250));
            check(fluidNetwork.injectItems(fluid.copy(), Actionable.MODULATE, SOURCE) == null && amount(fluidChild, fluid) == 250 && cells.fluidView().get(water) == 1000, "public fluid cells use the same lower storage priority");

            Config.publicMeStoragePriority = 100;
            NativeMeCellArray preferred = new NativeMeCellArray(isFluid -> new ItemStack(isFluid ? fluidMedia : itemMedia));
            preferred.insertItem(iron, 20);
            preferred.setPublished(true);
            child.extractItems(item(Items.iron_ingot, 100), Actionable.MODULATE, SOURCE);
            long before = amount(child, request);
            NetworkInventoryHandler<IAEItemStack> preferredNetwork = network(preferred, AEItemStackType.ITEM_STACK_TYPE, bus);
            check(preferredNetwork.injectItems(request.copy(), Actionable.MODULATE, SOURCE) == null && amount(child, request) == before && preferred.itemView().get(iron) == 36, "server can explicitly raise public storage above the routing bus");
        } finally {
            Config.publicMeStoragePriority = previous;
        }
        System.out.println("Native ME storage priority: " + checks + " checks passed (ore routing, populated/new types, simulation, partial/full destination, fluid and configured override)");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static <T extends IAEStack<T>> NetworkInventoryHandler<T> network(NativeMeCellArray cells, IAEStackType<T> type, IMEInventoryHandler<T> bus) {
        NetworkInventoryHandler<T> network = new NetworkInventoryHandler<>(type, new SecurityCache(null));
        // Register public cells first to catch ties that depend on registration order.
        for (IMEInventoryHandler handler : cells.getCellArray(type)) network.addNewStorage(handler);
        network.addNewStorage(bus);
        return network;
    }

    private static <T extends IAEStack<T>> long amount(IMEInventoryHandler<T> inventory, T request) {
        T result = inventory.getAvailableItem(request, 0);
        return result == null ? 0 : result.getStackSize();
    }

    private static IAEItemStack item(Item item, long amount) {
        return AEItemStack.create(new ItemStack(item)).setStackSize(amount);
    }

    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
        checks++;
    }
}
