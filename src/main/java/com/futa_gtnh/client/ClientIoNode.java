package com.futa_gtnh.client;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.block.TerminalIoConfig;

/**
 * 客户端记住的「某个 IO 节点方块怎么搬东西」。
 *
 * <p>
 * 和 {@link ClientTerminalIo} 一个模式：权威值在服务端那个方块实体里，
 * 这里只是它最近一次推过来的快照（{@code PacketIoNodeSync}）。
 * 带坐标和维度，是为了界面能判断「手上这份说的是不是我现在开的那个节点」。
 */
public final class ClientIoNode {

    private ClientIoNode() {}

    private static int dimension = Integer.MIN_VALUE;
    private static int x;
    private static int y;
    private static int z;
    private static final TerminalIoConfig CONFIG = new TerminalIoConfig();
    private static boolean present;
    /** 六个面里哪几面真的接着能搬东西的方块（服务端算好推过来的），位 i = ForgeDirection.getOrientation(i)。 */
    private static int itemMask;
    private static int fluidMask;
    /** 每面邻居方块的注册名（空串 = 没东西），界面标题里显示邻块是什么。 */
    private static final String[] NEIGHBOUR_NAMES = new String[6];

    public static void setConfig(int newDimension, int newX, int newY, int newZ, NBTTagCompound tag, int newItemMask,
        int newFluidMask, String[] names) {
        dimension = newDimension;
        x = newX;
        y = newY;
        z = newZ;
        CONFIG.readFromNbt(tag);
        itemMask = newItemMask;
        fluidMask = newFluidMask;
        for (int i = 0; i < 6; i++) {
            NEIGHBOUR_NAMES[i] = names != null && i < names.length ? names[i] : null;
        }
        present = true;
    }

    /** @return 这一面邻居方块的注册名（没有/未知时空串） */
    public static String getNeighbourName(int face) {
        String name = face >= 0 && face < 6 ? NEIGHBOUR_NAMES[face] : null;
        return name == null ? "" : name;
    }

    /** @return 这一面接着能收/能给的物品容器吗 */
    public static boolean hasItemConnection(int face) {
        return (itemMask & (1 << face)) != 0;
    }

    /** @return 这一面接着能收/能给的流体容器吗 */
    public static boolean hasFluidConnection(int face) {
        return (fluidMask & (1 << face)) != 0;
    }

    /** @return 服务端推过来的这份配置说的就是这几个坐标的那个节点吗 */
    public static boolean matches(int dimensionId, int blockX, int blockY, int blockZ) {
        return present && dimension == dimensionId && x == blockX && y == blockY && z == blockZ;
    }

    /** @return 界面当前绑定的节点 X 坐标（上行配置 / 请求权威值用） */
    public static int getX() {
        return x;
    }

    /** @return 界面当前绑定的节点 Y 坐标 */
    public static int getY() {
        return y;
    }

    /** @return 界面当前绑定的节点 Z 坐标 */
    public static int getZ() {
        return z;
    }

    /**
     * @return 可以直接改的那份配置
     *
     *         <p>
     *         返回的是活对象：界面点一下按钮就改它，然后整份发给服务端
     *         （{@code PacketIoNodeConfig}）。服务端收到后会回一份权威值把它覆盖掉，
     *         所以本地怎么改都不会和真实状态跑偏。
     */
    public static TerminalIoConfig get() {
        return CONFIG;
    }

    public static void clear() {
        dimension = Integer.MIN_VALUE;
        present = false;
        itemMask = 0;
        fluidMask = 0;
        CONFIG.clearFilters();
        for (net.minecraftforge.common.util.ForgeDirection face : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
            CONFIG.setMode(face, false, TerminalIoConfig.Mode.OFF);
            CONFIG.setMode(face, true, TerminalIoConfig.Mode.OFF);
        }
    }
}
