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
    /** 六个面里哪几面真的挨着能搬东西的方块（服务端算好推过来的），位 i = ForgeDirection.getOrientation(i)。 */
    private static int itemMask;
    private static int fluidMask;
    /** 每面邻居方块的注册名与 metadata（空串 = 没东西），用来把那圈方块画成它本来的样子。 */
    private static final String[] NEIGHBOUR_NAMES = new String[6];
    private static final byte[] NEIGHBOUR_METAS = new byte[6];

    public static void setConfig(int newDimension, int newX, int newY, int newZ, NBTTagCompound tag, int newItemMask,
        int newFluidMask, String[] names, byte[] metas) {
        dimension = newDimension;
        x = newX;
        y = newY;
        z = newZ;
        CONFIG.readFromNbt(tag);
        itemMask = newItemMask;
        fluidMask = newFluidMask;
        for (int i = 0; i < 6; i++) {
            NEIGHBOUR_NAMES[i] = names != null && i < names.length ? names[i] : null;
            NEIGHBOUR_METAS[i] = metas != null && i < metas.length ? metas[i] : 0;
        }
        present = true;
    }

    /** @return 这一面邻居方块的注册名（没有/未知时空串） */
    public static String getNeighbourName(int face) {
        String name = face >= 0 && face < 6 ? NEIGHBOUR_NAMES[face] : null;
        return name == null ? "" : name;
    }

    /** @return 这一面邻居方块的 metadata */
    public static byte getNeighbourMeta(int face) {
        return face >= 0 && face < 6 ? NEIGHBOUR_METAS[face] : 0;
    }

    /** @return 这一面挨着能收/能给的物品容器吗 */
    public static boolean hasItemTarget(int face) {
        return (itemMask & (1 << face)) != 0;
    }

    /** @return 这一面挨着能收/能给的流体容器吗 */
    public static boolean hasFluidTarget(int face) {
        return (fluidMask & (1 << face)) != 0;
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
        itemMask = 0;
        fluidMask = 0;
        CONFIG.clearFilters();
        for (net.minecraftforge.common.util.ForgeDirection face : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
            CONFIG.setMode(face, false, TerminalIoConfig.Mode.OFF);
            CONFIG.setMode(face, true, TerminalIoConfig.Mode.OFF);
        }
    }
}
