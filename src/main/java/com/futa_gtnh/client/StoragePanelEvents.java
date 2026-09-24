package com.futa_gtnh.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraftforge.client.event.GuiScreenEvent;

import com.futa_gtnh.client.nei.StoragePanelInput;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 共享存储面板的两条挂载线：
 *
 * <ul>
 * <li>{@link #onDrawScreen}：注册在 {@code MinecraftForge.EVENT_BUS} 上，画在任意界面之后 ——
 * 匠魂工作站界面开着时，把搜索栏画在它下面，并把存储区的真实数量补画上去
 * （{@link StationAmounts}）；</li>
 * <li>{@link StoragePanelInput}（NEI 的 {@code IContainerInputHandler}）：1.7.10 的 Forge
 * 没有可取消的鼠标/键盘事件，点击与键入必须走 NEI 这条能「消费事件」的路，
 * 否则点面板等于点在界面上（点在界面外会把手上的东西丢地上）。
 * 它由 {@code NeiIntegration} 在确认 NEI 在场后注册，所以没装 NEI 时那个类不会被加载。</li>
 * </ul>
 */
public class StoragePanelEvents {

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        GuiScreen screen = event.gui;
        if (!(screen instanceof GuiContainer)) return;

        StoragePanel.get()
            .draw(screen, event.mouseX, event.mouseY);
        // 存储区的物品栈只能显示到 64（超过会被原版整叠搬走），真数量这里补画
        StationAmounts.draw(screen, event.mouseX, event.mouseY);
    }
}
