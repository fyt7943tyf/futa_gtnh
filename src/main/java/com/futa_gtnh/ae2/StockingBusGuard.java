package com.futa_gtnh.ae2;

import net.minecraft.item.ItemStack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/**
 * Support code for the GregTech stocking input bus hooks (see {@code MixinGtStockingBus}).
 *
 * <p>
 * The bus plans a recipe against a <em>simulated</em> extraction and only pulls the consumed items from the network
 * afterwards. When the real pull comes back short the machine shuts down and the items that were already pulled are
 * thrown away. The simulation knows nothing about AE power, so a subnet whose energy buffer is smaller than the
 * request (or a source that is counted twice) trips this regularly. The hooks do three things:
 * <ol>
 * <li>cap the simulated amount to what the grid can pay for in power;</li>
 * <li>log every short pull with the numbers needed to tell the causes apart;</li>
 * <li>give the items already pulled back to the network when the check aborted.</li>
 * </ol>
 * Set {@code -Dfutagtnh.stockingBusGuard=false} to switch the behaviour changes off (the log stays).
 */
public final class StockingBusGuard {

    private static final Logger LOG = LogManager.getLogger("futa_gtnh");

    /** Far more than any real request; the simulation returns {@code min(available, request)}. */
    private static final double PROBE_POWER = 1.0e15;

    private static final long LOG_INTERVAL_MS = 5_000L;

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("futagtnh.stockingBusGuard"));

    private static final ThreadLocal<Boolean> IN_RECIPE_CHECK = new ThreadLocal<>();

    private StockingBusGuard() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void enterRecipeCheck() {
        IN_RECIPE_CHECK.set(Boolean.TRUE);
    }

    public static void leaveRecipeCheck() {
        IN_RECIPE_CHECK.remove();
    }

    /**
     * True only while {@code MTEMultiBlockBase.checkRecipe()} is on the stack. Only there does a failed bus pull mean
     * the recipe really did not start; fuel-burning machines call the same hooks while already producing, so refunding
     * there could duplicate.
     */
    public static boolean inRecipeCheck() {
        return IN_RECIPE_CHECK.get() != null;
    }

    /**
     * The most items of one kind that the grid can pay the extraction power for right now, the same figure
     * {@code Platform.poweredExtraction} uses. {@code Long.MAX_VALUE} when the grid cannot be queried.
     */
    public static long affordableItems(IEnergySource energy, int amountPerUnit) {
        if (energy == null) return Long.MAX_VALUE;
        double power = energy.extractAEPower(PROBE_POWER, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        double items = power * Math.max(1, amountPerUnit) + 0.9;
        return items >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) items;
    }

    /** Rate limiter shared by one bus; returns the new timestamp, or {@code lastLogMs} when logging is suppressed. */
    public static long logShortPull(long lastLogMs, String where, IEnergySource energy, IAEStack<?> request,
        IAEStack<?> result) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < LOG_INTERVAL_MS) return lastLogMs;
        long wanted = request.getStackSize();
        long got = result == null ? 0 : result.getStackSize();
        double powerForRequest = energy == null ? -1
            : energy.extractAEPower(request.getStackSize(), Actionable.SIMULATE, PowerMultiplier.CONFIG);
        String item = request instanceof IAEItemStack stack ? describe(stack.getItemStack()) : String.valueOf(request);
        LOG.warn(
            "Stocking bus short pull at {}: item={} wanted={} got={} powerAvailableForRequest={} (needs {})",
            where,
            item,
            wanted,
            got,
            powerForRequest,
            wanted);
        return now;
    }

    public static void logRefund(String where, int stacks, long items, long leftover, boolean refunded) {
        if (refunded) {
            LOG.warn(
                "Stocking bus at {} returned {} item(s) in {} stack(s) to the network after the recipe check aborted ({} did not fit)",
                where,
                items,
                stacks,
                leftover);
        } else {
            LOG.warn(
                "Stocking bus at {} lost {} item(s): the shortfall happened outside a recipe check, nothing was refunded",
                where,
                items);
        }
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "?";
        return stack.getUnlocalizedName() + "@" + stack.getItemDamage();
    }
}
