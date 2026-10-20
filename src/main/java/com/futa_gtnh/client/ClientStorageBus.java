package com.futa_gtnh.client;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.storagebus.StorageBusConfig;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

/**
 * 客户端记住的当前存储总线面配置。
 *
 * <p>
 * 权威值在服务端的方块实体里，这里只是最近一次 {@code PacketStorageBusSync}
 * 推过来的快照。一个方块实体可能有多面，界面同一时刻只看一面：
 * 切换面时向服务端重新要一份，服务端连同 {@code mask} 一起回传，
 * 界面据此知道还有哪几面可以切。
 */
public final class ClientStorageBus {

    private ClientStorageBus() {}

    private static int dimension = Integer.MIN_VALUE;
    private static int x;
    private static int y;
    private static int z;
    private static final StorageBusConfig CONFIG = new StorageBusConfig();
    private static boolean present;
    private static int selectedSide;
    private static int faceMask;
    private static String targetName = "";
    private static boolean itemTarget;
    private static boolean fluidTarget;

    public static void setConfig(int newDimension, int newX, int newY, int newZ, int newSide, int newMask,
        NBTTagCompound tag, String newTargetName, boolean newItemTarget, boolean newFluidTarget) {
        dimension = newDimension;
        x = newX;
        y = newY;
        z = newZ;
        selectedSide = newSide;
        faceMask = newMask;
        CONFIG.readFromNbt(tag);
        targetName = newTargetName == null ? "" : newTargetName;
        itemTarget = newItemTarget;
        fluidTarget = newFluidTarget;
        present = true;
    }

    public static boolean matches(int dimensionId, int blockX, int blockY, int blockZ) {
        return present && dimension == dimensionId && x == blockX && y == blockY && z == blockZ;
    }

    public static StorageBusConfig get() {
        return CONFIG;
    }

    public static int getX() {
        return x;
    }

    public static int getY() {
        return y;
    }

    public static int getZ() {
        return z;
    }

    public static int getSelectedSide() {
        return selectedSide;
    }

    public static int getFaceMask() {
        return faceMask;
    }

    public static boolean hasFace(int side) {
        return side >= 0 && side < TileEntityStorageBus.FACES && (faceMask & (1 << side)) != 0;
    }

    /** @return 从 {@code from} 开始沿 {@code direction} 找到的下一个已安装面；找不到返回 {@code from} */
    public static int nextFace(int from, int direction) {
        for (int step = 1; step <= TileEntityStorageBus.FACES; step++) {
            int side = (from + direction * step + TileEntityStorageBus.FACES) % TileEntityStorageBus.FACES;
            if (hasFace(side)) return side;
        }
        return from;
    }

    public static String getTargetName() {
        return targetName;
    }

    public static boolean hasItemTarget() {
        return itemTarget;
    }

    public static boolean hasFluidTarget() {
        return fluidTarget;
    }

    public static void clear() {
        dimension = Integer.MIN_VALUE;
        present = false;
        selectedSide = 0;
        faceMask = 0;
        targetName = "";
        itemTarget = false;
        fluidTarget = false;
        CONFIG.readFromNbt(null);
    }
}
