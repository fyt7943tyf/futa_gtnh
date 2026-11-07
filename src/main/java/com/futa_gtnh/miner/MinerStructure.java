package com.futa_gtnh.miner;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/**
 * 采矿场/油气钻机的结构布局与逐格摆放。
 *
 * <p>
 * 形状来自 GT5u {@code MTEDrillerBase} 的结构定义（3×7×3，经 transpose 后
 * {@code shape[z][y][x]}，z+ 为控制器背面）：
 * 
 * <pre>
 * z=0: y0-2 空，y3-5 " f "，y6 "b~b"   ← 控制器所在层（含左右两个仓位）
 * z=1: y0-2 " f "，y3-5 "fcf"，y6 "bbb" ← 钻井竖井层（管道从这里往下走）
 * z=2: y0-2 空，y3-5 " f "，y6 "bbb"
 * </pre>
 * 
 * 世界坐标 = 控制器 + 背面×z + 垂直方向×(x-1) + 上×(y-6)。整族形状沿 x 镜像对称，
 * 所以「垂直方向」取背面的任一水平垂直轴都等价。
 *
 * <p>
 * 摆放约束与反应堆 {@code ShellBuilder} 相同：只往空气/可替换方块里放、被实体占着的
 * 格子跳过；仓体用 {@code ItemMachines.placeBlockAt} 的同款管线放置（setBlock 按
 * baseType + {@code setInitialValuesAsNBT} 挂 MTE ID + 设所有者 + 设朝向）。
 */
public final class MinerStructure {

    private MinerStructure() {}

    /** 仓体角色（'b' 格的用途分配； famil 由 Ops 决定具体取哪种仓）。 */
    public enum HatchRole {

        /** 钻井液入口（仅采矿场）。 */
        INPUT_HATCH,

        /** 采矿管道入口（两族都要）。 */
        INPUT_BUS,

        /** 矿物出口总线（仅采矿场，给两个）。 */
        OUTPUT_BUS,

        /** 油气出口仓（仅油气钻机）。 */
        OUTPUT_HATCH,

        /** 免料自动维护仓。 */
        MAINTENANCE,

        /** 能源仓（玩家备料才摆）。 */
        ENERGY;

        public static final HatchRole[] VALUES = values();
    }

    /** 一个结构格：位置 + 要摆什么。 */
    public static final class Cell {

        public final int x, y, z;
        /** 'f' = 框架，'c' = 外壳，'b' = 仓位（或补外壳）。 */
        public final char code;
        /** 'b' 格的角色；非仓位格为 null。 */
        public final HatchRole role;

        Cell(int x, int y, int z, char code, HatchRole role) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.code = code;
            this.role = role;
        }
    }

    /**
     * 每个家族的仓位分配表：piece 坐标 (x,y,z) → 角色。
     * 没分到角色的 'b' 格补外壳。
     */
    private static HatchRole oreRole(int px, int py, int pz) {
        if (py != 6) return null;
        if (pz == 1 && px == 1) return HatchRole.INPUT_BUS;
        if (pz == 1 && px == 0) return HatchRole.INPUT_HATCH;
        if (pz == 1 && px == 2) return HatchRole.OUTPUT_BUS;
        if (pz == 2 && px == 1) return HatchRole.OUTPUT_BUS;
        if (pz == 2 && px == 0) return HatchRole.MAINTENANCE;
        if (pz == 2 && px == 2) return HatchRole.ENERGY;
        return null; // z0 的两个边位补外壳
    }

    private static HatchRole oilRole(int px, int py, int pz) {
        if (py != 6) return null;
        if (pz == 1 && px == 1) return HatchRole.INPUT_BUS;
        if (pz == 1 && px == 0) return HatchRole.OUTPUT_HATCH;
        if (pz == 1 && px == 2) return HatchRole.MAINTENANCE;
        if (pz == 2 && px == 1) return HatchRole.ENERGY;
        return null; // 其余补外壳
    }

    /**
     * 生成完整结构计划。
     *
     * @return 形状里全部非空格（除控制器本体），共 27 格；由调用方按世界现状分拣摆放
     */
    public static List<Cell> plan(MetaTileEntity controller, MinerMachines.Spec spec) {
        IGregTechTileEntity base = controller.getBaseMetaTileEntity();
        int cx = base.getXCoord();
        int cy = base.getYCoord();
        int cz = base.getZCoord();
        ForgeDirection back = base.getBackFacing();
        ForgeDirection side = back.getRotation(ForgeDirection.UP); // 水平垂直轴（镜像对称，取哪边都行）

        List<Cell> cells = new ArrayList<>();
        for (int py = 0; py <= 6; py++) {
            for (int pz = 0; pz <= 2; pz++) {
                for (int px = 0; px <= 2; px++) {
                    if (py == 6 && pz == 0 && px == 1) continue; // 控制器本体

                    char code = cellAt(px, py, pz);
                    if (code == ' ') continue; // 形状里的空气位（结构四角的空档）

                    int x = cx + back.offsetX * pz + side.offsetX * (px - 1);
                    int y = cy + (py - 6);
                    int z = cz + back.offsetZ * pz + side.offsetZ * (px - 1);

                    HatchRole role = null;
                    if (code == 'b') {
                        role = spec.family == MinerMachines.Family.ORE_DRILL ? oreRole(px, py, pz)
                            : oilRole(px, py, pz);
                    }
                    cells.add(new Cell(x, y, z, code, role));
                }
            }
        }
        return cells;
    }

    /**
     * 形状表：piece (x,y,z) 的格子编码。
     * <ul>
     * <li>y0-2：z1 列 " f "（x=1 框架，即钻井竖井下段），其余为空；</li>
     * <li>y3-5：z1 行 "fcf"（两侧框架、中点外壳），z0/z2 行 " f "（中点框架），四角为空；</li>
     * <li>y6：整层 "b~b"/"bbb"（8 个仓位或补外壳）。</li>
     * </ul>
     */
    private static char cellAt(int px, int py, int pz) {
        if (py <= 2) {
            if (pz != 1) return ' ';
            return px == 1 ? 'f' : ' ';
        }
        if (pz == 1) return px == 1 ? 'c' : 'f';
        return px == 1 ? 'f' : ' ';
    }

    /**
     * 仓体朝向：背离控制器的水平主轴方向（外侧面留给管道）。
     */
    public static ForgeDirection hatchFacing(int hx, int hy, int hz, int cx, int cz) {
        int dx = hx - cx;
        int dz = hz - cz;
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? ForgeDirection.EAST : ForgeDirection.WEST;
        return dz > 0 ? ForgeDirection.SOUTH : ForgeDirection.NORTH;
    }

    /**
     * 按 GT {@code ItemMachines.placeBlockAt} 的管线放置一台仓体。
     *
     * <p>
     * setBlock 的 meta 是 {@code getTileEntityBaseType()}（大 ID 塞不进 12 位方块
     * metadata，GT 把真实 MTE ID 存在方块实体里，物品 damage 就是 ID），随后
     * {@code setInitialValuesAsNBT} 挂上 ID、设所有者与朝向。
     *
     * @return 成功 = true；位置被占/放置失败 = false（调用方回收材料）
     */
    public static boolean placeHatch(World world, int x, int y, int z, MetaTileEntity hatch, EntityPlayerMP owner,
        int cx, int cz) {
        ItemStack stack = hatch.getStackForm(1);
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block == null || !(stack.getItem() instanceof ItemBlock)) return false;
        short id = (short) stack.getItemDamage();
        if (!world.setBlock(x, y, z, block, hatch.getTileEntityBaseType(), 3)) return false;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IGregTechTileEntity gt)) {
            world.setBlockToAir(x, y, z);
            return false;
        }
        gt.setInitialValuesAsNBT(null, id);
        gt.setOwnerUuid(owner.getUniqueID());
        gt.setOwnerName(owner.getCommandSenderName());
        if (hatch.getProxy() != null) hatch.getProxy()
            .setOwner(owner);
        gt.setFrontFacing(hatchFacing(x, y, z, cx, cz));
        return true;
    }
}
