package com.futa_gtnh.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;

import com.futa_gtnh.common.PumpFluidUpdates;

/** An explicit local repair for flows left behind before the automatic pump hook was installed. */
public final class CommandFluidRefresh extends CommandBase {

    @Override
    public String getCommandName() {
        return "futafluidrefresh";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/futafluidrefresh [半径:1-32，默认16]";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length > 1) throw new WrongUsageException(getCommandUsage(sender));
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        int radius = args.length == 0 ? 16 : parseIntBounded(sender, args[0], 1, PumpFluidUpdates.MAX_SCAN_RADIUS);
        ChunkCoordinates position = player.getPlayerCoordinates();
        if (!PumpFluidUpdates.startScan(player.worldObj, position.posX, position.posY, position.posZ, radius, sender)) {
            sender.addChatMessage(new ChatComponentText("当前维度已有流体刷新任务，请等它完成。"));
            return;
        }
        sender.addChatMessage(new ChatComponentText("开始分批刷新你周围半径 " + radius + " 格内已加载的非源水/岩浆；流体源保持不变，仍有源头供给的流动不会被清除。"));
    }
}
