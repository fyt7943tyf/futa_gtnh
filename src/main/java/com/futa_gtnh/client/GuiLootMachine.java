package com.futa_gtnh.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TileEntityLootMachine;
import com.futa_gtnh.inventory.ContainerLootMachine;
import com.futa_gtnh.lootbag.EnhancedLootBagsCompat;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLootMachineAction;

/**
 * 自选抽奖机界面。
 *
 * <p>
 * 上：袋子/书两个输入槽 + 时运与剩余次数；中：9×5 的「模拟出货」展示区
 * （不是真槽位，画的是 {@code PacketLootMachineResult} 同步来的服务端结果，
 * 悬浮显示该物品在当前时运等级下的掉率，与 NEI 的掉率提示同一套算法）；
 * 出货区下面是<b>历史轮次切换</b>（最多 3 轮，任选一轮领取），再下面是
 * 打开/刷新、领取、重置次数三个按钮 + 技术员代币余额 + 玩家背包。
 *
 * <p>
 * 按钮只负责发请求包，能不能点、点了以后发生什么，全部以服务端回执为准。
 * 「看哪一轮」是纯客户端视图状态（{@link ClientLootMachineState#setSelectedRound}），
 * 「领哪一轮」随领取包发给服务端再校验。
 *
 * <p>
 * <b>坐标约定</b>：{@code GuiContainer} 的按钮坐标是<b>屏幕坐标</b>，
 * 必须加 {@code guiLeft/guiTop} 偏移；而背景/前景两层绘制是 GUI 相对坐标。
 * 混用会得到「按钮贴在屏幕左上角、面板却居中」的错位界面。
 */
public class GuiLootMachine extends GuiContainer {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        FutaGtnhMod.MODID,
        "textures/gui/loot_machine.png");

    // 出货网格 9x4=36 格。GUI 贴图画布是 256x256（原版约定），面板高度压在 256
    // 以内，网格因此从 5 行缩到 4 行；超出的结果走「…还有 N 组」截断提示。
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 4;
    private static final int GRID_CELLS = GRID_COLS * GRID_ROWS;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 44;

    private static final int BUTTON_ROLL = 1;
    private static final int BUTTON_CLAIM = 2;
    private static final int BUTTON_RESET = 3;
    private static final int BUTTON_ROUND_BASE = 10;

    /**
     * 两行 14px 按钮的布局（GUI 相对坐标），节奏对齐共享终端界面：
     * 第一行 = 历史轮次切换 + 打开/刷新；第二行 = 领取 + 重置次数。
     */
    private static final int ROUND_ROW_Y = 119;
    private static final int ROUND_BUTTON_WIDTH = 36;
    private static final int ROUND_BUTTON_HEIGHT = 14;
    private static final int ACTION_ROW_Y = 136;
    private static final int TOKEN_LINE_Y = 154;
    /** 状态两行文字的起始 x（在书槽右侧，留 6px 间距）。 */
    private static final int STATUS_X = 52;
    /** 玩家背包标签（原版「物品栏」）的 y，画在背包槽上方 11px 处。 */
    private static final int INV_LABEL_Y = 165;

    private final ContainerLootMachine container;

    public GuiLootMachine(InventoryPlayer playerInventory, TileEntityLootMachine machine) {
        super(new ContainerLootMachine(playerInventory, machine));
        this.container = (ContainerLootMachine) inventorySlots;
        xSize = ContainerLootMachine.GUI_WIDTH;
        ySize = ContainerLootMachine.GUI_HEIGHT;
    }

    @Override
    public void initGui() {
        super.initGui();

        for (int i = 0; i < TileEntityLootMachine.ROLLS_PER_BAG; i++) {
            buttonList.add(
                new GuiSmallButton(
                    BUTTON_ROUND_BASE + i,
                    guiLeft + 7 + i * (ROUND_BUTTON_WIDTH + 2),
                    guiTop + ROUND_ROW_Y,
                    ROUND_BUTTON_WIDTH,
                    ROUND_BUTTON_HEIGHT,
                    ""));
        }
        buttonList.add(
            new GuiSmallButton(
                BUTTON_ROLL,
                guiLeft + 123,
                guiTop + ROUND_ROW_Y,
                46,
                ROUND_BUTTON_HEIGHT,
                StatCollector.translateToLocal("futa_gtnh.loot_machine.gui.open")));
        buttonList.add(
            new GuiSmallButton(
                BUTTON_CLAIM,
                guiLeft + 7,
                guiTop + ACTION_ROW_Y,
                36,
                ROUND_BUTTON_HEIGHT,
                StatCollector.translateToLocal("futa_gtnh.loot_machine.gui.claim")));
        buttonList.add(
            new GuiSmallButton(
                BUTTON_RESET,
                guiLeft + 45,
                guiTop + ACTION_ROW_Y,
                62,
                ROUND_BUTTON_HEIGHT,
                StatCollector.translateToLocal("futa_gtnh.loot_machine.gui.reset")));
    }

    /** 按钮文案/可用性随服务端回执和槽位内容变化，每帧刷新。 */
    @Override
    public void updateScreen() {
        super.updateScreen();

        int rollsUsed = ClientLootMachineState.getRollsUsed();
        int rollsLeft = ClientLootMachineState.getRollsMax() - rollsUsed;
        boolean hasBag = EnhancedLootBagsCompat.isLootBag(getMachineStack(TileEntityLootMachine.SLOT_BAG));

        for (Object buttonObject : buttonList) {
            GuiSmallButton button = (GuiSmallButton) buttonObject;
            if (button.id >= BUTTON_ROUND_BASE && button.id < BUTTON_ROUND_BASE + TileEntityLootMachine.ROLLS_PER_BAG) {
                int roundIndex = button.id - BUTTON_ROUND_BASE;
                String label = StatCollector
                    .translateToLocalFormatted("futa_gtnh.loot_machine.gui.round", roundIndex + 1);
                // 选中轮加 "* " 前缀（ASCII 星号，任何字体都有）
                button.displayString = (roundIndex == ClientLootMachineState.getSelectedRound() ? "* " : "") + label;
                button.enabled = roundIndex < rollsUsed;
                continue;
            }

            switch (button.id) {
                case BUTTON_ROLL:
                    if (ClientLootMachineState.hasResult()) {
                        button.displayString = StatCollector
                            .translateToLocalFormatted("futa_gtnh.loot_machine.gui.refresh", rollsLeft);
                    } else {
                        button.displayString = StatCollector.translateToLocal("futa_gtnh.loot_machine.gui.open");
                    }
                    button.enabled = hasBag && rollsLeft > 0;
                    break;
                case BUTTON_CLAIM:
                    button.enabled = ClientLootMachineState.hasResult() && hasBag;
                    break;
                case BUTTON_RESET:
                    button.enabled = rollsLeft <= 0 && hasBag
                        && ClientLootMachineState.isTokenAvailable()
                        && ClientLootMachineState.getTokenBalance() >= resetCost();
                    break;
                default:
            }
        }
    }

    /** 机器槽在客户端的镜像（容器同步会把服务端槽位写进客户端的方块实体）。 */
    private ItemStack getMachineStack(int machineSlot) {
        return container.getMachine()
            .getStackInSlot(machineSlot);
    }

    private int resetCost() {
        int fortune = Math.max(1, currentFortune());
        return fortune * fortune;
    }

    private int currentFortune() {
        int fortune = EnhancedLootBagsCompat.getFortuneLevel(getMachineStack(TileEntityLootMachine.SLOT_BOOK));
        ItemStack bag = getMachineStack(TileEntityLootMachine.SLOT_BAG);
        if (bag != null) fortune = Math.max(fortune, EnhancedLootBagsCompat.getFortuneLevel(bag));
        return fortune;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;

        if (button.id >= BUTTON_ROUND_BASE && button.id < BUTTON_ROUND_BASE + TileEntityLootMachine.ROLLS_PER_BAG) {
            // 纯视图切换：不走服务端，只改本地选中
            ClientLootMachineState.setSelectedRound(button.id - BUTTON_ROUND_BASE);
            return;
        }

        byte action;
        if (button.id == BUTTON_ROLL) {
            action = PacketLootMachineAction.ACTION_ROLL;
        } else if (button.id == BUTTON_CLAIM) {
            action = PacketLootMachineAction.ACTION_CLAIM;
        } else if (button.id == BUTTON_RESET) {
            action = PacketLootMachineAction.ACTION_RESET;
        } else {
            return;
        }
        // 领取的轮次下标随包发送；其余动作用 0 占位（服务端不读）
        NetworkHandler.INSTANCE
            .sendToServer(new PacketLootMachineAction(action, (byte) ClientLootMachineState.getSelectedRound()));
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager()
            .bindTexture(TEXTURE);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 标题与「物品栏」标签都按原版容器（GuiChest）的样式：左对齐、深灰、无阴影
        fontRendererObj.drawString(StatCollector.translateToLocal("container.futa_gtnh.loot_machine"), 8, 6, 0x404040);

        // 输入槽右边的两行状态：时运等级 / 剩余机会（槽位格 y=22..40，文字贴右排）
        int fortune = currentFortune();
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("futa_gtnh.loot_machine.gui.fortune", roman(fortune)),
            STATUS_X,
            24,
            fortune > 0 ? 0x2C5BA8 : 0x404040);

        int rollsLeft = ClientLootMachineState.getRollsMax() - ClientLootMachineState.getRollsUsed();
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted(
                "futa_gtnh.loot_machine.gui.rolls",
                rollsLeft,
                ClientLootMachineState.getRollsMax()),
            STATUS_X,
            34,
            rollsLeft > 0 ? 0x2E6B34 : 0xA03030);

        // 代币行：余额 +（重置费用）。VendingMachine 缺席时提示代币不可用
        String tokenLine;
        if (ClientLootMachineState.isTokenAvailable()) {
            tokenLine = StatCollector
                .translateToLocalFormatted("futa_gtnh.loot_machine.gui.token", ClientLootMachineState.getTokenBalance())
                + " "
                + EnumChatFormatting.GRAY
                + StatCollector.translateToLocalFormatted("futa_gtnh.loot_machine.gui.token_cost", resetCost());
        } else {
            tokenLine = StatCollector.translateToLocal("futa_gtnh.loot_machine.gui.token") + " "
                + EnumChatFormatting.RED
                + StatCollector.translateToLocal("futa_gtnh.loot_machine.gui.token_unavailable");
        }
        fontRendererObj.drawString(tokenLine, 8, TOKEN_LINE_Y, 0x404040);

        // 玩家背包标签（原版文案「物品栏」）；截断提示挂在同一行右对齐
        String invLabel = StatCollector.translateToLocal("container.inventory");
        fontRendererObj.drawString(invLabel, 8, INV_LABEL_Y, 0x404040);

        ItemStack[] stacks = ClientLootMachineState.getSelectedStacks();
        if (stacks.length > GRID_CELLS) {
            String truncation = EnumChatFormatting.RED + StatCollector
                .translateToLocalFormatted("futa_gtnh.loot_machine.gui.truncated", stacks.length - GRID_CELLS);
            fontRendererObj
                .drawString(truncation, xSize - 8 - fontRendererObj.getStringWidth(truncation), INV_LABEL_Y, 0x404040);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);

        drawResultGrid();
        drawResultTooltip(mouseX, mouseY);
    }

    /** 画当前选中轮的「模拟出货」。在 super.drawScreen 之后画，保证盖在槽位底图上。 */
    private void drawResultGrid() {
        ItemStack[] stacks = ClientLootMachineState.getSelectedStacks();
        if (stacks.length == 0) return;

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.zLevel = 100.0F;
        for (int i = 0; i < stacks.length && i < GRID_CELLS; i++) {
            if (stacks[i] == null) continue;
            int column = i % GRID_COLS;
            int row = i / GRID_COLS;
            int x = guiLeft + GRID_X + column * 18 + 1;
            int y = guiTop + GRID_Y + row * 18 + 1;
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stacks[i], x, y);
            itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), stacks[i], x, y);
        }
        itemRender.zLevel = 0.0F;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        RenderHelper.disableStandardItemLighting();
    }

    @SuppressWarnings("unchecked")
    private void drawResultTooltip(int mouseX, int mouseY) {
        ItemStack[] stacks = ClientLootMachineState.getSelectedStacks();
        double[] chances = ClientLootMachineState.getSelectedChances();
        if (stacks.length == 0) return;

        int column = (mouseX - guiLeft - GRID_X) / 18;
        int row = (mouseY - guiTop - GRID_Y) / 18;
        if (column < 0 || column >= GRID_COLS || row < 0 || row >= GRID_ROWS) return;
        if (!isInCell(mouseX, mouseY, column, row)) return;

        int index = row * GRID_COLS + column;
        if (index >= stacks.length || stacks[index] == null) return;

        List<String> lines = stacks[index].getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
        if (index < chances.length && chances[index] >= 0.0D) {
            lines.add(
                EnumChatFormatting.GOLD + StatCollector.translateToLocalFormatted(
                    "futa_gtnh.loot_machine.gui.drop_chance",
                    String.format(Locale.ROOT, "%.2f", chances[index])));
        }
        drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
    }

    /** (mouseX - x)/18 对负数会取到 -0，用矩形判定兜一层。 */
    private boolean isInCell(int mouseX, int mouseY, int column, int row) {
        int x = guiLeft + GRID_X + column * 18;
        int y = guiTop + GRID_Y + row * 18;
        return mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
    }

    private static String roman(int level) {
        switch (level) {
            case 1:
                return "I";
            case 2:
                return "II";
            case 3:
                return "III";
            default:
                return String.valueOf(level);
        }
    }
}
