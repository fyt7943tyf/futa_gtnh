package com.futa_gtnh.command;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.futa_gtnh.Config;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageFile;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 管理员指令 {@code /futashared}。
 *
 * <p>
 * 破坏性操作（{@code reload} / {@code clear}）单独校验 OP，而不是靠
 * {@link #getRequiredPermissionLevel()}：那样会把 {@code info} 也一起挡在门外，
 * 而「看看现在存了多少东西」是普通玩家也该能用的。
 */
public class CommandSharedStorage extends CommandBase {

    @Override
    public String getCommandName() {
        return "futashared";
    }

    @Override
    public List<String> getCommandAliases() {
        List<String> aliases = new ArrayList<>();
        aliases.add("sharedstorage");
        aliases.add("sharedbag");
        return aliases;
    }

    /** 0 = 谁都能用；破坏性子命令自己查 OP。 */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/futashared <info|save|reload|clear|escrow>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();

        switch (sub) {
            case "info":
                sendInfo(sender);
                break;

            case "escrow":
                if (!requireOp(sender)) return;
                SharedStorage escrow = SharedStorageManager.getStorage();
                if (args.length >= 2 && "refund".equalsIgnoreCase(args[1])) {
                    if (args.length < 3 || !"confirm".equalsIgnoreCase(args[2])) {
                        reply(
                            sender,
                            EnumChatFormatting.RED
                                + "请先暂停相关 IO、关闭 ae2CellFeedCraftingCpu，并核对来源容器、目标和 ME CPU，确认这些货没有留在其中；盲目退回可能复制物品。确认：/futashared escrow refund confirm");
                        return;
                    }
                    if (Config.ae2CellFeedCraftingCpu) {
                        reply(
                            sender,
                            EnumChatFormatting.RED + "请先关闭 ae2CellFeedCraftingCpu，并暂停相关 IO；核对来源、目标和 CPU 后再退回托管库存。");
                        return;
                    }
                    escrow.refundCraftingEscrow();
                    SharedStorageManager.saveNow();
                    reply(sender, EnumChatFormatting.GREEN + "可识别的传输托管库存已退回共享库；缺失模组的条目仍保留在存档。");
                } else {
                    reply(
                        sender,
                        "传输托管（合成 / IO）：" + escrow.snapshotCraftingItems()
                            .size()
                            + " 种物品 / "
                            + escrow.snapshotCraftingFluids()
                                .size()
                            + " 种流体（不计入可用库存）。");
                    for (java.util.Map.Entry<com.futa_gtnh.shared.ItemKey, Long> entry : escrow
                        .snapshotCraftingItems()) {
                        reply(
                            sender,
                            entry.getKey()
                                .prototype()
                                .getDisplayName() + " x "
                                + format(entry.getValue()));
                    }
                    for (java.util.Map.Entry<com.futa_gtnh.shared.FluidKey, Long> entry : escrow
                        .snapshotCraftingFluids()) {
                        reply(
                            sender,
                            entry.getKey()
                                .prototype()
                                .getLocalizedName() + " x "
                                + format(entry.getValue())
                                + " mB");
                    }
                }
                break;

            case "save":
                if (!requireOp(sender)) return;
                if (SharedStorageManager.isSavingBlocked()) {
                    reply(sender, EnumChatFormatting.RED + "读取失败后已暂停保存以保护原文件；修复数据文件后请重新 reload。");
                    return;
                }
                SharedStorageManager.saveNow();
                reply(sender, EnumChatFormatting.GREEN + "共享存储已保存。");
                break;

            case "reload":
                if (!requireOp(sender)) return;
                reload(sender, args);
                break;

            case "clear":
                if (!requireOp(sender)) return;
                if (args.length < 2 || !"confirm".equalsIgnoreCase(args[1])) {
                    reply(
                        sender,
                        EnumChatFormatting.RED + "这会清空全服共享存储池里所有东西（存储总线接进来的容器不受影响），且不可撤销。"
                            + "确认请执行：/futashared clear confirm");
                    return;
                }
                SharedStorageManager.getStorage()
                    .clear();
                SharedStorageManager.invalidateSnapshot();
                SharedStorageManager.saveNow();
                SharedStorageManager.resyncAll();
                reply(sender, EnumChatFormatting.RED + "共享存储池已清空（存储总线里的容器内容未动）。");
                break;

            default:
                reply(sender, EnumChatFormatting.RED + getCommandUsage(sender));
                break;
        }
    }

    private void sendInfo(ICommandSender sender) {
        SharedStorage storage = SharedStorageManager.getStorage();
        reply(sender, EnumChatFormatting.GOLD + "===== 全服共享存储 =====");
        reply(
            sender,
            EnumChatFormatting.YELLOW + "物品："
                + EnumChatFormatting.WHITE
                + storage.itemTypeCount()
                + " 种 / "
                + format(storage.getItemTotal())
                + " 个");
        reply(
            sender,
            EnumChatFormatting.YELLOW + "流体："
                + EnumChatFormatting.WHITE
                + storage.fluidTypeCount()
                + " 种 / "
                + format(storage.getFluidTotal())
                + " L");
        reply(
            sender,
            EnumChatFormatting.YELLOW + "待写盘改动：" + EnumChatFormatting.WHITE + (storage.isDirty() ? "有" : "无"));
        if (SharedStorageManager.isSavingBlocked()) {
            reply(sender, EnumChatFormatting.RED + "读取失败后已暂停保存；修复数据文件后请重新 reload。");
        }
    }

    /**
     * 从磁盘重新读一遍，<b>丢弃内存里未保存的改动</b>。
     *
     * <p>
     * 之所以不先保存一次：这个子命令存在的意义就是「让磁盘上的内容生效」
     * （管理员手工改了数据文件、或者拿备份覆盖了正式文件）。
     * 如果先写一遍盘，管理员的修改会立刻被内存里的旧数据盖掉，指令就等于没做。
     * 代价是最近几分钟未自动保存的改动会丢，所以要求加 {@code confirm}。
     */
    private void reload(ICommandSender sender, String[] args) {
        if (!SharedStorageManager.isLoaded()) {
            reply(sender, EnumChatFormatting.RED + "共享存储尚未载入，无法重载。");
            return;
        }

        if (args.length < 2 || !"confirm".equalsIgnoreCase(args[1])) {
            reply(sender, EnumChatFormatting.RED + "重载会用磁盘上的内容覆盖当前内存数据。");
            reply(
                sender,
                EnumChatFormatting.RED + "最近 "
                    + com.futa_gtnh.Config.autosaveIntervalSeconds
                    + " 秒内尚未自动保存的改动会丢失"
                    + (SharedStorageManager.getStorage()
                        .isDirty() ? "（当前确实有未保存的改动）" : "（当前没有未保存的改动）")
                    + "。");
            reply(sender, EnumChatFormatting.RED + "如果只是想刷新界面，用不着这个指令 —— 界面本来就是实时同步的。");
            reply(sender, EnumChatFormatting.RED + "确认请执行：/futashared reload confirm");
            return;
        }

        int count = SharedStorageManager.reloadFromDisk();
        if (count < 0) {
            reply(sender, EnumChatFormatting.RED + "重载失败，详见服务端日志。");
        } else {
            reply(sender, EnumChatFormatting.GREEN + "已从磁盘重载，" + count + " 个条目。");
        }
        reply(sender, EnumChatFormatting.GRAY + "数据文件：" + SharedStorageFile.FILE_NAME);
    }

    private boolean requireOp(ICommandSender sender) {
        if (sender.canCommandSenderUseCommand(4, getCommandName())) return true;
        reply(sender, EnumChatFormatting.RED + "该子命令需要管理员权限。");
        return false;
    }

    private static void reply(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }

    private static String format(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
