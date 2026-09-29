package com.futa_gtnh.client;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.block.TerminalIoConfig;

/**
 * 客户端记住的「某个共享终端方块怎么搬东西」。
 *
 * <p>
 * 只为了画配置界面：权威值在服务端那个方块实体里，这里只是它最近一次推过来的快照
 * （见 {@code PacketTerminalIoSync}）。带着坐标和维度，是因为玩家可能同时开着两个终端，
 * 界面必须能判断「手上这份说的是不是我现在开的那个」。
 */
public final class ClientTerminalIo {

    private ClientTerminalIo() {}

    private static int dimension = Integer.MIN_VALUE;
    private static int x;
    private static int y;
    private static int z;
    private static final TerminalIoConfig CONFIG = new TerminalIoConfig();
    private static boolean present;

    public static void setConfig(int newDimension, int newX, int newY, int newZ, NBTTagCompound tag) {
        dimension = newDimension;
        x = newX;
        y = newY;
        z = newZ;
        CONFIG.readFromNbt(tag);
        present = true;
    }

    /** @return 服务端推过来的这份配置说的就是这几个坐标的那个方块吗 */
    public static boolean matches(int dimensionId, int blockX, int blockY, int blockZ) {
        return present && dimension == dimensionId && x == blockX && y == blockY && z == blockZ;
    }

    /**
     * @return 可以直接改的那份配置
     *
     *         <p>
     *         返回的是活对象：界面点一下按钮就改它，然后整份发给服务端。
     *         服务端收到后会回一份权威值把它覆盖掉，所以本地怎么改都不会和真实状态跑偏。
     */
    public static TerminalIoConfig get() {
        return CONFIG;
    }

    public static void clear() {
        dimension = Integer.MIN_VALUE;
        present = false;
        CONFIG.clearFilter();
        for (net.minecraftforge.common.util.ForgeDirection face : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
            CONFIG.setMode(face, false, TerminalIoConfig.Mode.OFF);
            CONFIG.setMode(face, true, TerminalIoConfig.Mode.OFF);
        }
    }
}
