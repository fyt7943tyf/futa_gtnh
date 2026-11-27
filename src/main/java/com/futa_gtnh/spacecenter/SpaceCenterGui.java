package com.futa_gtnh.spacecenter;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

import gregtech.api.interfaces.IOreMaterial;
import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;

/**
 * 深空采矿指挥中心的 MUI2 界面：套 GT 多方块标准框架（终端区 + 控制器槽 + 电源开关），
 * 终端区里放两行状态 + 三个页签 + 三页内容：
 *
 * <ol>
 * <li><b>星球</b>：星球列表（图标+名称+状态），点一行进入配置页；</li>
 * <li><b>配置</b>：选中星球的 8 行计数器（矿机1-4/钻机1-4）+ 右侧矿脉权重分布 + 油田选择；</li>
 * <li><b>运行</b>：输入需求提示、立即输出与矿脉重置按钮、按「星球/类型/等级」聚合的运行摘要。</li>
 * </ol>
 *
 * <p>
 * <b>两侧各建一棵控件树，MUI2 只按名字同步 sync 值，不同步控件本身。</b>所以这棵树不能依赖
 * 任何两端可能不一样的东西——尤其是客户端本地的星球表（专用服务器的客户端，矿脉/油田配置
 * 未必与服务端一致）。星球页因此是固定 5 行的「行池」，每行有没有星球、显示什么，全部由
 * {@code scPlanets} 同步串决定；客户端从头到尾不调 {@code PlanetRegistry.planets()}。
 *
 * <p>
 * 遵守本仓库 MUI 审计铁律：所有会交互的 sync 值先 {@code syncValue} 注册；服务端 getter
 * 只回原始数据（\u001F/\u001E 分隔的编码串），本地化全部在客户端 {@code IKey.dynamic} 里做。
 * 每次打开 GUI 两侧各建一个实例，选中态在服务端实例里（不持久化）。
 */
public class SpaceCenterGui extends MTEMultiBlockBaseGui<MTESpaceMiningCenter> {

    private static final char F = '\u001F'; // 字段分隔
    private static final char R = '\u001E'; // 行分隔

    // ---- 几何 ----
    // 面板宽 235；主列左右各 4 的内边距，终端行再留 4 的左内边距 ⇒ 终端内容区 223 × 120。
    // 面板高 = 4 + 终端行高 + 间隙行 22 + 背包行 76 + 4 = 234，终端行高取 128。
    private static final int PAGE_W = 219;
    private static final int PAGE_H = 82;
    private static final int ROWS_PER_PAGE = 5;
    private static final int ROW_H = 14;
    private static final int COUNTER_ROWS = 8;
    private static final int COUNTER_Y0 = 10;
    private static final int COUNTER_PITCH = 8;
    private static final int MAX_VEIN_LINES = 8;
    private static final int MAX_SUMMARY_LINES = 6;
    /** 配置页右侧矿脉列的左缘（左侧留给 8 行计数器）。 */
    private static final int VEIN_X = 106;

    private static final String[] MACHINE_TYPE_KEYS = { "futa_gtnh.space_center.gui.miner",
        "futa_gtnh.space_center.gui.driller" };

    /** GUI 选中态：星球在服务端星球表里的下标（服务端实例内，不持久化）。 */
    private int selectedPlanet = -1;
    /** 星球列表当前页（客户端实例内）。 */
    private int planetPage;

    private StringSyncValue planetsValue;
    private StringSyncValue configValue;
    private IntSyncValue selectedValue;

    public SpaceCenterGui(MTESpaceMiningCenter mte) {
        super(mte);
    }

    // ------------------------------------------------------------------
    // 尺寸
    // ------------------------------------------------------------------

    @Override
    protected int getBasePanelWidth() {
        return 235;
    }

    /** 基类的 190 是跟着默认面板宽 198 走的；只加宽面板不改这里，内容会画到终端外面。 */
    @Override
    protected int getTerminalRowWidth() {
        return getBasePanelWidth() - 8;
    }

    @Override
    protected int getTerminalRowHeight() {
        return 128;
    }

    @Override
    protected int getBasePanelHeight() {
        return 234;
    }

    // 批量模式 / 输入分离 / 锁定配方 / 排出多余 这些通用按钮对本机器没有意义：不可超频，
    // 也没有「单条配方」可锁，留着只会让人以为按了有用。
    @Override
    protected boolean shouldDisplayVoidExcess() {
        return false;
    }

    @Override
    protected boolean shouldDisplayInputSeparation() {
        return false;
    }

    @Override
    protected boolean shouldDisplayBatchMode() {
        return false;
    }

    @Override
    protected boolean shouldDisplayRecipeLock() {
        return false;
    }

    // ------------------------------------------------------------------
    // 终端区 = 两行状态 + 页签 + 三页内容
    // ------------------------------------------------------------------

    @Override
    public ListWidget<IWidget, ?> createTerminalTextWidget(PanelSyncManager syncManager, ModularPanel parent) {
        planetsValue = new StringSyncValue(this::encodePlanets);
        syncManager.syncValue("scPlanets", planetsValue);
        configValue = new StringSyncValue(this::encodeConfig);
        syncManager.syncValue("scConfig", configValue);
        StringSyncValue status = new StringSyncValue(this::encodeStatus);
        syncManager.syncValue("scStatus", status);
        selectedValue = new IntSyncValue(
            () -> selectedPlanet,
            idx -> { if (idx >= 0 && idx < planets().size()) selectedPlanet = idx; }).allowC2S();
        syncManager.syncValue("scSelected", selectedValue);

        PagedWidget<?> pages = new PagedWidget<>().size(PAGE_W, PAGE_H);
        pages.addPage(buildPlanetPage(pages));
        pages.addPage(buildConfigPage(syncManager));
        pages.addPage(buildRunPage(syncManager));

        ListWidget<IWidget, ?> list = new ListWidget<>().fullWidth()
            .crossAxisAlignment(Alignment.CrossAxis.START);
        // 高度预算 120：9 + (9+2) + (14+2) + 82 = 118
        list.child(text(() -> formatStatus(0, status.getStringValue()), 0.8f));
        list.child(text(() -> formatStatus(1, status.getStringValue()), 0.8f).marginBottom(2));
        list.child(tabRow(pages));
        list.child(pages);
        return list;
    }

    /** 绝对定位的动态文本控件（本地化在 lambda 里、客户端求值）。 */
    private static TextWidget<?> text(Supplier<String> supplier, float scale, int x, int y) {
        TextWidget<?> widget = IKey.dynamic(supplier)
            .asWidget()
            .scale(scale);
        widget.pos(x, y);
        return widget;
    }

    private static TextWidget<?> text(Supplier<String> supplier, float scale) {
        return IKey.dynamic(supplier)
            .asWidget()
            .scale(scale);
    }

    private static ButtonWidget<?> button(int width, int height, int x, int y, IDrawable label,
        com.cleanroommc.modularui.api.widget.IGuiAction.MousePressed onClick) {
        return new ButtonWidget<>().size(width, height)
            .pos(x, y)
            .overlay(label)
            .onMousePressed(onClick);
    }

    private Flow tabRow(PagedWidget<?> pages) {
        String[] keys = { "futa_gtnh.space_center.gui.tab.planets", "futa_gtnh.space_center.gui.tab.config",
            "futa_gtnh.space_center.gui.tab.run" };
        Flow row = Flow.row()
            .height(14)
            .marginBottom(2);
        for (int i = 0; i < keys.length; i++) {
            int idx = i;
            // 显示哪一页纯粹是客户端的事，服务端不关心，不需要同步值
            row.child(button(72, 14, 0, 0, IKey.lang(keys[i]), b -> {
                pages.setPage(idx);
                return true;
            }));
        }
        return row;
    }

    // ------------------------------------------------------------------
    // 页 0：星球列表（固定 5 行的行池，内容由 scPlanets 决定）
    // ------------------------------------------------------------------

    private ParentWidget<?> buildPlanetPage(PagedWidget<?> pages) {
        ParentWidget<?> page = new ParentWidget<>().size(PAGE_W, PAGE_H);
        for (int r = 0; r < ROWS_PER_PAGE; r++) {
            final int row = r;
            int y = r * ROW_H;
            Supplier<String> name = () -> planetNameAt(absoluteIndex(row));
            page.child(
                new PlanetIconWidget().planetName(name)
                    .size(12, 12)
                    .pos(1, y + 1));
            page.child(
                new PlanetRowWidget(() -> name.get() != null).size(202, 12)
                    .pos(16, y + 1)
                    .overlay(
                        IKey.dynamic(
                            () -> formatPlanetRow(
                                planetsValue.getStringValue(),
                                absoluteIndex(row),
                                absoluteIndex(row) == selectedValue.getIntValue())))
                    .onMousePressed(b -> {
                        int target = absoluteIndex(row);
                        if (planetNameAt(target) == null) return true;
                        selectedValue.setIntValue(target);
                        pages.setPage(1);
                        return true;
                    }));
        }
        int navY = PAGE_H - 12;
        page.child(button(14, 12, 0, navY, IKey.str("<"), b -> {
            planetPage = Math.floorMod(planetPage - 1, planetPageCount());
            return true;
        }));
        page.child(button(14, 12, PAGE_W - 14, navY, IKey.str(">"), b -> {
            planetPage = Math.floorMod(planetPage + 1, planetPageCount());
            return true;
        }));
        page.child(text(() -> formatPageLabel(planetPage), 0.8f, 18, navY + 2));
        return page;
    }

    private int absoluteIndex(int row) {
        return row + planetPage * ROWS_PER_PAGE;
    }

    /** 星球总页数（至少 1），从同步串里数出来。 */
    private int planetPageCount() {
        String raw = planetsValue == null ? "" : planetsValue.getStringValue();
        int total = raw.isEmpty() ? 0 : raw.split(String.valueOf(R)).length;
        return Math.max(1, (total + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    // ------------------------------------------------------------------
    // 页 1：选中星球配置
    // ------------------------------------------------------------------

    private ParentWidget<?> buildConfigPage(PanelSyncManager sync) {
        ParentWidget<?> page = new ParentWidget<>().size(PAGE_W, PAGE_H);

        TextWidget<?> header = text(() -> formatSelectedHeader(configValue.getStringValue()), 0.8f, 4, 0);
        header.tooltipBuilder(t -> buildVeinTooltip(t, configValue.getStringValue()));
        page.child(header);
        page.child(text(() -> tr("futa_gtnh.space_center.gui.veins"), 0.8f, VEIN_X, 0));

        for (int i = 0; i < COUNTER_ROWS; i++) {
            int row = i;
            boolean isMiner = i < 4;
            int tier = (i % 4) + 1;
            int y = COUNTER_Y0 + i * COUNTER_PITCH;

            TextWidget<?> label = text(
                () -> tr(MACHINE_TYPE_KEYS[isMiner ? 0 : 1]) + " "
                    + tier
                    + " ×"
                    + configCount(configValue.getStringValue(), row),
                0.8f,
                4,
                y);
            label.tooltipBuilder(t -> buildTierTooltip(t, isMiner, tier));
            page.child(label);

            IntSyncValue counter = new IntSyncValue(() -> configCountRaw(row), value -> applyCount(row, value))
                .allowC2S();
            sync.syncValue("scCnt" + row, counter);
            page.child(button(12, 8, 72, y, IKey.str("-"), b -> {
                counter.setIntValue(Math.max(0, configCountRaw(row) - 1));
                return true;
            }));
            page.child(button(12, 8, 86, y, IKey.str("+"), b -> {
                counter.setIntValue(configCountRaw(row) + 1);
                return true;
            }));
        }

        for (int l = 0; l < MAX_VEIN_LINES; l++) {
            final int line = l;
            page.child(
                text(
                    () -> formatVeinLine(configValue.getStringValue(), line),
                    0.8f,
                    VEIN_X,
                    COUNTER_Y0 + l * COUNTER_PITCH));
        }

        IntSyncValue fluid = new IntSyncValue(this::fluidSelRaw, this::applyFluidSel).allowC2S();
        sync.syncValue("scFluid", fluid);
        int fluidY = PAGE_H - 8;
        page.child(button(12, 8, 0, fluidY, IKey.str("<"), b -> {
            fluid.setIntValue(Math.max(0, fluidSelRaw() - 1));
            return true;
        }));
        page.child(text(() -> tr("futa_gtnh.space_center.gui.field"), 0.8f, 16, fluidY));
        page.child(
            text(() -> formatFluidName(planetsValue.getStringValue(), configValue.getStringValue()), 0.8f, 42, fluidY));
        page.child(button(12, 8, PAGE_W - 12, fluidY, IKey.str(">"), b -> {
            fluid.setIntValue(fluidSelRaw() + 1);
            return true;
        }));
        return page;
    }

    // ------------------------------------------------------------------
    // 页 2：运行与 IO
    // ------------------------------------------------------------------

    private ParentWidget<?> buildRunPage(PanelSyncManager sync) {
        ParentWidget<?> page = new ParentWidget<>().size(PAGE_W, PAGE_H);
        StringSyncValue machines = new StringSyncValue(this::encodeMachines);
        sync.syncValue("scMachines", machines);
        IntSyncValue action = new IntSyncValue(() -> 0, this::doAction).allowC2S();
        sync.syncValue("scAction", action);

        page.child(text(() -> tr("futa_gtnh.space_center.gui.input_hint.0"), 0.7f, 4, 0));
        page.child(text(() -> tr("futa_gtnh.space_center.gui.input_hint.1"), 0.7f, 4, 8));

        page.child(button(68, 14, 0, 17, IKey.lang("futa_gtnh.space_center.gui.flush"), b -> {
            action.setIntValue(1);
            return true;
        }));
        // 按钮上直接写明会重置哪颗星球：它作用于「星球页里最后选中的那颗」，不说清楚就是盲操作
        page.child(button(120, 14, 72, 17, IKey.dynamic(this::formatResetLabel), b -> {
            action.setIntValue(2);
            return true;
        }));

        for (int l = 0; l < MAX_SUMMARY_LINES; l++) {
            final int line = l;
            page.child(text(() -> formatMachineLine(machines.getStringValue(), line), 0.7f, 4, 34 + l * 8));
        }
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
                if (machine instanceof SpaceCenterMiner miner) miner.resetVein();
                machine.pauseReason = null;
            }
        }
    }

    private String formatResetLabel() {
        String name = planetNameAt(selectedValue.getIntValue());
        String label = tr("futa_gtnh.space_center.gui.reset_vein");
        return name == null ? label : label + ": " + planetDisplayName(name);
    }

    // ------------------------------------------------------------------
    // 服务端数据编码（原始 token，客户端拼本地化）
    // ------------------------------------------------------------------

    /** 只允许服务端实例调：客户端本地的星球表不可信。 */
    private List<PlanetRegistry.PlanetInfo> planets() {
        return PlanetRegistry.planets();
    }

    private PlanetRegistry.PlanetInfo selected() {
        List<PlanetRegistry.PlanetInfo> list = planets();
        if (selectedPlanet < 0 || selectedPlanet >= list.size()) return null;
        return list.get(selectedPlanet);
    }

    /** name F tier F flags F fluidRegistry列表(逗号分隔)。flags：1=已勘查 2=有矿脉 4=有油田 8=已配置 16=火箭等级够 32=有机器在转。 */
    private String encodePlanets() {
        StringBuilder sb = new StringBuilder();
        int rocketTier = multiblock.rocketTier();
        for (PlanetRegistry.PlanetInfo info : planets()) {
            if (sb.length() > 0) sb.append(R);
            int flags = 0;
            if (multiblock.isSurveyed(info)) flags |= 1;
            if (info.hasVeins()) flags |= 2;
            if (info.hasFluids()) flags |= 4;
            MTESpaceMiningCenter.PlanetConfig config = multiblock.configFor(info.dimName, false);
            if (config != null && config.total() > 0) flags |= 8;
            if (rocketTier > 0 && rocketTier >= info.tierRequirement) flags |= 16;
            if (hasRunningMachine(info.dimName)) flags |= 32;
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

    private boolean hasRunningMachine(String dimName) {
        for (SpaceCenterMachine machine : multiblock.machines()) {
            if (machine.planet.equals(dimName) && machine.pauseReason == null) return true;
        }
        return false;
    }

    /** planetName F m0..m3 F d0..d3 F fluidIdx F veins（primaryInternalName:weight，逗号分隔，按权重降序）。 */
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
        sb.append(encodeVeins(info));
        return sb.toString();
    }

    /** 矿脉按权重降序（同权重按名字），客户端拿去算百分比。 */
    private static String encodeVeins(PlanetRegistry.PlanetInfo info) {
        TreeMap<Integer, List<String>> byWeight = new TreeMap<>((a, b) -> Integer.compare(b, a));
        for (gregtech.common.WorldgenGTOreLayer vein : info.veins) {
            if (vein.mPrimary == null) continue;
            byWeight.computeIfAbsent((int) vein.mWeight, w -> new ArrayList<>())
                .add(vein.mPrimary.getInternalName());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, List<String>> entry : byWeight.entrySet()) {
            List<String> names = entry.getValue();
            names.sort(String::compareTo);
            for (String name : names) {
                if (sb.length() > 0) sb.append(',');
                sb.append(name)
                    .append(':')
                    .append(entry.getKey());
            }
        }
        return sb.toString();
    }

    private int fluidIndexOf(PlanetRegistry.PlanetInfo info, String fluidKey) {
        if (fluidKey == null) return 0;
        for (int i = 0; i < info.fluids.size(); i++) {
            if (info.fluids.get(i).Registry.equals(fluidKey)) return i;
        }
        return 0;
    }

    /** rocketTier F euPerTick F configured F running F capacity F height F lubricantPerSecond。 */
    private String encodeStatus() {
        long eu = 0;
        int running = 0;
        int lubricated = 0;
        int rocketTier = multiblock.rocketTier();
        for (SpaceCenterMachine machine : multiblock.machines()) {
            if (machine.pauseReason == null) running++;
            if (!multiblock.canOperate(PlanetRegistry.byName(machine.planet), rocketTier)) continue;
            eu += machine.euPerTick();
            if (machine.pauseReason == null) lubricated++;
        }
        return rocketTier + ""
            + F
            + eu
            + ""
            + F
            + multiblock.machines()
                .size()
            + ""
            + F
            + running
            + ""
            + F
            + multiblock.capacity()
            + ""
            + F
            + multiblock.structureHeight()
            + ""
            + F
            + lubricated * com.futa_gtnh.Config.spaceCenterLubricantPerMachine;
    }

    /** 按「星球,类型(0矿机/1钻机),等级,状态」聚合成 planet,type,tier,state,count 行。 */
    private String encodeMachines() {
        TreeMap<String, int[]> groups = new TreeMap<>();
        for (SpaceCenterMachine machine : multiblock.machines()) {
            String state;
            if (machine.pauseReason != null) state = machine.pauseReason;
            else if (isDescending(machine)) state = "descend";
            else state = "work";
            String key = machine.planet + ","
                + (machine.type() == SpaceCenterMachine.Type.MINER ? 0 : 1)
                + ","
                + machine.tier
                + ","
                + state;
            int[] count = groups.computeIfAbsent(key, k -> new int[1]);
            count[0]++;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> entry : groups.entrySet()) {
            if (sb.length() > 0) sb.append(R);
            sb.append(entry.getKey())
                .append(',')
                .append(entry.getValue()[0]);
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
        // 客户端发来的是任意 int：钳到 [0, 剩余机位]，既不能塞负数也不能超过机位容量
        array[row % 4] = SpaceCenterMachine.clamp(value, 0, Math.max(0, multiblock.capacity() - others));
        multiblock.reconcileMachines();
    }

    private int fluidSelRaw() {
        PlanetRegistry.PlanetInfo info = selected();
        if (info == null) return 0;
        return fluidIndexOf(info, multiblock.selectedFluidKey(info.dimName));
    }

    private void applyFluidSel(int value) {
        PlanetRegistry.PlanetInfo info = selected();
        if (info == null || info.fluids.isEmpty()) return;
        int clamped = SpaceCenterMachine.clamp(value, 0, info.fluids.size() - 1);
        multiblock.configFor(info.dimName, true).fluidKey = info.fluids.get(clamped).Registry;
        // 换油田后该星球钻机的固定产量要重算
        for (SpaceCenterMachine machine : multiblock.machines()) {
            if (machine instanceof SpaceCenterDriller driller && machine.planet.equals(info.dimName)) {
                driller.resetRate();
                machine.pauseReason = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 客户端格式化（本地化都在这里做；只读同步串，不碰星球表）
    // ------------------------------------------------------------------

    /**
     * 取词条并填参数。
     *
     * <p>
     * 占位符和传参对不上（改文案漏了一个、或者换语言后数量不一致）时 {@link String#format} 会抛
     * {@link IllegalFormatException}——那是每一次重绘都会走到的地方，等于界面一打开就崩。
     * 这里改成把原文和参数原样显示出来：难看，但看得见是哪条词条出了问题。
     */
    private static String tr(String key, Object... args) {
        String pattern = StatCollector.translateToLocal(key);
        if (args.length == 0) return pattern;
        try {
            return String.format(pattern, args);
        } catch (IllegalFormatException e) {
            return pattern + " " + java.util.Arrays.toString(args);
        }
    }

    private static String[] planetRows(String raw) {
        return raw.isEmpty() ? new String[0] : raw.split(String.valueOf(R));
    }

    private static String planetNameAt(String raw, int index) {
        String[] rows = planetRows(raw);
        if (index < 0 || index >= rows.length) return null;
        String[] f = rows[index].split(String.valueOf(F));
        return f.length < 1 || f[0].isEmpty() ? null : f[0];
    }

    private String planetNameAt(int index) {
        return planetsValue == null ? null : planetNameAt(planetsValue.getStringValue(), index);
    }

    /**
     * 两行状态。字段：[0]火箭等级 [1]EU/t [2]已配置台数 [3]运行中台数 [4]机位容量 [5]层数 [6]润滑油 mB/s。
     * 拆成两行是因为合成一行在 219px 里放不下（会被终端边框截掉）。
     */
    private static String formatStatus(int line, String raw) {
        if (raw.isEmpty()) return "";
        String[] p = raw.split(String.valueOf(F));
        if (p.length < 7) return "";
        int rocket = parseInt(p[0]);
        if (line == 0) {
            return tr(
                "futa_gtnh.space_center.gui.status.0",
                rocket > 0 ? String.valueOf(rocket) : "-",
                p[1],
                p[2] + "/" + p[4],
                p[5]);
        }
        return tr("futa_gtnh.space_center.gui.status.1", p[3], p[6]);
    }

    private static String formatPlanetRow(String raw, int index, boolean selected) {
        String name = planetNameAt(raw, index);
        if (name == null) return "";
        String[] f = raw.split(String.valueOf(R))[index].split(String.valueOf(F));
        int flags = f.length > 2 ? parseInt(f[2]) : 0;
        int tier = f.length > 1 ? parseInt(f[1]) : 0;
        String state;
        String color;
        if ((flags & 16) == 0) {
            color = "\u00A7c";
            state = tr("futa_gtnh.space_center.gui.locked", tier);
        } else if ((flags & 1) == 0) {
            color = "\u00A78";
            state = tr("futa_gtnh.space_center.gui.unsurveyed");
        } else if ((flags & 32) != 0) {
            color = "\u00A7a";
            state = tr("futa_gtnh.space_center.gui.active");
        } else if ((flags & 8) != 0) {
            color = "\u00A76";
            state = tr("futa_gtnh.space_center.gui.paused");
        } else {
            color = "\u00A77";
            state = tr("futa_gtnh.space_center.gui.idle");
        }
        return (selected ? "\u00A7e" : "\u00A7f") + planetDisplayName(name) + "  " + color + state;
    }

    private String formatPageLabel(int page) {
        int total = planetRows(planetsValue.getStringValue()).length;
        return tr("futa_gtnh.space_center.gui.page", page + 1, planetPageCount(), total);
    }

    private static String formatSelectedHeader(String configRaw) {
        if (configRaw.isEmpty()) return tr("futa_gtnh.space_center.gui.none_selected");
        String[] p = configRaw.split(String.valueOf(F));
        String name = p[0].isEmpty() ? null : planetDisplayName(p[0]);
        int veins = p.length > 4 && !p[4].isEmpty() ? p[4].split(",").length : 0;
        return tr("futa_gtnh.space_center.gui.selected", name, veins);
    }

    private static String configCount(String raw, int row) {
        if (raw.isEmpty()) return "0";
        String[] p = raw.split(String.valueOf(F));
        if (p.length < 3) return "0";
        String[] counts = (row < 4 ? p[1] : p[2]).split(",");
        int idx = row % 4;
        return idx < counts.length ? counts[idx].trim() : "0";
    }

    /** 矿脉列的一行：第 line 行 = 「本地化材料名 百分比%」；超过上限时最后一行写「…还有 N 种」。 */
    private static String formatVeinLine(String configRaw, int line) {
        if (configRaw.isEmpty()) return "";
        String[] p = configRaw.split(String.valueOf(F));
        if (p.length < 5 || p[4].isEmpty()) return "";
        String[] entries = p[4].split(",");
        if (line >= MAX_VEIN_LINES) return "";
        if (line == MAX_VEIN_LINES - 1 && entries.length > MAX_VEIN_LINES) {
            return "\u00A78" + tr("futa_gtnh.space_center.gui.more_veins", entries.length - (MAX_VEIN_LINES - 1));
        }
        if (line >= entries.length) return "";
        long total = 0;
        for (String entry : entries) total += parseInt(entry.substring(entry.lastIndexOf(':') + 1));
        int sep = entries[line].lastIndexOf(':');
        String display = materialDisplayName(entries[line].substring(0, sep));
        long percent = total == 0 ? 0 : Math.round(parseInt(entries[line].substring(sep + 1)) * 100D / total);
        return cut(display, 13) + " " + percent + "%";
    }

    /** 矿脉权重预览 tooltip：每行「本地化名: 权重」（客户端按本地语言解析材料名）。 */
    private static void buildVeinTooltip(RichTooltip tooltip, String configRaw) {
        String[] p = configRaw.split(String.valueOf(F));
        if (configRaw.isEmpty() || p.length < 5 || p[4].isEmpty()) return;
        tooltip.addLine(IKey.lang("futa_gtnh.space_center.gui.veins"));
        String[] entries = p[4].split(",");
        int shown = 0;
        for (String entry : entries) {
            if (shown++ >= 24) {
                tooltip.addLine(IKey.dynamic(() -> tr("futa_gtnh.space_center.gui.more_veins", entries.length - 24)));
                break;
            }
            int sep = entry.lastIndexOf(':');
            if (sep <= 0) continue;
            tooltip.addLine(
                IKey.dynamic(() -> materialDisplayName(entry.substring(0, sep)) + ": " + entry.substring(sep + 1)));
        }
    }

    /** 这一档机器的数值 tooltip（客户端按等级现算，不走同步）。 */
    private static void buildTierTooltip(RichTooltip tooltip, boolean miner, int tier) {
        if (miner) {
            int side = SpaceCenterMachine.minerAreaSide(tier);
            tooltip.addLine(
                IKey.dynamic(
                    () -> tr(
                        "futa_gtnh.space_center.gui.tt.miner",
                        SpaceCenterMachine.minerEu(tier),
                        SpaceCenterMachine.minerCycle(tier),
                        side,
                        side * side)));
        } else {
            int range = SpaceCenterMachine.DRILLER_RANGE_CHUNKS[tier - 1];
            // 词条是「EU/t · R×R = 面积 · 系数」共 5 个占位符：2.1.0 这里少传了一个 R，
            // 悬停钻机行就是一次 MissingFormatArgumentException
            tooltip.addLine(
                IKey.dynamic(
                    () -> tr(
                        "futa_gtnh.space_center.gui.tt.driller",
                        SpaceCenterMachine.drillerEu(tier),
                        range,
                        range,
                        range * range,
                        com.futa_gtnh.Config.spaceCenterOilYieldFactor)));
        }
    }

    private static String formatFluidName(String planetsRaw, String configRaw) {
        String[] cfg = configRaw.split(String.valueOf(F));
        if (configRaw.isEmpty() || cfg.length < 4) return "-";
        int sel = parseInt(cfg[3]);
        for (String row : planetRows(planetsRaw)) {
            String[] f = row.split(String.valueOf(F));
            if (f.length < 4 || !f[0].equals(cfg[0])) continue;
            String[] fluids = f[3].isEmpty() ? new String[0] : f[3].split(",");
            if (fluids.length == 0) return "\u00A78" + tr("futa_gtnh.space_center.gui.no_field");
            int idx = SpaceCenterMachine.clamp(sel, 0, fluids.length - 1);
            net.minecraftforge.fluids.Fluid fluid = net.minecraftforge.fluids.FluidRegistry.getFluid(fluids[idx]);
            String local = fluid != null ? fluid.getLocalizedName(null) : fluids[idx];
            return cut(local, 20) + (fluids.length > 1 ? " (" + (idx + 1) + "/" + fluids.length + ")" : "");
        }
        return "-";
    }

    /** 运行摘要的一行：星球 类型等级 ×台数 状态。 */
    private static String formatMachineLine(String raw, int line) {
        if (raw.isEmpty()) return line == 0 ? tr("futa_gtnh.space_center.gui.no_machine") : "";
        String[] rows = raw.split(String.valueOf(R));
        if (line >= MAX_SUMMARY_LINES) return "";
        if (line == MAX_SUMMARY_LINES - 1 && rows.length > MAX_SUMMARY_LINES) {
            return "\u00A78" + tr("futa_gtnh.space_center.gui.more_machines", rows.length - (MAX_SUMMARY_LINES - 1));
        }
        if (line >= rows.length) return "";
        String[] f = rows[line].split(",");
        if (f.length < 5) return "";
        return planetDisplayName(f[0]) + " "
            + tr(MACHINE_TYPE_KEYS[parseInt(f[1])])
            + f[2]
            + " ×"
            + f[4]
            + " "
            + pauseColor(f[3])
            + stateName(f[3]);
    }

    private static String pauseColor(String state) {
        if ("work".equals(state)) return "\u00A7a";
        if ("descend".equals(state)) return "\u00A7b";
        return SpaceCenterMachine.PAUSE_AREA_EXHAUSTED.equals(state) ? "\u00A7c" : "\u00A76";
    }

    private static String stateName(String state) {
        String key = "futa_gtnh.space_center.gui.state." + state;
        String localized = StatCollector.translateToLocal(key);
        // 没有对应词条的 token（以后新增的原因忘了配 lang）原样显示，别装作「开采中」
        return localized.equals(key) ? state : localized;
    }

    /** 材料显示名：按客户端语言解析；解析不到就退回内部名。 */
    private static String materialDisplayName(String internalName) {
        IOreMaterial material = IOreMaterial.findMaterial(internalName);
        if (material == null) return internalName;
        String localized = material.getLocalizedName();
        return localized == null || localized.isEmpty() ? internalName : localized;
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

    private static String cut(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
