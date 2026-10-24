package com.futa_gtnh.aewireless;

import java.util.List;
import java.util.UUID;

import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.fluid.FluidStackTank;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.EnumSyncValue;
import com.cleanroommc.modularui.value.sync.FluidSlotSyncHandler;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Grid;
import com.cleanroommc.modularui.widgets.slot.FluidSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.slot.PhantomItemSlot;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.modularui2.CoverGuiData;
import gregtech.api.modularui2.GTGuiTextures;
import gregtech.common.gui.modularui.cover.base.CoverBaseGui;
import gregtech.common.modularui2.sync.LinkedBoolValue;
import gregtech.common.modularui2.widget.SelectButton;

/**
 * 「AE 无线接收端」覆盖板的 MUI2 界面。
 *
 * <p>
 * 布局参考 {@code CoverControlsWorkGui} / {@code CoverFluidfilterGui}：发信端选择（&lt; &gt; 循环）
 * → 监听类型切换 → 幽灵物品槽 / 流体槽 → 阈值 → 触发条件 → 满足条件时的动作 → 监听频率 →
 * 断联行为 → 实时状态行。
 *
 * <p>
 * <b>所有会交互的 sync 值必须经 {@code syncManager.syncValue} 注册</b>——未注册的值第一次点击就抛
 * {@code IllegalStateException: Sync handler is not yet initialised!} 崩客户端（1.33.1 的线上崩溃即此）。
 *
 * <p>
 * <b>本地化一律在客户端做</b>：{@code StringSyncValue} 的 getter 在服务端求值，专用服务端只有
 * en_US——同步原始数据（枚举序号、数量、编码串），客户端再拼本地化文本。
 *
 * <p>
 * 图标语义全套对齐 GT 惯例：启用/停用 = CHECKMARK/FORBIDDEN；条件 ≥/≤ = REDSTONE_ON/OFF
 * （机器控制器覆盖板同款）；断联「保持」= LOCK（中性，不用叉）；物品/流体开关 = LIQUIDMODE 对
 * （混凝土回填机同款）。
 */
public class CoverAeSignalReceiverGui extends CoverBaseGui<CoverAeSignalReceiver> {

    /** 编码分隔符：条目之间 / 字段之间（用不可见控制字符，不撞名字）。 */
    private static final String SEP_ENTRY = "\u0001";
    private static final String SEP_FIELD = "\u001F";

    public CoverAeSignalReceiverGui(CoverAeSignalReceiver cover) {
        super(cover);
    }

    @Override
    protected String getGuiId() {
        return "cover.futa_ae_signal_receiver";
    }

    /**
     * 所有配置行共用的行头：统一左缩进（对齐 GT 覆盖板模板）与行间距；
     * 再由 {@link #positionRow} 统一加 childPadding。
     */
    private static Flow row() {
        return Flow.row()
            .marginLeft(WIDGET_MARGIN)
            .marginBottom(4);
    }

    @Override
    public void addUIWidgets(PanelSyncManager syncManager, Flow column, CoverGuiData data) {
        StringSyncValue senderList = new StringSyncValue(this::encodeSenderList);
        syncManager.syncValue("sender_list", senderList);

        IntSyncValue selection = new IntSyncValue(this::selectedIndex, this::selectByIndex).allowC2S();
        syncManager.syncValue("sender_selection", selection);

        // 状态行只同步原始数据，本地化在客户端做（服务端只有 en_US）。
        IntSyncValue linkState = new IntSyncValue(() -> cover.getLinkState()
            .ordinal());
        syncManager.syncValue("link_state", linkState);
        LongSyncValue lastAmount = new LongSyncValue(cover::getLastAmount);
        syncManager.syncValue("last_amount", lastAmount);

        column.child(positionRow(createSenderRow(senderList, selection)))
            .child(positionRow(createTypeRow(syncManager)))
            .child(positionRow(createMonitorRow()))
            .child(positionRow(createThresholdRow(syncManager)))
            .child(positionRow(createConditionRow(syncManager)))
            .child(positionRow(createActionRow(syncManager)))
            .child(positionRow(createIntervalRow(syncManager)))
            .child(positionRow(createLinkLostRow(syncManager)))
            .child(positionRow(createStatusRow(linkState, lastAmount)));
    }

    // ------------------------------------------------------------------
    // 发信端选择
    // ------------------------------------------------------------------

    private Flow createSenderRow(StringSyncValue senderList, IntSyncValue selection) {
        ButtonWidget<?> prev = new ButtonWidget<>().size(16)
            .overlay(IKey.str("<"))
            .onMousePressed(button -> {
                int count = decodeEntryCount(senderList.getStringValue());
                if (count == 0) return true;
                int current = selection.getIntValue();
                int next = current < 0 ? count - 1 : current - 1;
                if (next < 0) next = count - 1;
                selection.setIntValue(next);
                return true;
            });
        ButtonWidget<?> next = new ButtonWidget<>().size(16)
            .overlay(IKey.str(">"))
            .onMousePressed(button -> {
                int count = decodeEntryCount(senderList.getStringValue());
                if (count == 0) return true;
                int current = selection.getIntValue();
                selection.setIntValue(current < 0 ? 0 : (current + 1) % count);
                return true;
            });
        return row().child(
            IKey.lang("futa_gtnh.aewireless.sender")
                .asWidget()
                .verticalCenter())
            .child(prev.marginLeft(4))
            .child(
                IKey.dynamic(() -> describeSelection(senderList.getStringValue(), selection.getIntValue()))
                    .asWidget()
                    .verticalCenter()
                    .marginRight(4))
            .child(next);
    }

    /** 服务端：当前选中条目在候选列表里的下标（没选/不在列表 = -1）。 */
    private int selectedIndex() {
        if (!cover.hasTarget()) return -1;
        List<AeSignalRegistry.Entry> list = visibleSenders();
        for (int i = 0; i < list.size(); i++) {
            AeSignalRegistry.Entry entry = list.get(i);
            if (entry.dim == cover.getTargetDim() && entry.x == cover.getTargetX()
                && entry.y == cover.getTargetY()
                && entry.z == cover.getTargetZ()) return i;
        }
        return -1;
    }

    /** 服务端：按候选下标选中发信端；-1 = 清除选择。 */
    private void selectByIndex(int index) {
        List<AeSignalRegistry.Entry> list = visibleSenders();
        if (index < 0) {
            cover.clearTarget();
            return;
        }
        if (index >= list.size()) return;
        AeSignalRegistry.Entry entry = list.get(index);
        cover.setTarget(entry.dim, entry.x, entry.y, entry.z);
    }

    /** 服务端：候选列表（按机器所有者的队伍可见性过滤）。 */
    private List<AeSignalRegistry.Entry> visibleSenders() {
        UUID owner = cover.getTile() instanceof IGregTechTileEntity gte ? gte.getOwnerUuid() : null;
        return AeSignalRegistry.listVisible(owner);
    }

    /** 服务端：把候选列表编码成单字符串（名字/坐标/状态）。 */
    private String encodeSenderList() {
        StringBuilder sb = new StringBuilder();
        for (AeSignalRegistry.Entry entry : visibleSenders()) {
            if (sb.length() > 0) sb.append(SEP_ENTRY);
            sb.append(entry.name)
                .append(SEP_FIELD)
                .append(entry.dim)
                .append(SEP_FIELD)
                .append(entry.x)
                .append(SEP_FIELD)
                .append(entry.y)
                .append(SEP_FIELD)
                .append(entry.z)
                .append(SEP_FIELD)
                .append(entry.state.ordinal());
        }
        return sb.toString();
    }

    private static int decodeEntryCount(String encoded) {
        if (encoded == null || encoded.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < encoded.length(); i++) {
            if (encoded.charAt(i) == SEP_ENTRY.charAt(0)) count++;
        }
        return count;
    }

    /** 客户端：选中条目的展示文本。 */
    private static String describeSelection(String encoded, int index) {
        String none = StatCollector.translateToLocal("futa_gtnh.aewireless.sender.none");
        if (index < 0) return none;
        String[] entries = encoded == null || encoded.isEmpty() ? new String[0] : encoded.split(SEP_ENTRY);
        if (index >= entries.length) return none;
        String[] fields = entries[index].split(SEP_FIELD);
        if (fields.length < 6) return none;
        TileAeSignalTransmitter.State state;
        try {
            state = TileAeSignalTransmitter.State.VALUES[Integer.parseInt(fields[5])];
        } catch (RuntimeException ignored) {
            return fields[0];
        }
        return fields[0] + " [" + localizedState(state) + "]";
    }

    // ------------------------------------------------------------------
    // 监听配置
    // ------------------------------------------------------------------

    private Flow createTypeRow(PanelSyncManager syncManager) {
        BooleanSyncValue fluidMode = new BooleanSyncValue(cover::isFluidMode, cover::setFluidMode).allowC2S();
        syncManager.syncValue("fluid_mode", fluidMode);
        // 物品/流体是一对模式，不是开/关——图标用 GT 的液体模式对，不用勾叉。
        return row().child(
            new ToggleButton().value(fluidMode)
                .overlay(true, GTGuiTextures.OVERLAY_BUTTON_LIQUIDMODE)
                .overlay(false, GTGuiTextures.OVERLAY_BUTTON_LIQUIDMODE_OFF)
                .size(16))
            .child(
                IKey.dynamic(
                    () -> StatCollector.translateToLocal(
                        cover.isFluidMode() ? "futa_gtnh.aewireless.type.fluid"
                            : "futa_gtnh.aewireless.type.item"))
                    .asWidget()
                    .verticalCenter()
                    .marginLeft(4));
    }

    /** 物品用幽灵槽（NEI 可拖），流体用幽灵流体槽（手持容器点击）。 */
    private Flow createMonitorRow() {
        return row().child(
            new PhantomItemSlot().slot(new ModularSlot(cover.getFilter(), 0))
                .marginRight(4))
            .child(
                new FluidSlot().syncHandler(
                    new FluidSlotSyncHandler(monitorFluidTank()).phantom(true)
                        .controlsAmount(false)))
            .child(
                IKey.lang("futa_gtnh.aewireless.monitor")
                    .asWidget()
                    .verticalCenter()
                    .marginLeft(4));
    }

    /** 流体监听选择的槽。 */
    private FluidStackTank monitorFluidTank() {
        return new FluidStackTank(() -> {
            if (!cover.isFluidMode() || cover.getFluidName()
                .isEmpty()) return null;
            Fluid fluid = FluidRegistry.getFluid(cover.getFluidName());
            return fluid == null ? null : new FluidStack(fluid, 1);
        },
            fluidStack -> cover.setFluidName(
                fluidStack == null ? ""
                    : fluidStack.getFluid()
                        .getName()),
            1);
    }

    private Flow createThresholdRow(PanelSyncManager syncManager) {
        IntSyncValue threshold = new IntSyncValue(cover::getThreshold, cover::setThreshold).allowC2S();
        syncManager.syncValue("threshold", threshold);
        return row().child(
            makeNumberField(70).value(threshold)
                .defaultNumber(64)
                .numbersInt(0, Integer.MAX_VALUE)
                .marginRight(4))
            .child(
                IKey.lang("futa_gtnh.aewireless.threshold")
                    .asWidget()
                    .verticalCenter());
    }

    /** GT 覆盖板模板的选项组网格：元素边距显式归零/归一，垂直居中。 */
    private static Grid optionGrid() {
        return new Grid().coverChildren()
            .minElementMarginRight(WIDGET_MARGIN)
            .minElementMarginBottom(2)
            .minElementMarginTop(0)
            .minElementMarginLeft(0)
            .alignment(Alignment.CenterLeft);
    }

    private Flow createConditionRow(PanelSyncManager syncManager) {
        EnumSyncValue<CoverAeSignalReceiver.ConditionMode, ?> condition = new EnumSyncValue<>(
            CoverAeSignalReceiver.ConditionMode.class,
            cover::getCondition,
            cover::setCondition).allowC2S();
        syncManager.syncValue("condition", condition);
        return row().child(
            optionGrid().row(
                new SelectButton()
                    .value(LinkedBoolValue.of(condition, CoverAeSignalReceiver.ConditionMode.ABOVE))
                    .overlay(GTGuiTextures.OVERLAY_BUTTON_REDSTONE_ON)
                    .size(16),
                IKey.lang("futa_gtnh.aewireless.condition.above")
                    .asWidget())
                .row(
                    new SelectButton()
                        .value(LinkedBoolValue.of(condition, CoverAeSignalReceiver.ConditionMode.BELOW))
                        .overlay(GTGuiTextures.OVERLAY_BUTTON_REDSTONE_OFF)
                        .size(16),
                    IKey.lang("futa_gtnh.aewireless.condition.below")
                        .asWidget()));
    }

    private Flow createActionRow(PanelSyncManager syncManager) {
        EnumSyncValue<CoverAeSignalReceiver.OnCondition, ?> action = new EnumSyncValue<>(
            CoverAeSignalReceiver.OnCondition.class,
            cover::getAction,
            cover::setAction).allowC2S();
        syncManager.syncValue("action", action);
        return row().child(
            optionGrid().row(
                new SelectButton()
                    .value(LinkedBoolValue.of(action, CoverAeSignalReceiver.OnCondition.ENABLE))
                    .overlay(GTGuiTextures.OVERLAY_BUTTON_CHECKMARK)
                    .size(16),
                IKey.lang("futa_gtnh.aewireless.action.enable")
                    .asWidget())
                .row(
                    new SelectButton()
                        .value(LinkedBoolValue.of(action, CoverAeSignalReceiver.OnCondition.DISABLE))
                        .overlay(GTGuiTextures.OVERLAY_BUTTON_FORBIDDEN)
                        .size(16),
                    IKey.lang("futa_gtnh.aewireless.action.disable")
                        .asWidget()));
    }

    private Flow createIntervalRow(PanelSyncManager syncManager) {
        IntSyncValue interval = new IntSyncValue(cover::getIntervalTicks, cover::setIntervalTicks).allowC2S();
        syncManager.syncValue("interval", interval);
        return row().child(
            makeNumberField(70).value(interval)
                .defaultNumber(20)
                .numbersInt(5, 600)
                .marginRight(4))
            .child(
                IKey.lang("futa_gtnh.aewireless.interval")
                    .asWidget()
                    .verticalCenter());
    }

    private Flow createLinkLostRow(PanelSyncManager syncManager) {
        EnumSyncValue<CoverAeSignalReceiver.LinkLostBehavior, ?> linkLost = new EnumSyncValue<>(
            CoverAeSignalReceiver.LinkLostBehavior.class,
            cover::getLinkLost,
            cover::setLinkLost).allowC2S();
        syncManager.syncValue("link_lost", linkLost);
        return row().child(
            optionGrid().row(
                new SelectButton()
                    .value(LinkedBoolValue.of(linkLost, CoverAeSignalReceiver.LinkLostBehavior.KEEP))
                    .overlay(GTGuiTextures.OVERLAY_BUTTON_LOCK)
                    .size(16),
                IKey.lang("futa_gtnh.aewireless.linklost.keep")
                    .asWidget())
                .row(
                    new SelectButton()
                        .value(LinkedBoolValue.of(linkLost, CoverAeSignalReceiver.LinkLostBehavior.ENABLE))
                        .overlay(GTGuiTextures.OVERLAY_BUTTON_CHECKMARK)
                        .size(16),
                    IKey.lang("futa_gtnh.aewireless.linklost.enable")
                        .asWidget())
                .row(
                    new SelectButton()
                        .value(LinkedBoolValue.of(linkLost, CoverAeSignalReceiver.LinkLostBehavior.DISABLE))
                        .overlay(GTGuiTextures.OVERLAY_BUTTON_FORBIDDEN)
                        .size(16),
                    IKey.lang("futa_gtnh.aewireless.linklost.disable")
                        .asWidget()));
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private Flow createStatusRow(IntSyncValue linkState, LongSyncValue lastAmount) {
        return row().child(
            IKey.dynamic(() -> statusText(linkState.getIntValue(), lastAmount.getLongValue()))
                .asWidget()
                .verticalCenter());
    }

    /** 客户端：由同步来的原始数据拼状态行——本地化必须在这里做，服务端只有 en_US。 */
    private String statusText(int stateOrdinal, long amount) {
        CoverAeSignalReceiver.LinkState state = CoverAeSignalReceiver.LinkState.VALUES[Math
            .floorMod(stateOrdinal, CoverAeSignalReceiver.LinkState.VALUES.length)];
        String stateText = localizedState(state);
        if (state == CoverAeSignalReceiver.LinkState.OK && amount >= 0) {
            return StatCollector.translateToLocalFormatted(
                "futa_gtnh.aewireless.status.amount",
                amount,
                cover.isFluidMode() ? "mB" : "",
                stateText);
        }
        return StatCollector.translateToLocalFormatted("futa_gtnh.aewireless.status.state", stateText);
    }

    private static String localizedState(TileAeSignalTransmitter.State state) {
        return StatCollector.translateToLocal(
            "futa_gtnh.aewireless.state." + state.name()
                .toLowerCase());
    }

    private static String localizedState(CoverAeSignalReceiver.LinkState state) {
        return StatCollector.translateToLocal(
            "futa_gtnh.aewireless.link." + state.name()
                .toLowerCase());
    }
}
