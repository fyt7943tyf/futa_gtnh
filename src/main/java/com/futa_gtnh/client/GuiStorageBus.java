package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.client.ui.FutaMarkedButton;
import com.futa_gtnh.client.ui.FutaUiTheme;
import com.futa_gtnh.inventory.ContainerStorageBus;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageBusConfig;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.storagebus.StorageBusConfig;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

/**
 * 存储总线的配置界面。
 *
 * <p>
 * 布局尽量贴近 AE2 的存储总线：上方是访问模式与优先级，中间是物品/流体筛选，
 * 底部是清空与完成。筛选格子是自绘的虚拟槽 —— 点一下把光标上的东西设为筛选，
 * 空手点一下清除；它不会消耗光标上的物品，也不会把光标物品塞进任何容器。
 *
 * <p>
 * 所有改动都整份发回服务端，服务端回一份权威值覆盖本地。界面从不做本地预测，
 * 所以伪造/丢包最多让界面短暂显示旧值，不会让服务端配置和界面长期跑偏。
 */
public class GuiStorageBus extends FutaGuiContainer {

    private static final int GUI_WIDTH = 256;
    private static final int GUI_HEIGHT = 212;

    private static final int BTN_TARGET_PREV = 0;
    private static final int BTN_TARGET_NEXT = 1;
    private static final int BTN_ACCESS_BASE = 10; // 10..12
    private static final int BTN_FILTER_BASE = 20; // 20..21
    private static final int BTN_MATCH_BASE = 30; // 30..32
    private static final int BTN_PRIORITY_MINUS = 40;
    private static final int BTN_PRIORITY_PLUS = 41;
    private static final int BTN_CLEAR = 50;
    private static final int BTN_DONE = 51;

    private static final int ITEM_GRID_X = 12;
    private static final int FLUID_GRID_X = 92;
    private static final int GRID_Y = 126;
    private static final int CELL = 20;
    private static final int SLOT = 18;

    private final ContainerStorageBus container;

    /** 当前正在编辑的面；右键哪块板打开的就是哪一面。 */
    private int selectedSide;

    private int requestTimer;
    /** 界面开着时定期向服务端要一份权威值：目标容器可能被别人换掉 / 拆掉。 */
    private int syncTimer;

    public GuiStorageBus(ContainerStorageBus container) {
        super(container);
        this.container = container;
        this.selectedSide = container == null ? 0 : container.getSide();
        this.xSize = GUI_WIDTH;
        this.ySize = GUI_HEIGHT;
    }

    private final class FlatButton extends FutaMarkedButton {

        private FlatButton(int id, int x, int y, int width, int height) {
            super(id, x, y, width, height, "");
        }
    }

    // ==================================================================
    // 布局
    // ==================================================================

    @Override
    public void initGui() {
        super.initGui();
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        buttonList.clear();
        buttonList.add(new FlatButton(BTN_TARGET_PREV, guiLeft + 100, guiTop + 22, 18, 16));
        buttonList.add(new FlatButton(BTN_TARGET_NEXT, guiLeft + 142, guiTop + 22, 18, 16));

        for (int i = 0; i < 3; i++) {
            buttonList.add(new FlatButton(BTN_ACCESS_BASE + i, guiLeft + 54 + i * 52, guiTop + 58, 48, 16));
        }
        for (int i = 0; i < 2; i++) {
            buttonList.add(new FlatButton(BTN_FILTER_BASE + i, guiLeft + 54 + i * 52, guiTop + 80, 48, 16));
        }
        for (int i = 0; i < 3; i++) {
            buttonList.add(new FlatButton(BTN_MATCH_BASE + i, guiLeft + 154 + i * 34, guiTop + 80, 32, 16));
        }

        buttonList.add(new FlatButton(BTN_PRIORITY_MINUS, guiLeft + 54, guiTop + 102, 18, 16));
        buttonList.add(new FlatButton(BTN_PRIORITY_PLUS, guiLeft + 120, guiTop + 102, 18, 16));

        buttonList.add(new FlatButton(BTN_CLEAR, guiLeft + 12, guiTop + 190, 76, 16));
        buttonList.add(new FlatButton(BTN_DONE, guiLeft + 184, guiTop + 190, 60, 16));

        refreshLabels();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private boolean configReady() {
        if (container == null || container.getBus() == null) return false;
        TileEntityStorageBus bus = container.getBus();
        TileEntity tile = bus;
        int dimension = tile.getWorldObj() == null ? 0 : tile.getWorldObj().provider.dimensionId;
        return ClientStorageBus.matches(dimension, tile.xCoord, tile.yCoord, tile.zCoord)
            && ClientStorageBus.getSelectedSide() == selectedSide;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private static String directionName(ForgeDirection side) {
        if (side == null || side == ForgeDirection.UNKNOWN) return tr("futa_gtnh.gui.storage_bus.target.none");
        return tr(
            "futa_gtnh.gui.terminal.io.dir." + side.name()
                .toLowerCase(java.util.Locale.ROOT));
    }

    // ==================================================================
    // 数据 / 同步
    // ==================================================================

    private void pushConfig() {
        if (!configReady()) return;
        TileEntityStorageBus bus = container.getBus();
        try {
            NetworkHandler.INSTANCE.sendToServer(
                PacketStorageBusConfig.update(
                    bus.xCoord,
                    bus.yCoord,
                    bus.zCoord,
                    selectedSide,
                    ClientStorageBus.get()
                        .writeToNbt()));
        } catch (Throwable ignored) {
            // 下一帧会继续显示旧值；服务端没收到就不会改
        }
    }

    private void requestConfig() {
        TileEntityStorageBus bus = container.getBus();
        if (bus == null) return;
        try {
            NetworkHandler.INSTANCE
                .sendToServer(PacketStorageBusConfig.request(bus.xCoord, bus.yCoord, bus.zCoord, selectedSide));
        } catch (Throwable ignored) {
            // 等下一次重试
        }
    }

    /** 在已安装的面之间切换；不改目标面，目标面就是这条总线的身份。 */
    private void switchFace(int delta) {
        if (!configReady()) return;
        int next = ClientStorageBus.nextFace(selectedSide, delta);
        if (next == selectedSide) return;
        selectedSide = next;
        requestConfig();
        refreshLabels();
    }

    private void pushPriority(int delta) {
        StorageBusConfig config = ClientStorageBus.get();
        int step = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) ? 10 : 1;
        config.setPriority(config.getPriority() + delta * step);
        pushConfig();
        refreshLabels();
    }

    private void pushAccess(StorageBusConfig.AccessMode mode) {
        ClientStorageBus.get()
            .setAccess(mode);
        pushConfig();
        refreshLabels();
    }

    private void pushFilterMode(StorageBusConfig.FilterMode mode) {
        ClientStorageBus.get()
            .setFilterMode(mode);
        pushConfig();
        refreshLabels();
    }

    private void pushMatchMode(StorageBusConfig.MatchMode mode) {
        ClientStorageBus.get()
            .setMatchMode(mode);
        pushConfig();
        refreshLabels();
    }

    // ==================================================================
    // 每帧
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (container != null && container.getBus() != null) {
            TileEntityStorageBus bus = container.getBus();
            int dimension = bus.getWorldObj() == null ? 0 : bus.getWorldObj().provider.dimensionId;
            if (ClientStorageBus.matches(dimension, bus.xCoord, bus.yCoord, bus.zCoord)
                && !ClientStorageBus.hasFace(selectedSide)) {
                // 开着界面时这一条被拆了 / 服务端选了别的面：跟着服务端走
                selectedSide = ClientStorageBus.getSelectedSide();
            }
        }
        if (!configReady()) {
            if (++requestTimer >= 10) {
                requestTimer = 0;
                requestConfig();
            }
        } else if (++syncTimer >= 20) {
            syncTimer = 0;
            requestConfig();
        }
        refreshLabels();
    }

    private void refreshLabels() {
        StorageBusConfig config = ClientStorageBus.get();
        boolean ready = configReady();

        setLabel(BTN_TARGET_PREV, "←", ready && Integer.bitCount(ClientStorageBus.getFaceMask()) > 1);
        setLabel(BTN_TARGET_NEXT, "→", ready && Integer.bitCount(ClientStorageBus.getFaceMask()) > 1);

        String[] accessKeys = { "futa_gtnh.gui.storage_bus.access.read_write",
            "futa_gtnh.gui.storage_bus.access.read_only", "futa_gtnh.gui.storage_bus.access.write_only" };
        for (int i = 0; i < 3; i++) {
            GuiButton button = findButton(BTN_ACCESS_BASE + i);
            if (button == null) continue;
            button.displayString = tr(accessKeys[i]);
            button.enabled = ready;
            if (button instanceof FutaMarkedButton) {
                ((FutaMarkedButton) button).setMarked(
                    config.getAccess()
                        .ordinal() == i);
            }
        }

        String[] filterKeys = { "futa_gtnh.gui.storage_bus.filter.whitelist",
            "futa_gtnh.gui.storage_bus.filter.blacklist" };
        for (int i = 0; i < 2; i++) {
            GuiButton button = findButton(BTN_FILTER_BASE + i);
            if (button == null) continue;
            button.displayString = tr(filterKeys[i]);
            button.enabled = ready;
            if (button instanceof FutaMarkedButton) {
                ((FutaMarkedButton) button).setMarked(
                    config.getFilterMode()
                        .ordinal() == i);
            }
        }

        String[] matchKeys = { "futa_gtnh.gui.storage_bus.match.exact", "futa_gtnh.gui.storage_bus.match.fuzzy",
            "futa_gtnh.gui.storage_bus.match.ore" };
        for (int i = 0; i < 3; i++) {
            GuiButton button = findButton(BTN_MATCH_BASE + i);
            if (button == null) continue;
            button.displayString = tr(matchKeys[i]);
            button.enabled = ready;
            if (button instanceof FutaMarkedButton) {
                ((FutaMarkedButton) button).setMarked(
                    config.getMatchMode()
                        .ordinal() == i);
            }
        }

        setLabel(BTN_PRIORITY_MINUS, "-", ready);
        setLabel(BTN_PRIORITY_PLUS, "+", ready);
        setLabel(BTN_CLEAR, tr("futa_gtnh.gui.storage_bus.clear"), ready);
        setLabel(BTN_DONE, tr("futa_gtnh.gui.storage_bus.done"), true);
    }

    private void setLabel(int id, String label, boolean enabled) {
        GuiButton button = findButton(id);
        if (button == null) return;
        button.displayString = label;
        button.enabled = enabled;
    }

    private GuiButton findButton(int id) {
        for (Object object : buttonList) {
            if (object instanceof GuiButton && ((GuiButton) object).id == id) return (GuiButton) object;
        }
        return null;
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        FutaUiTheme.panel(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT, 0xFF808080);
        fontRendererObj.drawStringWithShadow(tr("futa_gtnh.gui.storage_bus.title"), guiLeft + 8, guiTop + 6, 0xFFFFFF);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (!configReady()) {
            fontRendererObj
                .drawStringWithShadow(tr("futa_gtnh.gui.storage_bus.syncing"), guiLeft + 8, guiTop + 96, 0xFFFF55);
            return;
        }

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);

        StorageBusConfig config = ClientStorageBus.get();

        // 标题 / 目标
        String target = tr("futa_gtnh.gui.storage_bus.target") + " "
            + EnumChatFormatting.YELLOW
            + directionName(ForgeDirection.getOrientation(selectedSide));
        fontRendererObj.drawStringWithShadow(target, guiLeft + 8, guiTop + 26, 0xFFFFFF);
        String targetName = ClientStorageBus.getTargetName();
        if (targetName.isEmpty()) {
            fontRendererObj
                .drawString(tr("futa_gtnh.gui.storage_bus.target.empty"), guiLeft + 8, guiTop + 40, 0xFF7070);
        } else {
            String trimmed = fontRendererObj.trimStringToWidth(targetName, 200);
            fontRendererObj.drawString(trimmed, guiLeft + 8, guiTop + 40, 0x909090);
        }
        drawTargetState();

        // 访问 / 优先级 / 筛选标题
        fontRendererObj.drawString(tr("futa_gtnh.gui.storage_bus.access"), guiLeft + 8, guiTop + 62, 0xC0C0C0);
        fontRendererObj.drawString(tr("futa_gtnh.gui.storage_bus.filter"), guiLeft + 8, guiTop + 84, 0xC0C0C0);
        fontRendererObj.drawString(tr("futa_gtnh.gui.storage_bus.priority"), guiLeft + 8, guiTop + 106, 0xC0C0C0);
        String priority = Integer.toString(config.getPriority());
        fontRendererObj
            .drawString(priority, guiLeft + 86 - fontRendererObj.getStringWidth(priority) / 2, guiTop + 106, 0xFFFF55);

        fontRendererObj
            .drawString(tr("futa_gtnh.gui.storage_bus.filter.items"), guiLeft + ITEM_GRID_X, guiTop + 114, 0xC0C0C0);
        fontRendererObj
            .drawString(tr("futa_gtnh.gui.storage_bus.filter.fluids"), guiLeft + FLUID_GRID_X, guiTop + 114, 0xC0C0C0);

        drawFilterGrid(itemSlots(), mouseX, mouseY, ITEM_GRID_X);
        drawFilterGrid(fluidSlots(), mouseX, mouseY, FLUID_GRID_X);

        fontRendererObj.drawString(tr("futa_gtnh.gui.storage_bus.filter.hint"), guiLeft + 164, guiTop + 126, 0x707070);
        fontRendererObj.drawString(tr("futa_gtnh.gui.storage_bus.filter.hint2"), guiLeft + 164, guiTop + 138, 0x707070);
        fontRendererObj
            .drawString(tr("futa_gtnh.gui.storage_bus.priority.hint"), guiLeft + 164, guiTop + 156, 0x707070);

        drawTooltips(mouseX, mouseY);
    }

    private void drawTargetState() {
        boolean items = ClientStorageBus.hasItemTarget();
        boolean fluids = ClientStorageBus.hasFluidTarget();
        String state;
        int color;
        if (items && fluids) {
            state = tr("futa_gtnh.gui.storage_bus.target.both");
            color = 0xA0FFA0;
        } else if (items) {
            state = tr("futa_gtnh.gui.storage_bus.target.items");
            color = 0xA0FFA0;
        } else if (fluids) {
            state = tr("futa_gtnh.gui.storage_bus.target.fluids");
            color = 0xA0FFA0;
        } else {
            state = tr("futa_gtnh.gui.storage_bus.target.invalid");
            color = 0xFF7070;
        }
        fontRendererObj
            .drawString(state, guiLeft + GUI_WIDTH - 8 - fontRendererObj.getStringWidth(state), guiTop + 26, color);
    }

    /** @return 9 个物品筛选槽当前显示的物品栈（空槽是 null） */
    private ItemStack[] itemSlots() {
        StorageBusConfig config = ClientStorageBus.get();
        ItemStack[] result = new ItemStack[StorageBusConfig.FILTER_SLOTS];
        for (int i = 0; i < result.length; i++) {
            ItemKey key = config.getItemFilter(i);
            if (key != null) result[i] = key.prototype();
        }
        return result;
    }

    private ItemStack[] fluidSlots() {
        StorageBusConfig config = ClientStorageBus.get();
        ItemStack[] result = new ItemStack[StorageBusConfig.FILTER_SLOTS];
        for (int i = 0; i < result.length; i++) {
            FluidKey key = config.getFluidFilter(i);
            if (key != null) result[i] = fluidDisplay(key);
        }
        return result;
    }

    private static ItemStack fluidDisplay(FluidKey key) {
        try {
            return gregtech.api.util.GTUtility.getFluidDisplayStack(key.prototype(1000), true);
        } catch (Throwable t) {
            return null;
        }
    }

    private void drawFilterGrid(ItemStack[] stacks, int mouseX, int mouseY, int gridX) {
        int gridY = guiTop + GRID_Y;
        for (int i = 0; i < StorageBusConfig.FILTER_SLOTS; i++) {
            int x = guiLeft + gridX + (i % 3) * CELL;
            int y = gridY + (i / 3) * CELL;
            boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
            FutaUiTheme.slot(x, y);
            if (hovered) drawBorder(x - 1, y - 1, SLOT + 2, SLOT + 2, 0xFFFFFFFF);
            ItemStack stack = stacks[i];
            if (stack != null) {
                RenderHelper.enableGUIStandardItemLighting();
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x + 1, y + 1);
                RenderHelper.disableStandardItemLighting();
            }
        }
    }

    private void drawTooltips(int mouseX, int mouseY) {
        int itemIndex = filterIndexAt(mouseX, mouseY, ITEM_GRID_X);
        if (itemIndex >= 0) {
            ItemStack stack = itemSlots()[itemIndex];
            if (stack != null) {
                drawHoveringText(
                    stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips),
                    mouseX,
                    mouseY,
                    fontRendererObj);
            } else {
                drawHoveringText(
                    java.util.Arrays.asList(tr("futa_gtnh.gui.storage_bus.filter.set_item")),
                    mouseX,
                    mouseY,
                    fontRendererObj);
            }
            return;
        }

        int fluidIndex = filterIndexAt(mouseX, mouseY, FLUID_GRID_X);
        if (fluidIndex >= 0) {
            FluidKey key = ClientStorageBus.get()
                .getFluidFilter(fluidIndex);
            List<String> tooltip = new ArrayList<>();
            if (key != null) {
                try {
                    tooltip.add(
                        key.getFluid()
                            .getLocalizedName(key.prototype(1000)));
                } catch (Throwable t) {
                    tooltip.add(
                        key.getFluid()
                            .getName());
                }
            } else {
                tooltip.add(tr("futa_gtnh.gui.storage_bus.filter.set_fluid"));
            }
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
            return;
        }

        if (mouseX >= guiLeft + 50 && mouseX < guiLeft + 142 && mouseY >= guiTop + 100 && mouseY < guiTop + 118) {
            drawHoveringText(
                java.util.Arrays.asList(
                    tr("futa_gtnh.gui.storage_bus.priority.tip1"),
                    tr("futa_gtnh.gui.storage_bus.priority.tip2")),
                mouseX,
                mouseY,
                fontRendererObj);
        }
    }

    private int filterIndexAt(int mouseX, int mouseY, int gridX) {
        int x0 = guiLeft + gridX;
        int y0 = guiTop + GRID_Y;
        if (mouseX < x0 || mouseY < y0) return -1;
        int col = (mouseX - x0) / CELL;
        int row = (mouseY - y0) / CELL;
        if (col < 0 || col >= 3 || row < 0 || row >= 3) return -1;
        if ((mouseX - x0) % CELL >= SLOT || (mouseY - y0) % CELL >= SLOT) return -1;
        return row * 3 + col;
    }

    private void drawBorder(int x, int y, int width, int height, int color) {
        drawRect(x, y, x + width, y + 1, color);
        drawRect(x, y + height - 1, x + width, y + height, color);
        drawRect(x, y, x + 1, y + height, color);
        drawRect(x + width - 1, y, x + width, y + height, color);
    }

    // ==================================================================
    // 输入
    // ==================================================================

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_DONE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (!configReady()) return;

        if (button.id == BTN_TARGET_PREV) {
            switchFace(-1);
            return;
        }
        if (button.id == BTN_TARGET_NEXT) {
            switchFace(1);
            return;
        }
        if (button.id >= BTN_ACCESS_BASE && button.id < BTN_ACCESS_BASE + 3) {
            pushAccess(StorageBusConfig.AccessMode.values()[button.id - BTN_ACCESS_BASE]);
            return;
        }
        if (button.id >= BTN_FILTER_BASE && button.id < BTN_FILTER_BASE + 2) {
            pushFilterMode(StorageBusConfig.FilterMode.values()[button.id - BTN_FILTER_BASE]);
            return;
        }
        if (button.id >= BTN_MATCH_BASE && button.id < BTN_MATCH_BASE + 3) {
            pushMatchMode(StorageBusConfig.MatchMode.values()[button.id - BTN_MATCH_BASE]);
            return;
        }
        if (button.id == BTN_PRIORITY_MINUS) {
            pushPriority(-1);
            return;
        }
        if (button.id == BTN_PRIORITY_PLUS) {
            pushPriority(1);
            return;
        }
        if (button.id == BTN_CLEAR) {
            ClientStorageBus.get()
                .clearFilters();
            pushConfig();
            refreshLabels();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (!configReady() || (mouseButton != 0 && mouseButton != 1)) return;

        int itemIndex = filterIndexAt(mouseX, mouseY, ITEM_GRID_X);
        if (itemIndex >= 0) {
            ItemStack cursor = mc.thePlayer.inventory.getItemStack();
            StorageBusConfig config = ClientStorageBus.get();
            ItemKey key = mouseButton == 0 && cursor != null ? ItemKey.of(cursor) : null;
            config.setItemFilter(itemIndex, key);
            pushConfig();
            refreshLabels();
            return;
        }

        int fluidIndex = filterIndexAt(mouseX, mouseY, FLUID_GRID_X);
        if (fluidIndex >= 0) {
            ItemStack cursor = mc.thePlayer.inventory.getItemStack();
            StorageBusConfig config = ClientStorageBus.get();
            FluidKey key = mouseButton == 0 && cursor != null ? StorageBusConfig.fluidKeyOf(cursor) : null;
            if (mouseButton == 0 && cursor != null && key == null) {
                // 光标上不是流体容器：不要误清掉已有筛选，给玩家一个提示即可
                return;
            }
            config.setFluidFilter(fluidIndex, key);
            pushConfig();
            refreshLabels();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }
}
