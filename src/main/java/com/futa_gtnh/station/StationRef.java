package com.futa_gtnh.station;

/**
 * 「这一屏开的那个合成站」——把界面里需要的东西一次取齐：坐标 + 那份客户端镜像。
 *
 * <p>
 * 单独抽一个类出来，是为了让 {@code client/StoragePanel} 这种纯客户端代码<b>不用碰
 * tconstruct 的类型</b>：坐标和镜像的取法（要读 {@code CraftingStationContainer.logic}）
 * 全在 {@code TinkersScreens} 里，那边有匠魂在场守卫。
 */
public final class StationRef {

    public final int dimension;
    public final int x;
    public final int y;
    public final int z;
    /** 客户端那份只读镜像（服务端那份不在这里）。 */
    public final SharedStorageInventory inventory;

    public StationRef(int dimension, int x, int y, int z, SharedStorageInventory inventory) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.inventory = inventory;
    }
}
