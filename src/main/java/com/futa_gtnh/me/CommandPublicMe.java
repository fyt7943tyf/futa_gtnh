package com.futa_gtnh.me;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

/** Minimal command access to the same asynchronous service used by the shared terminal. */
public final class CommandPublicMe extends CommandBase {

    @Override
    public String getCommandName() {
        return "futame";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/futame <info|plan 数量|planfluid 毫桶数|confirm|status|cancel>（plan 以手持物品为目标）";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "info" : args[0];
        if ("info".equalsIgnoreCase(action)) {
            reply(sender, PublicMeNetwork.status());
            return;
        }
        if (!(sender instanceof EntityPlayerMP player)) {
            reply(sender, "合成操作需要玩家身份");
            return;
        }
        if ("confirm".equalsIgnoreCase(action)) reply(sender, PublicMeCrafting.confirm(player));
        else if ("status".equalsIgnoreCase(action)) reply(sender, PublicMeCrafting.status(player));
        else if ("cancel".equalsIgnoreCase(action)) reply(sender, PublicMeCrafting.cancel(player));
        else if ("plan".equalsIgnoreCase(action) || "planfluid".equalsIgnoreCase(action)) {
            long amount;
            try {
                amount = args.length > 1 ? Long.parseLong(args[1]) : 1;
            } catch (NumberFormatException invalid) {
                reply(sender, "请输入有效的合成数量");
                return;
            }
            ItemStack held = player.getHeldItem();
            IAEStack<?> output;
            if ("planfluid".equalsIgnoreCase(action)) {
                FluidStack fluid = held == null ? null : FluidContainerRegistry.getFluidForFilledItem(held);
                output = fluid == null ? null : AEFluidStack.create(fluid);
            } else output = held == null ? null : AEItemStack.create(held);
            if (output == null) {
                reply(sender, "请手持目标物品；流体计划请手持对应的已装液容器");
                return;
            }
            output.setStackSize(amount);
            reply(sender, PublicMeCrafting.plan(player, output));
        } else reply(sender, getCommandUsage(sender));
    }

    private static void reply(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }
}
