package com.futa_gtnh.client.widget;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

/**
 * 本模组统一的搜索输入框。
 *
 * <p>
 * 以前共享背包、寻物魔杖、小游戏助手、合成站存储面板各自 new 一个裸的
 * {@link GuiTextField}，四份代码长着四套大同小异的「建框 + 聚焦 + 失焦」逻辑，
 * 行为还各有细微出入。这个子类把它们收拢成一个组件，统一了三件事：
 *
 * <ul>
 * <li><b>右键清空</b>：框内右键 = 聚焦并清空（和 NEI 自带搜索条、AE2 终端
 * 同一个手感）；框外右键仍走原版的失焦逻辑。</li>
 * <li><b>变更回调</b>：{@link #setChangeListener} 注册的监听会在文本真正变化时
 * 收到通知 —— 无论变化来自键入、清空还是程序写入；光标移动这类不改变文本的
 * 按键不会触发。调用方不再需要自己在 keyTyped 里猜「这次到底改没改词」。</li>
 * <li><b>统一默认</b>：最长 64 字符、不画自带背景（各界面自己描边/画提示）。</li>
 * </ul>
 *
 * <p>
 * <b>为什么回调要在两个口子上各拦一遍</b>：1.7.10 的 {@code GuiTextField} 输入
 * 字符时是<b>直接改内部字段</b>、不经过 {@link #setText}，退格/删除才走
 * {@code setText}。所以 {@code textboxKeyTyped} 与 {@code setText} 都要包一层
 * 前后对比，才不会漏掉某条路径上的变化。
 */
public class FutaSearchField extends GuiTextField {

    /** 文本变化回调（客户端线程同步调用，别在这里做重活）。 */
    public interface TextChangeListener {

        void onSearchTextChanged(String newText);
    }

    private TextChangeListener changeListener;

    /** 程序性写文本（恢复上次的搜索词）时置位：那不是用户输入，不该触发回调。 */
    private boolean suspendEvents;

    /** 框高。1.7.10 的 GuiTextField 上这个字段的映射名不可靠，自己存一份。 */
    private final int boxHeight;

    public FutaSearchField(FontRenderer fontRendererObj, int x, int y, int width, int height) {
        super(fontRendererObj, x, y, width, height);
        this.boxHeight = height;
        setMaxStringLength(64);
        setEnableBackgroundDrawing(false);
    }

    public void setChangeListener(TextChangeListener listener) {
        this.changeListener = listener;
    }

    /**
     * 程序性写文本。{@code notifyListener=false} 用于「恢复上一次的词」这类
     * 非用户输入 —— 不触发变更回调，界面也就不会平白多刷一遍列表。
     */
    public void setText(String text, boolean notifyListener) {
        if (notifyListener) {
            setText(text);
            return;
        }
        suspendEvents = true;
        try {
            setText(text);
        } finally {
            suspendEvents = false;
        }
    }

    @Override
    public void setText(String text) {
        String next = text == null ? "" : text;
        boolean changed = !getText().equals(next);
        super.setText(next);
        if (changed) fireChange();
    }

    @Override
    public boolean textboxKeyTyped(char typedChar, int keyCode) {
        String before = getText();
        boolean consumed = super.textboxKeyTyped(typedChar, keyCode);
        // 方向键/翻页键也会「消费」事件，但不改文本 —— 不算搜索词变了
        if (consumed && !before.equals(getText())) fireChange();
        return consumed;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (contains(mouseX, mouseY) && mouseButton == 1) {
            // 右键清空：焦点留在框里，方便接着输入新词
            setFocused(true);
            if (!getText().isEmpty()) setText("");
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    public boolean contains(int mouseX, int mouseY) {
        return mouseX >= this.xPosition && mouseX < this.xPosition + this.width
            && mouseY >= this.yPosition
            && mouseY < this.yPosition + this.boxHeight;
    }

    private void fireChange() {
        if (suspendEvents) return;
        TextChangeListener listener = changeListener;
        if (listener != null) listener.onSearchTextChanged(getText());
    }
}
