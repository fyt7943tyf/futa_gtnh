package com.futa_gtnh.command;

import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;

import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;

/** Read-only flow queries are public; every metadata mutation is checked by SupplyTracker. */
public final class CommandProductionLines extends CommandBase {

    @Override
    public String getCommandName() {
        return "futalines";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/futalines list [页] | create <名称> | rename <产线UUID> <名称> | delete <产线UUID> | "
            + "bind <产线UUID> <设备ID> | bindpos <产线UUID> <ae_bus|io|ae_machine> <维度> <x> <y> <z> <面0..5或-1> | "
            + "unbind <设备ID> | query [产线UUID|all] [1|10|60] [设备页] | source <设备ID> [1|10|60] [原料页]";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        try {
            if ("list".equals(action)) {
                list(sender, SupplyTracker.productionSnapshot("", "", 10, number(args, 1, 1) - 1, 0, 0));
                return;
            }
            if ("query".equals(action) || "source".equals(action)) {
                boolean device = "source".equals(action);
                if (device && args.length < 2) throw new IllegalArgumentException("请填写设备 ID");
                String selected = args.length < 2 || "all".equals(args[1]) ? "" : args[1];
                int minutes = number(args, 2, 10);
                if (minutes != 1 && minutes != 10 && minutes != 60)
                    throw new IllegalArgumentException("统计窗口只能是 1、10 或 60 分钟");
                int page = number(args, 3, 1) - 1;
                show(
                    sender,
                    SupplyTracker.productionSnapshot(
                        device ? "" : selected,
                        device ? selected : "",
                        minutes,
                        0,
                        device ? 0 : page,
                        device ? page : 0),
                    device);
                return;
            }
            NBTTagCompound request = new NBTTagCompound();
            request.setString("action", action);
            switch (action) {
                case "create":
                    require(args, 2);
                    request.setString("name", join(args, 1));
                    break;
                case "rename":
                    require(args, 3);
                    request.setString("lineId", args[1]);
                    request.setString("name", join(args, 2));
                    break;
                case "delete":
                    require(args, 2);
                    request.setString("lineId", args[1]);
                    break;
                case "bind":
                    require(args, 3);
                    request.setString("lineId", args[1]);
                    request.setString("sourceId", args[2]);
                    break;
                case "unbind":
                    require(args, 2);
                    request.setString("sourceId", args[1]);
                    break;
                case "bindpos":
                    require(args, 8);
                    request.setString("action", "bind");
                    request.setString("lineId", args[1]);
                    request.setTag(
                        "source",
                        new SupplySource(
                            kind(args[2]),
                            Integer.parseInt(args[3]),
                            Integer.parseInt(args[4]),
                            Integer.parseInt(args[5]),
                            Integer.parseInt(args[6]),
                            Integer.parseInt(args[7]),
                            "").write());
                    break;
                default:
                    reply(sender, getCommandUsage(sender));
                    return;
            }
            NBTTagCompound result = SupplyTracker.productionMutation(sender, request);
            reply(sender, result.getString("message"));
            if (result.getBoolean("ok") && !result.getString("lineId")
                .isEmpty()) reply(sender, "产线 UUID：" + result.getString("lineId"));
        } catch (IllegalArgumentException invalid) {
            reply(sender, "参数无效：" + invalid.getMessage());
            reply(sender, getCommandUsage(sender));
        }
    }

    private static int kind(String value) {
        if ("ae_bus".equals(value)) return SupplySource.AE_BUS;
        if ("io".equals(value)) return SupplySource.IO;
        if ("ae_machine".equals(value)) return SupplySource.AE_MACHINE;
        throw new IllegalArgumentException("设备类型应为 ae_bus、io 或 ae_machine");
    }

    private static void require(String[] args, int count) {
        if (args.length < count) throw new IllegalArgumentException("参数不足");
    }

    private static int number(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }

    private static String join(String[] args, int start) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) result.append(' ');
            result.append(args[i]);
        }
        return result.toString();
    }

    private static void list(ICommandSender sender, NBTTagCompound snapshot) {
        reply(sender, "产线 " + (snapshot.getInteger("linePage") + 1) + "/" + snapshot.getInteger("linePages"));
        NBTTagList list = snapshot.getTagList("lines", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound line = list.getCompoundTagAt(i);
            reply(
                sender,
                line.getString("name") + " [" + line.getString("id") + "] " + line.getInteger("sourceCount") + " 个设备");
        }
        if (list.tagCount() == 0) reply(sender, "尚未创建产线；管理员可使用 /futalines create <名称>");
    }

    private static void show(ICommandSender sender, NBTTagCompound snapshot, boolean device) {
        if (!snapshot.getBoolean("lineFound") || !snapshot.getBoolean("sourceFound")) {
            reply(sender, "未找到所选产线或设备");
            return;
        }
        reply(
            sender,
            "设备领用/回库，" + snapshot.getInteger("minutes")
                + " 个分钟桶；实际观察 "
                + String.format(Locale.ROOT, "%.1f", snapshot.getLong("observedMillis") / 1000d)
                + " 秒。不是机器实际消耗。"
                + (snapshot.getBoolean("enabled") ? "" : " 当前统计已关闭。"));
        NBTTagCompound totals = snapshot.getCompoundTag("totals");
        if (!snapshot.getBoolean("hasRate")) reply(sender, "当前分钟桶观察时间不足，速率尚未就绪。");
        reply(sender, rates(totals));
        if (device) {
            NBTTagList resources = snapshot.getTagList("resources", 10);
            reply(
                sender,
                "原料页 " + (snapshot.getInteger("resourcePage") + 1) + "/" + snapshot.getInteger("resourcePages"));
            for (int i = 0; i < resources.tagCount(); i++) {
                NBTTagCompound row = resources.getCompoundTagAt(i);
                Object key = SupplyTracker.readKey(row);
                String name = key instanceof com.futa_gtnh.shared.ItemKey
                    ? ((com.futa_gtnh.shared.ItemKey) key).prototype()
                        .getDisplayName()
                    : key instanceof com.futa_gtnh.shared.FluidKey ? ((com.futa_gtnh.shared.FluidKey) key).prototype()
                        .getLocalizedName() : "未知原料";
                String unit = "mB".equals(row.getString("unit")) ? "mB" : "件";
                reply(
                    sender,
                    name + "：领用 "
                        + rate(row, "outPerMinute")
                        + "，回库 "
                        + rate(row, "inPerMinute")
                        + " "
                        + unit
                        + "/min");
            }
        } else {
            NBTTagList sources = snapshot.getTagList("sources", 10);
            reply(sender, "设备页 " + (snapshot.getInteger("sourcePage") + 1) + "/" + snapshot.getInteger("sourcePages"));
            for (int i = 0; i < sources.tagCount(); i++) {
                NBTTagCompound row = sources.getCompoundTagAt(i);
                reply(sender, row.getString("id") + " " + row.getString("name") + "：" + rates(row));
            }
        }
        if (snapshot.getLong("dropped") > 0) reply(sender, "统计容量曾达到上限，当前数据可能不完整。");
    }

    private static String rates(NBTTagCompound row) {
        return "物品领用 " + rate(row, "itemOutPerMinute")
            + " / 回库 "
            + rate(row, "itemInPerMinute")
            + " 件/min；流体领用 "
            + rate(row, "fluidOutPerMinute")
            + " / 回库 "
            + rate(row, "fluidInPerMinute")
            + " mB/min";
    }

    private static String rate(NBTTagCompound row, String key) {
        return String.format(Locale.ROOT, "%.2f", row.getDouble(key));
    }

    private static void reply(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1
            ? getListOfStringsMatchingLastWord(
                args,
                "list",
                "create",
                "rename",
                "delete",
                "bind",
                "bindpos",
                "unbind",
                "query",
                "source")
            : null;
    }
}
