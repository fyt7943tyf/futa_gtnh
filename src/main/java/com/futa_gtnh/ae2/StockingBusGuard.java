package com.futa_gtnh.ae2;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;

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

    /**
     * True when ProgrammableHatches (or anything else exposing {@code IMEHatchOverrided}) has replaced this bus's
     * extraction source; the guard then neither caps nor refunds, because the items do not come from the AE network.
     * Unknown or failing overrides count as overridden.
     */
    public static boolean externallyOverridden(Object bus) {
        Method override = OVERRIDE.get(bus.getClass());
        if (override == null) return false;
        try {
            return Boolean.TRUE.equals(override.invoke(bus));
        } catch (Throwable error) {
            return true;
        }
    }

    private static final ClassValue<Method> OVERRIDE = new ClassValue<Method>() {

        @Override
        protected Method computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Class<?> iface : c.getInterfaces()) {
                    if (!iface.getName()
                        .endsWith(".IMEHatchOverrided")) continue;
                    try {
                        return iface.getMethod("override");
                    } catch (NoSuchMethodException ignored) {
                        return null;
                    }
                }
            }
            return null;
        }
    };

    /**
     * A view of the network that records every item taken out with a real ({@code MODULATE}) extraction and otherwise
     * forwards untouched. A dynamic proxy keeps it independent of the AE2 interface's exact method set.
     */
    @SuppressWarnings("unchecked")
    public static IMEMonitor<IAEItemStack> recordingMonitor(final IMEMonitor<IAEItemStack> real,
        final List<IAEItemStack> taken) {
        return (IMEMonitor<IAEItemStack>) Proxy.newProxyInstance(
            IMEMonitor.class.getClassLoader(),
            new Class<?>[] { IMEMonitor.class },
            new InvocationHandler() {

                @Override
                public Object invoke(Object self, Method method, Object[] args) throws Throwable {
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                    if (result instanceof IAEItemStack && args != null
                        && args.length == 3
                        && args[1] == Actionable.MODULATE
                        && method.getName()
                            .equals("extractItems")) {
                        taken.add(((IAEItemStack) result).copy());
                    }
                    return result;
                }
            });
    }

    /** Rate limiter shared by one bus; returns the new timestamp, or {@code lastLogMs} when logging is suppressed. */
    public static long logShortPull(long lastLogMs, String where, IEnergySource energy, long planned, long pulled) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < LOG_INTERVAL_MS) return lastLogMs;
        double powerForPlan = energy == null ? -1
            : energy.extractAEPower(planned, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        LOG.warn(
            "Stocking bus short pull at {}: planned={} pulled={} powerAvailableForPlanned={} (needs {})",
            where,
            planned,
            pulled,
            powerForPlan,
            planned);
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
}
