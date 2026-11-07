package com.futa_gtnh.miner;

import java.util.List;
import java.util.UUID;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.futa_gtnh.aewireless.AeSignalRegistry;
import com.futa_gtnh.aewireless.TileAeSignalTransmitter;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.modularui2.CoverGuiData;
import gregtech.common.gui.modularui.cover.base.CoverBaseGui;

/**
 * 「AE 采矿枢纽」覆盖板的 MUI2 界面：发信器选择（&lt; &gt; 循环）+ 实时状态行。
 *
 * <p>
 * 骨架与 {@code CoverAeSignalReceiverGui} 同款：候选列表编码成单字符串同步，
 * 选中用下标；状态行只同步原始枚举序号与数值，本地化在客户端拼。所有会交互的
 * sync 值必须经 {@code syncManager.syncValue} 注册（未注册点击即崩，见
 * {@code CoverAeSignalReceiverGui} 的教训注释）。
 */
public class CoverMinerIoGui extends CoverBaseGui<CoverMinerIo> {

    /** 编码分隔符：条目之间 / 字段之间（用不可见控制字符，不撞名字）。 */
    private static final String SEP_ENTRY = "\u0001";
    private static final String SEP_FIELD = "\u001F";

    public CoverMinerIoGui(CoverMinerIo cover) {
        super(cover);
    }

    @Override
    protected String getGuiId() {
        return "cover.futa_miner_io";
    }

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

        IntSyncValue linkState = new IntSyncValue(
            () -> cover.getLinkState()
                .ordinal());
        syncManager.syncValue("link_state", linkState);
        LongSyncValue lastMoved = new LongSyncValue(cover::getLastMoved);
        syncManager.syncValue("last_moved", lastMoved);

        column.child(positionRow(createSenderRow(senderList, selection)))
            .child(positionRow(createStatusRow(linkState, lastMoved)));
    }

    // ------------------------------------------------------------------
    // 发信器选择（与 CoverAeSignalReceiverGui 同款）
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
            IKey.lang("futa_gtnh.miner.sender")
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

    /** 服务端：按候选下标选中发信器；-1 = 清除选择。 */
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
        String none = StatCollector.translateToLocal("futa_gtnh.miner.sender.none");
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
    // 状态
    // ------------------------------------------------------------------

    private Flow createStatusRow(IntSyncValue linkState, LongSyncValue lastMoved) {
        return row().child(
            IKey.dynamic(() -> statusText(linkState.getIntValue(), lastMoved.getLongValue()))
                .asWidget()
                .verticalCenter());
    }

    /** 客户端：由同步来的原始数据拼状态行——本地化必须在这里做，服务端只有 en_US。 */
    private String statusText(int stateOrdinal, long lastMoved) {
        CoverMinerIo.LinkState state = CoverMinerIo.LinkState.VALUES[Math
            .floorMod(stateOrdinal, CoverMinerIo.LinkState.VALUES.length)];
        String stateText = StatCollector.translateToLocal(
            "futa_gtnh.miner.link." + state.name()
                .toLowerCase());
        if (state == CoverMinerIo.LinkState.OK && lastMoved >= 0) {
            return StatCollector.translateToLocalFormatted("futa_gtnh.miner.status.moved", lastMoved, stateText);
        }
        return StatCollector.translateToLocalFormatted("futa_gtnh.miner.status.state", stateText);
    }

    private static String localizedState(TileAeSignalTransmitter.State state) {
        return StatCollector.translateToLocal(
            "futa_gtnh.aewireless.state." + state.name()
                .toLowerCase());
    }
}
