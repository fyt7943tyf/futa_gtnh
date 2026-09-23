package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.ItemKey;

/**
 * 匠魂工作站界面下方的「共享存储」面板 —— 相当于在合成站旁边放了一个连通全服存储的箱子。
 *
 * <p>
 * 位置：贴着界面的正下方，宽度和界面一致（9 列 × 18px = 162），行数按屏幕剩余高度自适应
 * （GUI scale 大时只放得下一两行，所以面板自带搜索框，一页十几个也够用）。
 *
 * <p>
 * 交互（和箱子一致）：左键 = 取到<b>光标</b>上；Shift + 左键 = 取一整叠直接进背包；
 * 右键 = 取半叠到光标；光标上拿着东西时左键 = 那一叠存回共享存储，右键 = 存 1 个。
 *
 * <p>
 * <b>输入是怎么拦下来的</b>：1.7.10 的 Forge 没有可取消的鼠标/键盘事件，所以点击与键入
 * 交给 NEI 的 {@code IContainerInputHandler}（返回 true 即消费掉，底层界面收不到）——
 * 见 {@code nei/StoragePanelInput}。没有 NEI 时这个面板不启用（否则点击会漏给底下的界面，
 * 点在界面外会把手上的东西丢地上）。
 */
public final class StoragePanel {

    private static final int COLS = 9;
    private static final int SLOT = 18;
    private static final int MAX_ROWS = 4;
    private static final int SEARCH_H = 14;

    private static final StoragePanel INSTANCE = new StoragePanel();

    public static StoragePanel get() {
        return INSTANCE;
    }

    private final List<StorageViewEntry> filtered = new ArrayList<>();

    private GuiTextField search;
    private boolean focused;
    private int page;
    private int revision = -1;
    private String lastQuery = "\u0000";

    /** 这一帧算出来的几何信息，点击判定用 */
    private boolean visible;
    private GuiScreen lastScreen;
    private int x;
    private int y;
    private int width;
    private int rows;

    private RenderItem itemRender;

    private StoragePanel() {}

    // ==================================================================
    // 绘制
    // ==================================================================

    public void draw(GuiScreen screen, int mouseX, int mouseY) {
        visible = false;
        if (!(screen instanceof GuiContainer)) return;
        if (!TinkersScreens.isWorkstation(screen)) return;
        if (!ClientStorageCache.isReady()) return;

        GuiContainer gui = (GuiContainer) screen;
        layout(gui, screen);

        // 背景 + 边框（贴近原版箱子配色）
        fill(x - 2, y - 2, x + width + 2, y + rows * SLOT + SEARCH_H + 4, 0xFFC6C6C6);
        border(x - 2, y - 2, x + width + 2, y + rows * SLOT + SEARCH_H + 4, 0xFF555555);

        if (search == null) {
            search = new GuiTextField(
                net.minecraft.client.Minecraft.getMinecraft().fontRenderer,
                x + 2,
                y + 2,
                width - 4,
                SEARCH_H - 4);
            search.setMaxStringLength(64);
        }
        search.xPosition = x + 2;
        search.yPosition = y + 2;
        search.width = width - 4;
        search.setFocused(focused);
        search.drawTextBox();
        if (search.getText()
            .isEmpty()) {
            net.minecraft.client.Minecraft.getMinecraft().fontRenderer
                .drawStringWithShadow(StatCollector.translateToLocal("futa_gtnh.panel.search"), x + 5, y + 5, 0x808080);
        }

        rebuildIfNeeded();

        int gridY = y + SEARCH_H;
        int start = page * COLS * rows;
        for (int i = 0; i < COLS * rows; i++) {
            int index = start + i;
            int col = i % COLS;
            int row = i / COLS;
            int sx = x + col * SLOT;
            int sy = gridY + row * SLOT;
            slotBackground(sx, sy);

            if (index >= filtered.size()) continue;
            ItemStack stack = filtered.get(index)
                .getDisplay();
            if (stack == null) continue;
            drawStack(stack, sx + 1, sy + 1);
        }

        // 页码 + 提示
        int totalPages = Math.max(1, (filtered.size() + COLS * rows - 1) / (COLS * rows));
        net.minecraft.client.Minecraft.getMinecraft().fontRenderer.drawString(
            StatCollector.translateToLocalFormatted("futa_gtnh.panel.page", page + 1, totalPages, filtered.size()),
            x + 2,
            y + rows * SLOT + SEARCH_H + 2,
            0x404040);

        visible = true;
        lastScreen = screen;
    }

    private void layout(GuiContainer gui, GuiScreen screen) {
        width = COLS * SLOT;
        // guiLeft / guiTop / xSize / ySize 在 GuiContainer 里都是 protected，跨包读不到，
        // 反射读一次就够（结果缓存）。开发环境是 MCP 名，正式 jar 经过 reobf 之后是 SRG 名，
        // 两个都试一遍。
        int guiLeft = Accessors.intField(gui, "guiLeft", "field_147003_i");
        int guiTop = Accessors.intField(gui, "guiTop", "field_147009_r");
        int guiW = Accessors.intField(gui, "xSize", "net/minecraft/client/gui/inventory/GuiContainer/field_146999_f");
        int guiH = Accessors.intField(gui, "ySize", "net/minecraft/client/gui/inventory/GuiContainer/field_147000_g");
        if (guiW <= 0) guiW = 176;
        if (guiH <= 0) guiH = 166;
        x = guiLeft + (guiW - width) / 2;
        // 界面下方有多少地方就放几行；不够两行时压在世界区域上也要给两行
        int below = screen.height - (guiTop + guiH);
        rows = Math.max(2, Math.min(MAX_ROWS, (below - SEARCH_H - 6) / SLOT));
        y = guiTop + guiH + 2;
        if (y + rows * SLOT + SEARCH_H + 4 > screen.height) {
            y = Math.max(0, screen.height - (rows * SLOT + SEARCH_H + 6));
        }
    }

    private void rebuildIfNeeded() {
        String query = search == null ? "" : search.getText();
        int now = ClientStorageCache.getRevision();
        if (now == revision && query.equals(lastQuery)) return;
        revision = now;
        lastQuery = query;

        filtered.clear();
        StorageSearch.filter(ClientStorageCache.items(), StorageSearch.compile(query), filtered);

        int perPage = COLS * rows;
        int maxPage = Math.max(0, (filtered.size() - 1) / perPage);
        if (page > maxPage) page = maxPage;
    }

    /** 这个界面里现在有没有画出面板（输入钩子先问它，避免误吃别的事件）。 */
    public boolean isShownIn(GuiContainer gui) {
        return visible && gui == lastScreen;
    }

    // ==================================================================
    // 输入（都由 NEI 的 IContainerInputHandler 转发过来）
    // ==================================================================

    /** @return true 表示这次点击被面板吃掉了，底层界面不该再看到 */
    public boolean mouseClicked(GuiScreen screen, int mouseX, int mouseY, int button) {
        if (!visible) return false;

        if (insideSearch(mouseX, mouseY)) {
            focused = search != null;
            if (search != null) search.mouseClicked(mouseX, mouseY, button);
            focused = search != null && search.isFocused();
            return true;
        }

        StorageViewEntry entry = entryAt(mouseX, mouseY);
        if (entry == null) {
            // 面板空白处点一下：退出输入状态（否则键盘一直被搜索框吃着）
            focused = false;
            return insidePanel(mouseX, mouseY);
        }
        if (entry.isFluid()) return true; // 面板只管物品，流体请用终端

        ItemKey key = entry.getItemKey();
        if (key == null) return true;

        ItemStack cursor = net.minecraft.client.Minecraft.getMinecraft().thePlayer.inventory.getItemStack();
        if (cursor != null) {
            // 光标上拿着东西 → 存回共享存储（箱子语义）
            if (button == 0) {
                NetworkHandler.INSTANCE
                    .sendToServer(new PacketStorageAction(PacketStorageAction.DEPOSIT_CURSOR).withAmount(0L));
            } else if (button == 1) {
                NetworkHandler.INSTANCE
                    .sendToServer(new PacketStorageAction(PacketStorageAction.DEPOSIT_CURSOR).withAmount(1L));
            }
            return true;
        }

        long amount;
        if (button == 1) {
            amount = 32L; // 右键取半叠
        } else if (isShiftDown()) {
            // Shift + 左键：整叠直接进背包（和箱子一致）
            NetworkHandler.INSTANCE.sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_ITEM, key, 64L));
            return true;
        } else {
            amount = 1L;
        }

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_TO_CURSOR, key, amount));
        return true;
    }

    public boolean mouseScrolled(GuiScreen screen, int dir) {
        if (!visible) return false;
        int perPage = COLS * rows;
        int maxPage = Math.max(0, (filtered.size() - 1) / perPage);
        int next = page + (dir > 0 ? -1 : 1);
        if (next < 0) next = 0;
        if (next > maxPage) next = maxPage;
        if (next != page) {
            page = next;
            return true;
        }
        return insidePanel(mouseXOf(screen), mouseYOf(screen)) || focused;
    }

    /** @return true 表示这个按键被搜索框吃掉了 */
    public boolean keyTyped(GuiScreen screen, char typed, int keyCode) {
        if (!visible || !focused || search == null) return false;

        if (keyCode == 1) { // Esc：先退出输入状态，不关界面
            focused = false;
            search.setFocused(false);
            return true;
        }
        if (keyCode == 28 || keyCode == 156) { // 回车：收起键盘
            focused = false;
            search.setFocused(false);
            return true;
        }
        if (search.textboxKeyTyped(typed, keyCode)) return true;
        // 把「可能被原版当成快捷键」的字符也吃掉：聚焦时就该只在输入框里打字
        return keyCode != 0 || typed >= 32;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private StorageViewEntry entryAt(int mouseX, int mouseY) {
        if (!visible) return null;
        int gridY = y + SEARCH_H;
        if (mouseX < x || mouseX >= x + width || mouseY < gridY || mouseY >= gridY + rows * SLOT) return null;

        int col = (mouseX - x) / SLOT;
        int row = (mouseY - gridY) / SLOT;
        int index = page * COLS * rows + row * COLS + col;
        if (index < 0 || index >= filtered.size()) return null;
        return filtered.get(index);
    }

    private boolean insidePanel(int mouseX, int mouseY) {
        return mouseX >= x - 2 && mouseX < x + width + 2 && mouseY >= y - 2 && mouseY < y + rows * SLOT + SEARCH_H + 4;
    }

    private boolean insideSearch(int mouseX, int mouseY) {
        return mouseX >= x + 2 && mouseX < x + width - 2 && mouseY >= y + 2 && mouseY < y + SEARCH_H;
    }

    private static int mouseXOf(GuiScreen screen) {
        return org.lwjgl.input.Mouse.getEventX() * screen.width
            / net.minecraft.client.Minecraft.getMinecraft().displayWidth;
    }

    private static int mouseYOf(GuiScreen screen) {
        return screen.height
            - org.lwjgl.input.Mouse.getEventY() * screen.height
                / net.minecraft.client.Minecraft.getMinecraft().displayHeight
            - 1;
    }

    private static boolean isShiftDown() {
        return org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
    }

    private void drawStack(ItemStack stack, int sx, int sy) {
        if (itemRender == null) itemRender = new RenderItem();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.renderItemAndEffectIntoGUI(
            net.minecraft.client.Minecraft.getMinecraft().fontRenderer,
            net.minecraft.client.Minecraft.getMinecraft()
                .getTextureManager(),
            stack,
            sx,
            sy);
        itemRender.renderItemOverlayIntoGUI(
            net.minecraft.client.Minecraft.getMinecraft().fontRenderer,
            net.minecraft.client.Minecraft.getMinecraft()
                .getTextureManager(),
            stack,
            sx,
            sy);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    private void slotBackground(int sx, int sy) {
        fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);
        fill(sx, sy, sx + 16, sy + 1, 0xFF373737);
        fill(sx, sy, sx + 1, sy + 16, 0xFF373737);
        fill(sx, sy + 15, sx + 16, sy + 16, 0xFFFFFFFF);
        fill(sx + 15, sy, sx + 16, sy + 16, 0xFFFFFFFF);
    }

    private static void fill(int x1, int y1, int x2, int y2, int argb) {
        float a = (argb >>> 24) / 255.0F;
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertex(x1, y2, 0.0D);
        tess.addVertex(x2, y2, 0.0D);
        tess.addVertex(x2, y1, 0.0D);
        tess.addVertex(x1, y1, 0.0D);
        tess.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void border(int x1, int y1, int x2, int y2, int argb) {
        fill(x1, y1, x2, y1 + 1, argb);
        fill(x1, y2 - 1, x2, y2, argb);
        fill(x1, y1, x1 + 1, y2, argb);
        fill(x2 - 1, y1, x2, y2, argb);
    }
}
