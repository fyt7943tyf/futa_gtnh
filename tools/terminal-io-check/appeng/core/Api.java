package appeng.core;

import java.lang.reflect.Proxy;

import com.google.common.base.Optional;

import appeng.api.IAppEngApi;
import appeng.api.definitions.IDefinitions;
import appeng.api.definitions.IItemDefinition;
import appeng.api.definitions.IItems;
import appeng.api.features.IRegistryContainer;
import appeng.api.features.ISpecialComparisonRegistry;
import appeng.api.storage.IStorageHelper;

/**
 * Test-only empty AE2 item definitions and comparison registry. The real core API
 * constructs every AE2 block, including optional power integrations that Forge
 * normally strips. Ore checks need no blocks, encoded patterns or custom comparisons.
 * AEItemStack, AESharedNBT and OreFilteredList remain the real dependency classes.
 */
public final class Api {

    public static final IAppEngApi INSTANCE = emptyRegistry(IAppEngApi.class);

    private Api() {}

    private static <T> T emptyRegistry(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "definitions":
                    return emptyRegistry(IDefinitions.class);
                case "storage":
                    return emptyRegistry(IStorageHelper.class);
                case "createItemList":
                    return new appeng.util.item.ItemList();
                case "createSortedItemList":
                    return new appeng.util.item.ItemList(true);
                case "createFluidList":
                    return new appeng.util.item.FluidList();
                case "createAEStackList":
                    return new appeng.util.item.IAEStackList();
                case "items":
                    return emptyRegistry(IItems.class);
                case "encodedPattern":
                    return emptyRegistry(IItemDefinition.class);
                case "maybeStack":
                    return Optional.absent();
                case "registries":
                    return emptyRegistry(IRegistryContainer.class);
                case "specialComparison":
                    return emptyRegistry(ISpecialComparisonRegistry.class);
                case "getSpecialComparison":
                    return null;
                default:
                    throw new AssertionError("Unexpected AE2 registry use in ore checks: " + method);
            }
        }));
    }
}
