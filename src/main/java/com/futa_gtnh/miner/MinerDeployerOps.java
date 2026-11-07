package com.futa_gtnh.miner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.Config;
import com.futa_gtnh.shared.ItemKey;

import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.common.covers.Cover;

/**
 * 矿机一站式部署的编排：识别机型 → 生成结构计划 → 三级取料 → 逐格摆放 →
 * 贴 AE 采矿枢纽覆盖板 → 聊天报告。
 *
 * <p>
 * 仓位分配（见 {@link MinerStructure}）：采矿场 = 输入总线 + 输入仓（钻井液）+
 * 2×输出总线 + 免料自动维护仓 + 能源仓；油气钻机 = 输入总线 + 输出仓（油气）+
 * 免料自动维护仓 + 能源仓。某种仓的原型找不到（如维护仓未启用）时该位补外壳。
 *
 * <p>
 * 摆放只往空气/可替换方块里放（同反应堆 {@code ShellBuilder}）；<b>升级</b>路径：
 * 仓位上已经是本机型外壳时，把外壳收回来换成仓体（外壳退回共享背包）——已建好的
 * 结构再点一次部署器即可补成带仓体的版本。被其它方块占住的格子跳过并报告。
 */
public final class MinerDeployerOps {

    private MinerDeployerOps() {}

    /** 单格摆放动作。 */
    private static final class Work {

        final MinerStructure.Cell cell;
        /** 要摆的物品（外壳/框架/仓体），用于取料扣账。 */
        final ItemStack want;
        /** 仓体 MTE（仓体格才有）；null = 普通方块 setBlock。 */
        final MetaTileEntity hatch;
        /** true = 先拆掉本机型的外壳再摆仓体（外壳退料）。 */
        final boolean replacing;

        Work(MinerStructure.Cell cell, ItemStack want, MetaTileEntity hatch, boolean replacing) {
            this.cell = cell;
            this.want = want;
            this.hatch = hatch;
            this.replacing = replacing;
        }
    }

    /** 一份聚合需求（预览/报告展示用）。 */
    private static final class Demand {

        final ItemKey key;
        final ItemStack display;
        int needed;

        Demand(ItemKey key, ItemStack display) {
            this.key = key;
            this.display = display;
        }
    }

    private enum State {

        DONE,
        PLACEABLE,
        BLOCKED;

        public static final State[] VALUES = values();
    }

    /** 服务端入口（包处理器调用）。 */
    public static void run(EntityPlayerMP player, TileEntity target, boolean preview) {
        if (!Config.enableMinerDeployer) return;
        if (!(target instanceof IGregTechTileEntity base)) return;
        if (!(base.getMetaTileEntity() instanceof MetaTileEntity mte)) return;

        MinerMachines.Spec spec = MinerMachines.identify(mte);
        if (spec == null) {
            chat(player, "futa_gtnh.miner.msg.not_supported");
            return;
        }
        World world = ((TileEntity) base).getWorldObj();
        if (world == null || world.isRemote) return;

        List<MinerStructure.Cell> cells = MinerStructure.plan(mte, spec);
        if (preview) {
            preview(player, world, spec, cells);
        } else {
            deploy(player, world, base, spec, cells);
        }
    }

    // ==================================================================
    // 预览（潜行右键：只报清单不动方块）
    // ==================================================================

    private static void preview(EntityPlayerMP player, World world, MinerMachines.Spec spec,
        List<MinerStructure.Cell> cells) {
        Map<ItemKey, Demand> demands = new LinkedHashMap<>();
        int done = 0, blocked = 0;
        for (MinerStructure.Cell cell : cells) {
            ItemStack want = wantOfCell(cell, spec);
            if (want == null) continue; // 仓体原型缺失 → 补外壳位，不单列
            switch (classify(world, cell, want)) {
                case DONE -> done++;
                case BLOCKED -> blocked++;
                default -> {
                    Demand demand = demandOf(demands, want);
                    if (demand != null) demand.needed++;
                }
            }
        }
        chat(player, "futa_gtnh.miner.msg.preview_head");
        for (Demand demand : demands.values()) {
            chat(player, "futa_gtnh.miner.msg.preview_row", demand.display.getDisplayName(), demand.needed);
        }
        chat(player, "futa_gtnh.miner.msg.preview_tail", done, blocked);
        chat(player, "futa_gtnh.miner.msg.preview_hint");
    }

    // ==================================================================
    // 部署
    // ==================================================================

    private static void deploy(EntityPlayerMP player, World world, IGregTechTileEntity base, MinerMachines.Spec spec,
        List<MinerStructure.Cell> cells) {
        int cx = base.getXCoord();
        int cz = base.getZCoord();

        // ---- 分类 + 组需求 ----
        Map<ItemKey, Demand> demands = new LinkedHashMap<>();
        List<Work> work = new ArrayList<>();
        int done = 0, blocked = 0;
        for (MinerStructure.Cell cell : cells) {
            ItemStack want = wantOfCell(cell, spec);
            if (want == null) continue; // 仓体原型缺失 → 该位留给外壳

            State state = classify(world, cell, want);
            if (state == State.DONE) {
                done++;
                continue;
            }
            if (state == State.PLACEABLE) {
                work.add(new Work(cell, want, hatchOf(cell, spec), false));
                Demand demand = demandOf(demands, want);
                if (demand != null) demand.needed++;
                continue;
            }
            // BLOCKED：仓位格上是本机型外壳 → 升级（拆外壳换仓体）；其余跳过
            if (cell.code == 'b' && isOwnCasing(world, cell, spec)) {
                MetaTileEntity hatch = hatchOf(cell, spec);
                if (hatch == null) {
                    blocked++;
                    continue;
                }
                ItemStack hatchStack = hatch.getStackForm(1);
                work.add(new Work(cell, hatchStack, hatch, true));
                Demand demand = demandOf(demands, hatchStack);
                if (demand != null) demand.needed++;
                continue;
            }
            blocked++;
        }

        if (work.isEmpty()) {
            chat(player, "futa_gtnh.miner.msg.deploy_nothing", done, blocked);
            attachCover(player, base);
            return;
        }

        // ---- 三级取料 ----
        Map<ItemKey, Integer> demandCounts = new LinkedHashMap<>();
        for (Demand demand : demands.values()) {
            if (demand.needed > 0) demandCounts.put(demand.key, demand.needed);
        }
        Map<ItemKey, Integer> pool = new LinkedHashMap<>();
        int shortageTotal = MinerMaterials.satisfy(player, demandCounts, pool);

        // ---- 摆放（摆一个扣一个；断了账也是平的）----
        int placed = 0;
        for (Work op : work) {
            if (MinerMaterials.poolCount(pool) <= 0) break;

            if (op.replacing) {
                // 升级：先收回本机型外壳（放不成再补回来）
                world.setBlockToAir(op.cell.x, op.cell.y, op.cell.z);
            }
            boolean placedOk;
            if (op.hatch != null) {
                placedOk = MinerStructure.placeHatch(world, op.cell.x, op.cell.y, op.cell.z, op.hatch, player, cx, cz);
            } else {
                Block block = Block.getBlockFromItem(op.want.getItem());
                int meta = op.want.getItemDamage();
                placedOk = block != null && world.setBlock(op.cell.x, op.cell.y, op.cell.z, block, meta, 3);
            }
            if (!placedOk) {
                if (op.replacing) restoreCasing(world, op.cell, spec);
                continue;
            }
            MinerMaterials.consumeOne(pool, ItemKey.of(op.want));
            placed++;
        }

        // ---- 余料退回共享背包 ----
        MinerMaterials.refund(pool);

        // ---- 报告 ----
        chat(player, "futa_gtnh.miner.msg.deploy_head", placed, done, blocked);
        if (shortageTotal > 0) {
            chat(player, "futa_gtnh.miner.msg.deploy_shortage", shortageTotal);
        }
        chat(player, "futa_gtnh.miner.msg.deploy_hint");
        attachCover(player, base);
    }

    // ==================================================================
    // 覆盖板
    // ==================================================================

    /** 贴 AE 采矿枢纽覆盖板：材料同三级取料；没有覆盖板物品就提示手动贴。 */
    private static void attachCover(EntityPlayerMP player, IGregTechTileEntity controller) {
        ItemStack coverItem = MinerRegistration.minerIoStack();
        if (coverItem == null) return;
        // IGregTechTileEntity 本身就实现了 ICoverable，直接当覆盖板宿主用
        ICoverable coverable = controller;

        ItemKey coverKey = ItemKey.of(coverItem);
        if (coverKey == null) return;
        Map<ItemKey, Integer> demands = new LinkedHashMap<>();
        demands.put(coverKey, 1);
        Map<ItemKey, Integer> pool = new LinkedHashMap<>();
        if (MinerMaterials.satisfy(player, demands, pool) > 0) {
            MinerMaterials.refund(pool);
            chat(player, "futa_gtnh.miner.msg.cover_missing");
            return;
        }

        // 选贴的面：跳过正面（控制器语义保留）和已有覆盖板的面
        ForgeDirection front = controller.getFrontFacing();
        ForgeDirection chosen = null;
        ForgeDirection[] preference = { ForgeDirection.UP, ForgeDirection.NORTH, ForgeDirection.SOUTH,
            ForgeDirection.WEST, ForgeDirection.EAST, ForgeDirection.DOWN };
        for (ForgeDirection side : preference) {
            if (side == front) continue;
            if (coverable.hasCoverAtSide(side)) continue;
            chosen = side;
            break;
        }
        if (chosen == null) {
            MinerMaterials.refund(pool);
            chat(player, "futa_gtnh.miner.msg.cover_no_side");
            return;
        }

        Cover cover = CoverRegistry.buildCover(new ItemStack(coverItem.getItem(), 1, 0), chosen, coverable);
        if (!(cover instanceof CoverMinerIo)) {
            MinerMaterials.refund(pool);
            chat(player, "futa_gtnh.miner.msg.cover_missing");
            return;
        }
        coverable.attachCover(cover);
        cover.onPlayerAttach(player, new ItemStack(coverItem.getItem(), 1, 0));
        MinerMaterials.consumeOne(pool, coverKey);
        MinerMaterials.refund(pool);
        chat(player, "futa_gtnh.miner.msg.cover_attached");
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 一格该摆什么：
     * <ul>
     * <li>'f' → 本机型框架；'c' → 本机型外壳；</li>
     * <li>'b' + 角色 → 对应仓体物品（原型缺失 → null，该位留给外壳）；</li>
     * <li>'b' 无角色 → 补外壳。</li>
     * </ul>
     */
    private static ItemStack wantOfCell(MinerStructure.Cell cell, MinerMachines.Spec spec) {
        return switch (cell.code) {
            case 'f' -> MinerMachines.frameStack(spec);
            case 'c' -> MinerMachines.casingStack(spec);
            case 'b' -> {
                ItemStack hatch = hatchStackOf(cell, spec);
                yield hatch != null ? hatch : MinerMachines.casingStack(spec);
            }
            default -> null;
        };
    }

    private static Demand demandOf(Map<ItemKey, Demand> demands, ItemStack want) {
        ItemKey key = ItemKey.of(want);
        if (key == null) return null;
        Demand demand = demands.get(key);
        if (demand == null) {
            demand = new Demand(key, want);
            demands.put(key, demand);
        }
        return demand;
    }

    /** 仓体格的原型物品（不含补外壳的兜底）。 */
    private static ItemStack hatchStackOf(MinerStructure.Cell cell, MinerMachines.Spec spec) {
        if (cell.role == null) return null;
        return switch (cell.role) {
            case INPUT_HATCH -> MinerMachines.inputHatchStack();
            case INPUT_BUS -> MinerMachines.inputBusStack();
            case OUTPUT_BUS -> MinerMachines.outputBusStack();
            case OUTPUT_HATCH -> MinerMachines.outputHatchStack();
            case MAINTENANCE -> MinerMachines.autoMaintenanceStack();
            case ENERGY -> MinerMachines.energyHatchStack(spec.minTier);
        };
    }

    /** 仓体格的原型 MTE（放置用）；没有角色或原型缺失 = null。 */
    private static MetaTileEntity hatchOf(MinerStructure.Cell cell, MinerMachines.Spec spec) {
        if (cell.role == null) return null;
        return switch (cell.role) {
            case INPUT_HATCH -> MinerMachines.findHatch(MTEHatchInput.class, 1);
            case INPUT_BUS -> MinerMachines.findHatch(MTEHatchInputBus.class, 1);
            case OUTPUT_BUS -> MinerMachines.findHatch(MTEHatchOutputBus.class, 1);
            case OUTPUT_HATCH -> MinerMachines.findHatch(MTEHatchOutput.class, 1);
            case MAINTENANCE -> MinerRegistration.maintenanceMte();
            case ENERGY -> MinerMachines.findHatch(MTEHatchEnergy.class, spec.minTier);
        };
    }

    /** 格子现状：已就位 / 可摆（空气或可替换、无实体）/ 被挡。 */
    private static State classify(World world, MinerStructure.Cell cell, ItemStack want) {
        int x = cell.x, y = cell.y, z = cell.z;
        Block current = world.getBlock(x, y, z);
        Block wantBlock = Block.getBlockFromItem(want.getItem());
        int wantMeta = want.getItemDamage();

        if (wantBlock != null && current == wantBlock && world.getBlockMetadata(x, y, z) == wantMeta) {
            return State.DONE;
        }

        // 仓体物品：已有同 ID 的仓体也算就位（玩家手动放过正确的仓）
        if (want.getItem() instanceof ItemBlock) {
            TileEntity te = world.getTileEntity(x, y, z);
            if (te instanceof IGregTechTileEntity gte && gte.getMetaTileID() == (short) want.getItemDamage()) {
                return State.DONE;
            }
        }

        if ((world.isAirBlock(x, y, z) || current.isReplaceable(world, x, y, z))
            && !isBlockedByEntity(world, x, y, z)) {
            return State.PLACEABLE;
        }
        return State.BLOCKED;
    }

    /** 仓位上是不是本机型自己的外壳（可拆了换仓体）。 */
    private static boolean isOwnCasing(World world, MinerStructure.Cell cell, MinerMachines.Spec spec) {
        ItemStack casing = MinerMachines.casingStack(spec);
        if (casing == null) return false;
        Block casingBlock = Block.getBlockFromItem(casing.getItem());
        return world.getBlock(cell.x, cell.y, cell.z) == casingBlock
            && world.getBlockMetadata(cell.x, cell.y, cell.z) == casing.getItemDamage();
    }

    /** 升级失败回滚：把拆掉的外壳补回原位。 */
    private static void restoreCasing(World world, MinerStructure.Cell cell, MinerMachines.Spec spec) {
        ItemStack casing = MinerMachines.casingStack(spec);
        if (casing == null) return;
        Block casingBlock = Block.getBlockFromItem(casing.getItem());
        if (casingBlock == null) return;
        world.setBlock(cell.x, cell.y, cell.z, casingBlock, casing.getItemDamage(), 3);
    }

    /** 方块要放的位置上有没有生物站着（不把人封进结构里）。 */
    private static boolean isBlockedByEntity(World world, int x, int y, int z) {
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
        return !world.getEntitiesWithinAABB(EntityLivingBase.class, box)
            .isEmpty();
    }

    private static void chat(EntityPlayerMP player, String key, Object... args) {
        if (player == null) return;
        player.addChatMessage(new ChatComponentTranslation(key, args));
    }
}
