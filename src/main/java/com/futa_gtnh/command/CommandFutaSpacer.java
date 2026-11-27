package com.futa_gtnh.command;

import java.util.List;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import com.futa_gtnh.spacecenter.PlanetRegistry;
import com.futa_gtnh.spacecenter.PlanetSeeds;
import com.futa_gtnh.spacecenter.VirtualOilField;
import com.futa_gtnh.spacecenter.VirtualVeinSim;

import gregtech.api.interfaces.IOreMaterial;

/**
 * {@code /futaspacer}：深空采矿指挥中心的调试命令。
 *
 * <ul>
 * <li>{@code /futaspacer planets} —— 列出已注册的星球（维度键 / 维度 id / 火箭等级 / 矿脉与油田概况）；</li>
 * <li>{@code /futaspacer probe <dimName> <cx> <cz> [era]} —— 用虚拟模拟器离线复现该星球
 * 该区块的矿脉与油田，输出可与真实星球（探矿仪 / Visual Prospecting）对照，
 * 验证确定性复现的正确性。</li>
 * </ul>
 */
public final class CommandFutaSpacer extends CommandBase {

    @Override
    public String getCommandName() {
        return "futaspacer";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/futaspacer planets | probe <dimName> <chunkX> <chunkZ> [era]";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));
        if ("planets".equalsIgnoreCase(args[0])) {
            listPlanets(sender);
        } else if ("probe".equalsIgnoreCase(args[0])) {
            if (args.length < 4) throw new WrongUsageException(getCommandUsage(sender));
            String dimName = args[1];
            int cx = parseIntBounded(sender, args[2], -30000000, 30000000);
            int cz = parseIntBounded(sender, args[3], -30000000, 30000000);
            int era = args.length >= 5 ? parseIntBounded(sender, args[4], 0, 255) : 0;
            probe(sender, dimName, cx, cz, era);
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void listPlanets(ICommandSender sender) {
        List<PlanetRegistry.PlanetInfo> planets = PlanetRegistry.planets();
        if (planets.isEmpty()) {
            sender.addChatMessage(new ChatComponentText("星球表为空（没装 Galacticraft，或没有任何星球有矿/油）。"));
            return;
        }
        sender.addChatMessage(new ChatComponentText("已注册星球（维度键 / dimId / 火箭等级 / 矿脉 / 油田流体）："));
        for (PlanetRegistry.PlanetInfo info : planets) {
            sender.addChatMessage(
                new ChatComponentText(
                    String.format(
                        "  %s / %d / T%d / 矿脉%d种(权重%d) / 流体%d种",
                        info.dimName,
                        info.dimensionId,
                        info.tierRequirement,
                        info.veins.size(),
                        info.totalVeinWeight(),
                        info.fluids.size())));
        }
    }

    private void probe(ICommandSender sender, String dimName, int chunkX, int chunkZ, int era) {
        PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(dimName);
        if (info == null) {
            sender.addChatMessage(new ChatComponentText("未知星球 " + dimName + "，用 /futaspacer planets 查列表。"));
            return;
        }
        Long seed = PlanetSeeds.seedOf(info.dimensionId);
        if (seed == null) seed = PlanetRegistry.tryCaptureSeed(info.dimensionId);
        if (seed == null) {
            sender.addChatMessage(new ChatComponentText("星球 " + dimName + " 还没有勘查记录（该维度未加载过）；先进入该星球一次。"));
            return;
        }
        sender.addChatMessage(
            new ChatComponentText(
                String.format(
                    "probe %s (dim %d) 区块 (%d,%d) era=%d seed=%d",
                    dimName,
                    info.dimensionId,
                    chunkX,
                    chunkZ,
                    era,
                    seed)));

        // --- 矿脉 ---
        VirtualVeinSim.ChunkOre ore = VirtualVeinSim
            .simulateChunk(dimName, seed, info.dimensionId, era, chunkX, chunkZ);
        if (ore.isEmpty()) {
            sender.addChatMessage(new ChatComponentText("  矿脉：无（与真实星球该区块一致：不在任何种子区块的影响范围内）"));
        } else {
            sender.addChatMessage(
                new ChatComponentText(
                    "  矿脉：" + ore.veinNames
                        + " 最低层 Y="
                        + (ore.minVeinY == Integer.MAX_VALUE ? "-" : ore.minVeinY)
                        + " 总方块数="
                        + ore.totalBlocks()));
            for (Map.Entry<IOreMaterial, Integer> entry : ore.veinBlocks.entrySet()) {
                sender.addChatMessage(
                    new ChatComponentText(
                        String.format(
                            "    %s ×%d",
                            entry.getKey()
                                .getInternalName(),
                            entry.getValue())));
            }
            if (!ore.smallOres.isEmpty()) {
                StringBuilder small = new StringBuilder("    小矿：");
                for (Map.Entry<IOreMaterial, Integer> entry : ore.smallOres.entrySet()) {
                    small.append(
                        entry.getKey()
                            .getInternalName())
                        .append('×')
                        .append(entry.getValue())
                        .append(' ');
                }
                sender.addChatMessage(new ChatComponentText(small.toString()));
            }
        }

        // --- 油田 ---
        VirtualOilField.PristineField field = VirtualOilField.pristine(info.dimensionId, seed, chunkX, chunkZ);
        if (field == null || field.fluid.getFluid() == null) {
            sender.addChatMessage(new ChatComponentText("  油田：无"));
        } else {
            sender.addChatMessage(
                new ChatComponentText(
                    String.format(
                        "  油田：%s 存量=%d 上界产量/周期=%d（钻机按 ×%.2f 系数取用）",
                        field.fluid.getFluid()
                            .getUnlocalizedName(),
                        field.amount,
                        field.upperBoundPerCycle(),
                        com.futa_gtnh.Config.spaceCenterOilYieldFactor)));
        }
        sender.addChatMessage(new ChatComponentText("  对照方法：到真实星球同区块用探矿仪/VP 查看，或实地挖开核对（同 seed 应一致）。"));
    }
}
