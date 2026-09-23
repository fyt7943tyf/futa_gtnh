package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import com.futa_gtnh.tinkers.TinkersAutoFill;

/**
 * 把「玩家点了哪一格」告诉工作站自动补料。
 *
 * <p>
 * <b>为什么非要在这里拿这个信号</b>：自动补料的规则是「记住你摆好的样子，少了就补回」，
 * 而「少了」有两种完全不同的原因 —— 合成吃掉了（该补）、玩家自己拿走了（不该补）。
 * 库存在这两种情况下长得一模一样，只有在<b>处理点击的那一刻</b>才知道是谁干的。
 * 少了这个信号，玩家从合成站九宫格里把材料拿回来，下一 tick 就被补回去，
 * 表现就是「东西拿不出来」。
 *
 * <p>
 * 挂在原版 {@code Container.slotClick} 上而不是匠魂的容器上，有两个原因：
 * <ul>
 * <li>匠魂的 {@code CraftingStationContainer.slotClick} 会调 {@code super.slotClick}，
 * 挂基类一样能收到；冶炼炉那边则是直接走基类实现；</li>
 * <li>只挂一处，匠魂改版也不影响这里。</li>
 * </ul>
 *
 * <p>
 * 开销可以忽略：转发方法先判两个 boolean（自动补料开关、匠魂在场），
 * 再看「有没有工作站界面开着」——一个空 {@code WeakHashMap} 的 {@code isEmpty()}，
 * 所以游戏里其它界面的点击什么都不会做。
 *
 * <p>
 * {@code slotClick} 是原版方法，正式包里会被重命名，所以这里保持默认的 {@code remap}
 * （让 refmap 翻成 {@code func_75144_a}）；事件不取消，只是旁听。
 */
@Mixin(Container.class)
public class MixinContainer {

    @Inject(method = "slotClick", at = @At("HEAD"))
    private void futa$notePlayerClick(int slotId, int clickedButton, int mode, EntityPlayer player) {
        TinkersAutoFill.notePlayerClick((Container) (Object) this, slotId, mode);
    }
}
