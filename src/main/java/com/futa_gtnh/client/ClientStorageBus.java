package com.futa_gtnh.client;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.storagebus.StorageBusConfig;

/**
 * 客户端记住的某条存储总线配置。
 *
 * <p>
 * 权威值在服务端的方块实体里，这里只是最近一次 {@code PacketStorageBusSync}
 * 推过来的快照。界面每帧检查坐标/维度是否还是当前这条总线，不是就自动向服务端
 * 再要一份 —— 和 {@link ClientIoNode} 同一个模式。
 */
public final class ClientStorageBus {

    private ClientStorageBus() {}

    private static int dimension = Integer.MIN_VALUE;
    private static int x;
    private static int y;
    private static int z;
    private static final StorageBusConfig CONFIG = new StorageBusConfig();
    private static boolean present;
    private static String targetName = "";
    private static boolean itemTarget;
    private static boolean fluidTarget;

    public static void setConfig(int newDimension, int newX, int newY, int newZ, NBTTagCompound tag,
        String newTargetName, boolean newItemTarget, boolean newFluidTarget) {
        dimension = newDimension;
        x = newX;
        y = newY;
        z = newZ;
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
        targetName = "";
        itemTarget = false;
        fluidTarget = false;
        CONFIG.readFromNbt(null);
    }
}
