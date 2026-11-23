package com.futa_gtnh.spacecenter;

import java.util.List;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;

/**
 * 深空采矿指挥中心的 MUI2 界面：套 GT 多方块标准框架（终端区 + 控制器槽 + 电源开关），
 * 终端区里放三页内容：
 *
 * <ol>
 * <li><b>星球</b>：星球列表（图标+名称+状态），点一行进入配置页；</li>
 * <li><b>配置</b>：选中星球的 8 行计数器（矿机1-4/钻机1-4）+ 油田选择 + 矿脉预览；</li>
 * <li><b>运行</b>：输入需求提示、立即输出与矿脉重置按钮、各虚拟机运行摘要。</li>
 * </ol>
 *
 * <p>
 * 遵守本仓库 MUI 审计铁律：所有会交互的 sync 值先 {@code syncValue} 注册；服务端 getter
 * 只回原始数据（\u001F/\u001E 分隔的编码串），本地化全部在客户端 {@code IKey.dynamic} 里做。
 * 每次打开 GUI 两侧各建一个实例，选中态在服务端实例里（不持久化）。
 */
public class SpaceCenterGui extends MTEMultiBlockBaseGui<MTESpaceMiningCenter> {

    private static final char F = '\u001F'; // 字段分隔
    private static final char R = '\u001E'; // 行分隔

    private static final String[] MACHINE_TYPE_KEYS = { "futa_gtnh.space_center.gui.miner",
        "futa_gtnh.space_center.gui.driller" };

    /** GUI 选中态（服务端实例内）。 */
    private int selectedPlanet = -1;
    private int mainPage = 0;
    private StringSyncValue planetsValue;
    private StringSyncValue configValue;

    public SpaceCenterGui(MTESpaceMiningCenter mte) {
        super(mte);
    }

    // ------------------------------------------------------------------
    // 尺寸：加宽加高一点，给星球列表留空间
    // ------------------------------------------------------------------

    @Override
    protected int getBasePanelWidth() {
        return 235;
    }

    @Override
    protected int getBasePanelHeight() {
        return 181 + 38;
    }

    @Override
    protected int getTerminalRowHeight() {
        return 112;
    }

    // ------------------------------------------------------------------
    // 终端区 = 状态行 + 页签 + 三页内容
    // ------------------------------------------------------------------

    @Override
    public ListWidget<com.cleanroommc.modularui.api.widget.IWidget, ?> createTerminalTextWidget(
        PanelSyncManager syncManager, ModularPanel parent) {
        planetsValue = new StringSyncValue(this::encodePlanets);
        syncManager.syncValue("scPlanets", planetsValue);
        configValue = new StringSyncValue(this::encodeConfig);
        syncManager.syncValue("scConfig", configValue);
        StringSyncValue status = new StringSyncValue(this::encodeStatus);
        syncManager.syncValue("scStatus", status);

        IntSyncValue page = new IntSyncValue(() -> mainPage, p -> mainPage = p).allowC2S();
        syncManager.syncValue("scPage", page);

        PagedWidget<?> pages = new PagedWidget<>().size(222, 82);
        pages.addPage(buildPlanetPage(syncManager, pages, page));
        pages.addPage(buildConfigPage(syncManager, pages));
        pages.addPage(buildRunPage(syncManager, pages));

        ListWidget<com.cleanroommc.modularui.api.widget.IWidget, ?> list = new ListWidget<>().fullWidth()
            .crossAxisAlignment(com.cleanroommc.modularui.utils.Alignment.CrossAxis.START);
        list.child(
            IKey.dynamic(() -> formatStatus(status.getStringValue()))
                .asWidget()
                .scale(0.8f)
                .marginBottom(2));
        list.child(tabRow(pages, page));
        list.child(pages);
        return list;
    }

    private Flow tabRow(PagedWidget<?> pages, IntSyncValue page) {
        String[] keys = { "futa_gtnh.space_center.gui.tab.planets", "futa_gtnh.space_center.gui.tab.config",
            "futa_gtnh.space_center.gui.tab.run" };
        Flow row = Flow.row()
            .height(14)
            .marginBottom(2);
        for (int i = 0; i < keys.length; i++) {
            int idx = i;
            row.child(
                new ButtonWidget<>().size(72, 14)
                    .overlay(IKey.lang(keys[i]))
                    .onMousePressed(button -> {
                        page.setIntValue(idx);
                        pages.setPage(idx);
                        return true;
                    }));
        }
        return row;
    }

    /** 页 0：星球列表。 */
    private ParentWidget<?> buildPlanetPage(PanelSyncManager sync, PagedWidget<?> pages, IntSyncValue mainPage) {
        ParentWidget<?> page = new ParentWidget<>().size(222, 82);
        IntSyncValue selected = new IntSyncValue(
            () -> selectedPlanet,
            idx -> { if (idx >= 0 && idx < planets().size()) selectedPlanet = idx; }).allowC2S();
        sync.syncValue("scSelected", selected);

        int rowsPerPage = 4;
        int total = planets().size();
        int pageCount = Math.max(1, (total + rowsPerPage - 1) / rowsPerPage);
        PagedWidget<?> rows = new PagedWidget<>().pos(0, 0)
            .size(222, 82);
        for (int p = 0; p < pageCount; p++) {
            ParentWidget<?> rowPage = new ParentWidget<>().size(222, 82);
            for (int i = 0; i < rowsPerPage; i++) {
                int index = p * rowsPerPage + i;
                if (index >= total) break;
                int y = i * 16;
                rowPage.child(new PlanetIconWidget(planets().get(index).dimName).pos(2, y));
                int idx = index;
                rowPage.child(
                    new ButtonWidget<>().size(198, 14)
                        .pos(22, y)
                        .overlay(IKey.dynamic(() -> formatPlanetRow(planetsValue.getStringValue(), idx)))
                        .onMousePressed(button -> {
                            selected.setIntValue(idx);
                            pages.setPage(1);
                            mainPage.setIntValue(1);
                            return true;
                        }));
            }
            rows.addPage(rowPage);
        }
        page.child(rows);
        page.child(
            new ButtonWidget<>().size(14, 12)
                .pos(2, 82 - 12)
                .overlay(IKey.str("<"))
                .onMousePressed(button -> {
                    rows.setPage(
                        Math.floorMod(
                            rows.getCurrentPageIndex() - 1,
                            Math.max(
                                1,
                                rows.getPages()
                                    .size())));
                    return true;
                }));
        page.child(
            new ButtonWidget<>().size(14, 12)
                .pos(206, 82 - 12)
                .overlay(IKey.str(">"))
                .onMousePressed(button -> {
                    rows.setPage(
                        (rows.getCurrentPageIndex() + 1) % Math.max(
                            1,
                            rows.getPages()
                                .size()));
                    return true;
                }));
        return page;
    }

    /** 页 1：选中星球配置。 */
    private ParentWidget<?> buildConfigPage(PanelSyncManager sync, PagedWidget<?> pages) {
        ParentWidget<?> page = new ParentWidget<>().size(222, 82);
        page.child(
            IKey.dynamic(() -> formatSelectedHeader(configValue.getStringValue()))
                .asWidget()
                .scale(0.8f)
                .pos(4, 0)
                .tooltipBuilder(t -> buildVeinTooltip(t, configValue.getStringValue())));

        // 行距 8px（0.8 倍字号约 7px），8 行占 10..73，油田行放 74，不再重叠
        for (int i = 0; i < 8; i++) {
            int row = i;
            boolean isMiner = i < 4;
            int tier = (i % 4) + 1;
            int y = 10 + i * 8;
            page.child(
                IKey.dynamic(
                    () -> StatCollector.translateToLocal(MACHINE_TYPE_KEYS[isMiner ? 0 : 1]) + " "
                        + tier
                        + " : "
                        + configCount(configValue.getStringValue(), row))
                    .asWidget()
                    .scale(0.8f)
                    .pos(8, y));
            IntSyncValue counter = new IntSyncValue(() -> configCountRaw(row), value -> applyCount(row, value))
                .allowC2S();
            sync.syncValue("scCnt" + row, counter);
            page.child(
                new ButtonWidget<>().size(11, 8)
                    .pos(118, y)
                    .overlay(IKey.str("-"))
                    .onMousePressed(button -> {
                        counter.setIntValue(Math.max(0, configCountRaw(row) - 1));
                        return true;
                    }));
            page.child(
                new ButtonWidget<>().size(11, 8)
                    .pos(156, y)
                    .overlay(IKey.str("+"))
                    .onMousePressed(button -> {
                        counter.setIntValue(configCountRaw(row) + 1);
                        return true;
                    }));
        }

        IntSyncValue fluid = new IntSyncValue(this::fluidSelRaw, this::applyFluidSel).allowC2S();
        sync.syncValue("scFluid", fluid);
        page.child(
            IKey.lang("futa_gtnh.space_center.gui.field")
                .asWidget()
                .scale(0.8f)
                .pos(8, 74));
        page.child(
            IKey.dynamic(() -> formatFluidName(planetsValue.getStringValue(), configValue.getStringValue()))
                .asWidget()
                .scale(0.8f)
                .pos(46, 74));
        page.child(
            new ButtonWidget<>().size(11, 8)
                .pos(2, 74)
                .overlay(IKey.str("<"))
                .onMousePressed(button -> {
                    fluid.setIntValue(Math.max(0, fluidSelRaw() - 1));
                    return true;
                }));
        page.child(
            new ButtonWidget<>().size(11, 8)
                .pos(206, 74)
                .overlay(IKey.str(">"))
                .onMousePressed(button -> {
                    fluid.setIntValue(fluidSelRaw() + 1);
                    return true;
                }));
        return page;
    }

    /** 页 2：运行与 IO。 */
    private ParentWidget<?> buildRunPage(PanelSyncManager sync, PagedWidget<?> pages) {
        ParentWidget<?> page = new ParentWidget<>().size(222, 82);
        StringSyncValue machines = new StringSyncValue(this::encodeMachines);
        sync.syncValue("scMachines", machines);
        IntSyncValue action = new IntSyncValue(() -> 0, this::doAction).allowC2S();
        sync.syncValue("scAction", action);

        page.child(
            IKey.lang("futa_gtnh.space_center.gui.input_hint")
                .asWidget()
                .scale(0.7f)
                .pos(4, 0));
        page.child(
            new ButtonWidget<>().size(64, 14)
                .pos(4, 14)
                .overlay(IKey.lang("futa_gtnh.space_center.gui.flush"))
                .onMousePressed(button -> {
                    action.setIntValue(1);
                    return true;
                }));
        page.child(
            new ButtonWidget<>().size(64, 14)
                .pos(74, 14)
                .overlay(IKey.lang("futa_gtnh.space_center.gui.reset_vein"))
                .onMousePressed(button -> {
                    action.setIntValue(2);
                    return true;
                }));
        page.child(
            IKey.dynamic(() -> formatVeinAndMachines(machines.getStringValue()))
                .asWidget()
                .scale(0.7f)
                .pos(4, 34));
        return page;
    }

    private void doAction(int value) {
        if (value == 1) {
            multiblock.flushBuffers();
        } else if (value == 2) {
            PlanetRegistry.PlanetInfo info = selected();
            if (info == null) return;
            for (SpaceCenterMachine machine : multiblock.machines()) {
                if (!machine.planet.equals(info.dimName)) continue;
                if (machine instanceof SpaceCenterMiner miner) {
                    miner.era++;
                    miner.chunkIndex = 0;
                    miner.progress = 0;
                }
                machine.pauseReason = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 服务端数据编码（原始 token，客户端拼本地化）
    // ------------------------------------------------------------------

    private List<PlanetRegistry.PlanetInfo> planets() {
        return PlanetRegistry.planets();
    }

    private PlanetRegistry.PlanetInfo selected() {
        List<PlanetRegistry.PlanetInfo> list = planets();
        if (selectedPlanet < 0 || selectedPlanet >= list.size()) return null;
        return list.get(selectedPlanet);
    }

    /** name F tier F flags F fluidRegistry列表(逗号分隔)。flags：1=已勘查 2=有矿脉 4=有油田 8=已配置 16=火箭等级够。 */
    private String encodePlanets() {
        StringBuilder sb = new StringBuilder();
        int rocketTier = multiblock.rocketTier();
        for (PlanetRegistry.PlanetInfo info : planets()) {
            if (sb.length() > 0) sb.append(R);
            int flags = 0;
            if (multiblock.planetSeed(info.dimName) != null) flags |= 1;
            if (info.hasVeins()) flags |= 2;
            if (info.hasFluids()) flags |= 4;
            MTESpaceMiningCenter.PlanetConfig config = multiblock.configFor(info.dimName, false);
            if (config != null && config.total() > 0) flags |= 8;
            if (rocketTier > 0 && rocketTier >= info.tierRequirement) flags |= 16;
            StringBuilder fluids = new StringBuilder();
            for (gregtech.api.objects.GTUOFluid fluid : info.fluids) {
                if (fluids.length() > 0) fluids.append(',');
                fluids.append(fluid.Registry);
            }
            sb.append(info.dimName)
                .append(F)
                .append(info.tierRequirement)
                .append(F)
                .append(flags)
                .append(F)
                .append(fluids);
        }
        return sb.toString();
    }

    /** planetName F m0..m3 F d0..d3 F fluidIdx F veins（primaryInternalName:weight 逗号分隔，权重预览用）。 */
    private String encodeConfig() {
        PlanetRegistry.PlanetInfo info = selected();
        if (info == null) return "";
        MTESpaceMiningCenter.PlanetConfig config = multiblock.configFor(info.dimName, false);
        StringBuilder sb = new StringBuilder(info.dimName).append(F);
        for (int i = 0; i < 4; i++) sb.append(config == null ? 0 : config.miners[i])
            .append(i < 3 ? ',' : ' ');
        sb.append(F);
        for (int i = 0; i < 4; i++) sb.append(config == null ? 0 : config.drillers[i])
            .append(i < 3 ? ',' : ' ');
        sb.append(F)
            .append(fluidIndexOf(info, config == null ? null : config.fluidKey))
            .append(F);
        StringBuilder veins = new StringBuilder();
        for (gregtech.common.WorldgenGTOreLayer vein : info.veins) {
            if (veins.length() > 0) veins.append(',');
            // primary 的 internalName 客户端可用 IOreMaterial.findMaterial 还原并按客户端语言本地化
            veins.append(vein.mPrimary == null ? "?" : vein.mPrimary.getInternalName())
                .append(':')
                .append(vein.mWeight);
        }
        sb.append(veins);
        return sb.toString();
    }

    private int fluidIndexOf(PlanetRegistry.PlanetInfo info, String fluidKey) {
        if (fluidKey == null) return 0;
        for (int i = 0; i < info.fluids.size(); i++) {
            if (info.fluids.get(i).Registry.equals(fluidKey)) return i;
        }
        return 0;
    }

    /** euT F active F total F capacity F height F rocketTier F lubePerSec。 */
    private String encodeStatus() {
        long eu = 0;
        int active = 0;
        for (SpaceCenterMachine machine : multiblock.machines()) {
            eu += machine.euPerTick();
            if (machine.pauseReason == null) active++;
        }
        return eu + ""
            + F
            + active
            + ""
            + F
            + multiblock.machines()
                .size()
            + ""
            + F
            + multiblock.capacity()
            + ""
            + F
            + multiblock.structureHeight()
            + ""
            + F
            + multiblock.rocketTier()
            + ""
            + F
            + (multiblock.machines()
                .size() * com.futa_gtnh.Config.spaceCenterLubricantPerMachine);
    }

    /** 每行一台虚拟机：type,tier,planetName,state,progress,cycle。 */
    private String encodeMachines() {
        StringBuilder sb = new StringBuilder();
        for (SpaceCenterMachine machine : multiblock.machines()) {
            if (sb.length() > 0) sb.append(R);
            String state;
            if (machine.pauseReason != null) state = machine.pauseReason;
            else if (isDescending(machine)) state = "descend";
            else state = "work";
            sb.append(machine.type() == SpaceCenterMachine.Type.MINER ? 0 : 1)
                .append(',')
                .append(machine.tier)
                .append(',')
                .append(machine.planet)
                .append(',')
                .append(state)
                .append(',')
                .append(machine.progress)
                .append(',')
                .append(machine.cycleTicks());
        }
        return sb.toString();
    }

    private static boolean isDescending(SpaceCenterMachine machine) {
        if (machine instanceof SpaceCenterMiner miner) return miner.descendRemaining > 0;
        if (machine instanceof SpaceCenterDriller driller) return !driller.atBottom();
        return false;
    }

    // ------------------------------------------------------------------
    // 配置写入（服务端，带容量钳制）
    // ------------------------------------------------------------------

    private int configCountRaw(int row) {
        PlanetRegistry.PlanetInfo info = selected();
        if (info == null) return 0;
        MTESpaceMiningCenter.PlanetConfig config = multiblock.configFor(info.dimName, false);
        if (config == null) return 0;
        int[] array = row < 4 ? config.miners : config.drillers;
        return array[row % 4];
    }

    private void applyCount(int row, int value) {
        PlanetRegistry.PlanetInfo info = selected();
        if (info == null) return;
        MTESpaceMiningCenter.PlanetConfig config = multiblock.configFor(info.dimName, true);
        int[] array = row < 4 ? config.miners : config.drillers;
        int others = config.total() - array[row % 4];
        int clamped = Math.max(0, Math.min(value, multiblock.capacity() - others));
        array[row % 4] = clamped;
        multiblock.reconcileMachines();
    }

    private int fluidSelRaw() {
        PlanetRegistry.PlanetInfo info = selected();
        if (info == null) return 0;
        MTESpaceMiningCenter.PlanetConfig config = multiblock.configFor(info.dimName, false);
        return fluidIndexOf(info, config == null ? null : config.fluidKey);
    }

    private void applyFluidSel(int value) {
        PlanetRegistry.PlanetInfo info = selected();
        if (info == null || info.fluids.isEmpty()) return;
        int clamped = Math.max(0, Math.min(value, info.fluids.size() - 1));
        MTESpaceMiningCenter.PlanetConfig config = multiblock.configFor(info.dimName, true);
        config.fluidKey = info.fluids.get(clamped).Registry;
        // 换油田后该星球钻机产量重算
        for (SpaceCenterMachine machine : multiblock.machines()) {
            if (machine instanceof SpaceCenterDriller driller && machine.planet.equals(info.dimName)) {
                driller.resetRate();
            }
        }
    }

    // ------------------------------------------------------------------
    // 客户端格式化（本地化都在这里做）
    // ------------------------------------------------------------------

    private static String formatStatus(String raw) {
        if (raw.isEmpty()) return "";
        String[] p = raw.split(String.valueOf(F));
        if (p.length < 7) return "";
        int rocket = parseInt(p[5]);
        return StatCollector.translateToLocal("futa_gtnh.space_center.gui.status")
            .replace("%r", rocket > 0 ? String.valueOf(rocket) : "-")
            .replace("%e", p[0])
            .replace("%a", p[1])
            .replace("%t", p[2])
            .replace("%c", p[3])
            .replace("%h", p[4])
            .replace("%l", p[6]);
    }

    private static String formatPlanetRow(String raw, int index) {
        String[] rows = raw.split(String.valueOf(R));
        if (index >= rows.length) return "";
        String[] f = rows[index].split(String.valueOf(F));
        if (f.length < 4) return "";
        String name = planetDisplayName(f[0]);
        int flags = parseInt(f[2]);
        String state;
        if ((flags & 16) == 0) {
            state = "\u00A7c" + StatCollector.translateToLocal("futa_gtnh.space_center.gui.locked")
                .replace("%t", f[1]);
        } else if ((flags & 1) == 0) {
            state = "\u00A78" + StatCollector.translateToLocal("futa_gtnh.space_center.gui.unsurveyed");
        } else if ((flags & 8) != 0) {
            state = "\u00A7a" + StatCollector.translateToLocal("futa_gtnh.space_center.gui.active");
        } else {
            state = "\u00A77" + StatCollector.translateToLocal("futa_gtnh.space_center.gui.idle");
        }
        return name + "  " + state;
    }

    private static String formatSelectedHeader(String configRaw) {
        String[] p = configRaw.split(String.valueOf(F));
        String name = p.length > 0 && !p[0].isEmpty() ? planetDisplayName(p[0]) : "-";
        String veinCount = p.length > 4 && !p[4].isEmpty() ? String.valueOf(p[4].split(",").length) : "0";
        return StatCollector.translateToLocal("futa_gtnh.space_center.gui.selected")
            .replace("%p", name)
            .replace("%v", veinCount);
    }

    /** 矿脉权重预览 tooltip：每行「本地化名: 权重」（客户端按本地语言解析材料名）。 */
    private static void buildVeinTooltip(com.cleanroommc.modularui.screen.RichTooltip tooltip, String configRaw) {
        String[] p = configRaw.split(String.valueOf(F));
        if (p.length < 5 || p[4].isEmpty()) return;
        tooltip.addLine(IKey.lang("futa_gtnh.space_center.gui.veins"));
        int lines = 0;
        for (String entry : p[4].split(",")) {
            if (lines++ >= 12) {
                tooltip.addLine(IKey.lang("futa_gtnh.space_center.gui.more"));
                break;
            }
            int sep = entry.lastIndexOf(':');
            if (sep <= 0) continue;
            String internalName = entry.substring(0, sep);
            String weight = entry.substring(sep + 1);
            gregtech.api.interfaces.IOreMaterial material = gregtech.api.interfaces.IOreMaterial
                .findMaterial(internalName);
            String display = material != null ? material.getLocalizedName() : internalName;
            tooltip.addLine(IKey.str(display + ": " + weight));
        }
    }

    private static String configCount(String raw, int row) {
        String[] p = raw.split(String.valueOf(F));
        if (p.length < 3) return "0";
        String[] counts = (row < 4 ? p[1] : p[2]).split(",");
        int idx = row % 4;
        return idx < counts.length ? counts[idx].trim() : "0";
    }

    private static String formatFluidName(String planetsRaw, String configRaw) {
        String[] cfg = configRaw.split(String.valueOf(F));
        if (cfg.length < 4) return "-";
        String planetName = cfg[0];
        int sel = parseInt(cfg[3]);
        for (String row : planetsRaw.split(String.valueOf(R))) {
            String[] f = row.split(String.valueOf(F));
            if (f.length < 4 || !f[0].equals(planetName)) continue;
            String[] fluids = f[3].isEmpty() ? new String[0] : f[3].split(",");
            if (fluids.length == 0) return StatCollector.translateToLocal("futa_gtnh.space_center.gui.no_field");
            int idx = Math.min(Math.max(0, sel), fluids.length - 1);
            net.minecraftforge.fluids.Fluid fluid = net.minecraftforge.fluids.FluidRegistry.getFluid(fluids[idx]);
            String local = fluid != null ? fluid.getLocalizedName(null) : fluids[idx];
            return (fluids.length > 1 ? "(" + (idx + 1) + "/" + fluids.length + ") " : "") + local;
        }
        return "-";
    }

    private static String formatVeinAndMachines(String machinesRaw) {
        if (machinesRaw.isEmpty()) return StatCollector.translateToLocal("futa_gtnh.space_center.gui.no_machine");
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String row : machinesRaw.split(String.valueOf(R))) {
            String[] f = row.split(",");
            if (f.length < 6) continue;
            if (lines++ >= 7) {
                sb.append('\n')
                    .append(StatCollector.translateToLocal("futa_gtnh.space_center.gui.more"));
                break;
            }
            if (sb.length() > 0) sb.append('\n');
            String state = "descend".equals(f[3]) ? StatCollector.translateToLocal("futa_gtnh.space_center.gui.descend")
                : StatCollector.translateToLocal("futa_gtnh.space_center.gui.work");
            sb.append(planetDisplayName(f[2]))
                .append(' ')
                .append(StatCollector.translateToLocal(MACHINE_TYPE_KEYS[parseInt(f[0])]))
                .append(f[1])
                .append(" - ")
                .append(state)
                .append(" (")
                .append(f[4])
                .append('/')
                .append(f[5])
                .append("t)");
        }
        return sb.toString();
    }

    /** 星球显示名：优先本地化 key，回退首字母大写原名。 */
    static String planetDisplayName(String name) {
        String key = "futa_gtnh.space_center.planet." + name;
        String localized = StatCollector.translateToLocal(key);
        if (localized.equals(key)) {
            return name.substring(0, 1)
                .toUpperCase() + name.substring(1);
        }
        return localized;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
