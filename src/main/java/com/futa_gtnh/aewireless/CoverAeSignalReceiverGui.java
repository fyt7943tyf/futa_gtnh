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
 * 布局参考 {@code CoverControlsWorkGui} / {@code CoverFluidfilterGui}：
 * 发信端选择（&lt; &gt; 循环）→ 监听类型切换 → 幽灵物品槽 / 流体槽 →
 * 阈值 → 触发条件 → 满足条件时的动作 → 监听频率 → 断联行为 → 实时状态行。
 *
 * <p>
 * 发信端候选列表只在服务端算（要查队伍可见性），整体编码成一个字符串同步给
 * 客户端做展示；选择本身用索引同步，服务端把索引换算回坐标写进覆盖板。
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

    @Override
    public void addUIWidgets(PanelSyncManager syncManager, Flow column, CoverGuiData data) {
        StringSyncValue senderList = new StringSyncValue(this::encodeSenderList);
        syncManager.syncValue("sender_list", senderList);

        IntSyncValue selection = new IntSyncValue(this::selectedIndex, this::selectByIndex).allowC2S();
        syncManager.syncValue("sender_selection", selection);

        StringSyncValue status = new StringSyncValue(this::statusText);
        syncManager.syncValue("status", status);

        column.child(positionRow(createSenderRow(senderList, selection)))
            .child(positionRow(createTypeRow(syncManager)))
            .child(positionRow(createMonitorRow()))
            .child(positionRow(createThresholdRow()))
            .child(positionRow(createConditionRow()))
            .child(positionRow(createActionRow()))
            .child(positionRow(createIntervalRow()))
            .child(positionRow(createLinkLostRow()))
            .child(positionRow(createStatusRow(status)));
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
        return Flow.row()
            .marginBottom(4)
            .child(
                IKey.lang("futa_gtnh.aewireless.sender")
                    .asWidget())
            .child(prev.marginLeft(8))
            .child(
                IKey.dynamic(() -> describeSelection(senderList.getStringValue(), selection.getIntValue()))
                    .asWidget()
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
        return Flow.row()
            .marginBottom(4)
            .child(
                new ToggleButton().value(fluidMode)
                    .overlay(true, GTGuiTextures.OVERLAY_BUTTON_CHECKMARK)
                    .overlay(false, GTGuiTextures.OVERLAY_BUTTON_CROSS)
                    .size(16)
                    .tooltipDynamic(
                        t -> t.addLine(
                            StatCollector.translateToLocal(
                                cover.isFluidMode() ? "futa_gtnh.aewireless.type.fluid"
                                    : "futa_gtnh.aewireless.type.item"))))
            .child(
                IKey.dynamic(
                    () -> StatCollector.translateToLocal(
                        cover.isFluidMode() ? "futa_gtnh.aewireless.type.fluid" : "futa_gtnh.aewireless.type.item"))
                    .asWidget()
                    .marginLeft(4));
    }

    /** 物品用幽灵槽（NEI 可拖），流体用幽灵流体槽（手持容器点击）。 */
    private Flow createMonitorRow() {
        return Flow.row()
            .marginBottom(4)
            .child(
                new PhantomItemSlot().slot(new ModularSlot(cover.getFilter(), 0))
                    .marginRight(6))
            .child(
                new FluidSlot().syncHandler(
                    new FluidSlotSyncHandler(monitorFluidTank()).phantom(true)
                        .controlsAmount(false)))
            .child(
                IKey.lang("futa_gtnh.aewireless.monitor")
                    .asWidget()
                    .scale(0.8f)
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

    private Flow createThresholdRow() {
        IntSyncValue threshold = new IntSyncValue(cover::getThreshold, cover::setThreshold).allowC2S();
        return Flow.row()
            .marginBottom(4)
            .child(
                makeNumberField(70).value(threshold)
                    .defaultNumber(64)
                    .numbersInt(0, Integer.MAX_VALUE)
                    .marginRight(6))
            .child(
                IKey.lang("futa_gtnh.aewireless.threshold")
                    .asWidget());
    }

    private Flow createConditionRow() {
        EnumSyncValue<CoverAeSignalReceiver.ConditionMode, ?> condition = new EnumSyncValue<>(
            CoverAeSignalReceiver.ConditionMode.class,
            cover::getCondition,
            cover::setCondition).allowC2S();
        return Flow.row()
            .marginBottom(4)
            .child(
                new Grid().coverChildren()
                    .minElementMarginBottom(2)
                    .minElementMarginRight(WIDGET_MARGIN)
                    .alignment(Alignment.CenterLeft)
                    .row(
                        new SelectButton()
                            .value(LinkedBoolValue.of(condition, CoverAeSignalReceiver.ConditionMode.ABOVE))
                            .overlay(GTGuiTextures.OVERLAY_BUTTON_EMIT_REDSTONE)
                            .size(16),
                        IKey.lang("futa_gtnh.aewireless.condition.above")
                            .asWidget())
                    .row(
                        new SelectButton()
                            .value(LinkedBoolValue.of(condition, CoverAeSignalReceiver.ConditionMode.BELOW))
                            .overlay(GTGuiTextures.OVERLAY_BUTTON_CROSS)
                            .size(16),
                        IKey.lang("futa_gtnh.aewireless.condition.below")
                            .asWidget()));
    }

    private Flow createActionRow() {
        EnumSyncValue<CoverAeSignalReceiver.OnCondition, ?> action = new EnumSyncValue<>(
            CoverAeSignalReceiver.OnCondition.class,
            cover::getAction,
            cover::setAction).allowC2S();
        return Flow.row()
            .marginBottom(4)
            .child(
                new Grid().coverChildren()
                    .minElementMarginBottom(2)
                    .minElementMarginRight(WIDGET_MARGIN)
                    .alignment(Alignment.CenterLeft)
                    .row(
                        new SelectButton().value(LinkedBoolValue.of(action, CoverAeSignalReceiver.OnCondition.ENABLE))
                            .overlay(GTGuiTextures.OVERLAY_BUTTON_CHECKMARK)
                            .size(16),
                        IKey.lang("futa_gtnh.aewireless.action.enable")
                            .asWidget())
                    .row(
                        new SelectButton().value(LinkedBoolValue.of(action, CoverAeSignalReceiver.OnCondition.DISABLE))
                            .overlay(GTGuiTextures.OVERLAY_BUTTON_FORBIDDEN)
                            .size(16),
                        IKey.lang("futa_gtnh.aewireless.action.disable")
                            .asWidget()));
    }

    private Flow createIntervalRow() {
        IntSyncValue interval = new IntSyncValue(cover::getIntervalTicks, cover::setIntervalTicks).allowC2S();
        return Flow.row()
            .marginBottom(4)
            .child(
                makeNumberField(70).value(interval)
                    .defaultNumber(20)
                    .numbersInt(5, 600)
                    .marginRight(6))
            .child(
                IKey.lang("futa_gtnh.aewireless.interval")
                    .asWidget());
    }

    private Flow createLinkLostRow() {
        EnumSyncValue<CoverAeSignalReceiver.LinkLostBehavior, ?> linkLost = new EnumSyncValue<>(
            CoverAeSignalReceiver.LinkLostBehavior.class,
            cover::getLinkLost,
            cover::setLinkLost).allowC2S();
        return Flow.row()
            .marginBottom(4)
            .child(
                new Grid().coverChildren()
                    .minElementMarginBottom(2)
                    .minElementMarginRight(WIDGET_MARGIN)
                    .alignment(Alignment.CenterLeft)
                    .row(
                        new SelectButton()
                            .value(LinkedBoolValue.of(linkLost, CoverAeSignalReceiver.LinkLostBehavior.KEEP))
                            .overlay(GTGuiTextures.OVERLAY_BUTTON_CROSS)
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

    private Flow createStatusRow(StringSyncValue status) {
        return Flow.row()
            .marginBottom(2)
            .child(
                IKey.dynamic(status::getStringValue)
                    .asWidget()
                    .scale(0.9f));
    }

    /** 服务端：状态行文本（当前存量 vs 阈值 + 链路状态）。 */
    private String statusText() {
        String state = localizedState(cover.getLinkState());
        if (cover.getLinkState() == CoverAeSignalReceiver.LinkState.OK && cover.getLastAmount() >= 0) {
            return StatCollector.translateToLocalFormatted(
                "futa_gtnh.aewireless.status.amount",
                cover.getLastAmount(),
                cover.isFluidMode() ? "mB" : "",
                state);
        }
        return StatCollector.translateToLocalFormatted("futa_gtnh.aewireless.status.state", state);
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
