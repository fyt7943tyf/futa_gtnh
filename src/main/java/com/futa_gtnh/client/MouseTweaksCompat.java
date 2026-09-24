package com.futa_gtnh.client;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

import org.lwjgl.input.Mouse;

import com.futa_gtnh.inventory.ContainerSharedTerminal;

import cpw.mods.fml.common.Loader;

/**
 * MouseTweaks 兼容层。
 *
 * <p>
 * <b>要解决的问题：</b>MouseTweaks 的「滚轮 tweak」（配置项 {@code B:WheelTweak}，GTNH 整合包
 * 默认开着）语义是「滚轮滚过某一格时，替玩家在两个物品栏之间搬一个物品」，做法是<b>合成一次
 * 真实点击</b>打到那一格上（见 MouseTweaks 的
 * {@code yalter.mousetweaks.handlers.WheelHandler} → {@code ContainerContext.clickSlot}，
 * 最终调的是 {@code GuiContainer.handleMouseClick} 或 {@code PlayerControllerMP.windowClick}）。
 *
 * <p>
 * 对原版容器这没问题，但共享存储的格子是<b>虚拟格</b>：在
 * {@code ContainerSharedTerminal.slotClick} 里「左键点一下 = 取出 1 个」。两者一叠加，
 * 玩家在 B 界面里<b>滚轮每滚一格就会掉出来一个物品</b>（而滚轮本来只该用来翻页）。
 *
 * <p>
 * <b>做法：</b>MouseTweaks 提供了官方兼容接口
 * {@code yalter.mousetweaks.api.IMTModGuiContainer}：界面实现它就等于告诉 MouseTweaks
 * 「这个容器我自己管」。其中 {@code isWheelTweakDisabled()} 返回 true 时，它就不会在这个
 * 界面上做滚轮搬运 —— 这是唯一一处行为改动；接口里其它方法都按原版语义老实实现
 * （点击依旧交回 {@code handleMouseClick}），所以 MouseTweaks 的拖拽/左键 tweak 在本界面里
 * 的手感不变。
 *
 * <p>
 * <b>隔离规则（和 {@link NecharBridge} 一样）：所有出现 MouseTweaks 类型的代码只能存在于
 * {@link Gui} 这个嵌套类里。</b>没装 MouseTweaks 时那个接口不存在，加载 {@link Gui} 会
 * {@code NoClassDefFoundError}；嵌套类是懒加载的，而 {@link #isAvailable()} 只在 modid 和
 * 类名都探到之后才让外层去 {@code new} 它，所以缺席时一切照旧。
 */
public final class MouseTweaksCompat {

    private static boolean probed;
    private static boolean available;

    private MouseTweaksCompat() {}

    /**
     * MouseTweaks（modid 就是 {@code MouseTweaks}，注意大小写）是否在场且可用。
     * 第一次调用时探测一次，之后缓存。
     *
     * <p>
     * 除了 modid 还按类名探了一次接口：FML 的 modid 表在极早期可能还没填好，
     * 双保险（和 {@link NecharBridge#init()} 一个思路）。
     */
    public static boolean isAvailable() {
        if (!probed) {
            probed = true;
            try {
                available = Loader.isModLoaded("MouseTweaks")
                    && probeClass("yalter.mousetweaks.api.IMTModGuiContainer");
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    private static boolean probeClass(String name) {
        try {
            Class.forName(name, false, MouseTweaksCompat.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 装了 MouseTweaks 时实际使用的界面类，没装时返回 {@code null}。
     *
     * <p>
     * 给 NEI 联动用：NEI 的 {@code GuiInfo.customSlotGuis} 是
     * {@code HashSet<Class>} + {@code gui.getClass()} 精确匹配，子类不会自动继承，
     * 所以那边要把这个子类也登记一遍（见 {@code NeiIntegration.register()}）。
     *
     * <p>
     * 没装 MouseTweaks 时直接返回 null：碰 {@link Gui} 会触发它的类加载，而它实现的
     * 接口那时候并不存在，会 {@code NoClassDefFoundError}。
     */
    public static Class<? extends GuiContainer> guiClass() {
        return isAvailable() ? Gui.class : null;
    }

    /**
     * 装了 MouseTweaks 时使用的终端界面。
     *
     * <p>
     * 除了 {@code isWheelTweakDisabled()} 之外，其它方法都刻意和原版保持一致：
     * 槽位表就是 {@link #inventorySlots}，点击依旧走 {@code handleMouseClick}。
     * 这样无论 MouseTweaks 把本界面当成「模组界面」还是「原版界面」，
     * 玩家已有的操作手感都不会变。
     */
    public static final class Gui extends GuiSharedTerminal implements yalter.mousetweaks.api.IMTModGuiContainer {

        Gui(ContainerSharedTerminal container) {
            super(container);
        }

        /** 接口版本号。MouseTweaks 2.5.x 这一版接口就是 1。 */
        @Override
        public int getAPIVersion() {
            return 1;
        }

        @Override
        public String getModName() {
            return "FutaGTNH 共享存储终端";
        }

        @Override
        public boolean isMouseTweaksDisabled() {
            return false;
        }

        /**
         * ★ 整个兼容层的重点：<b>在本界面里关掉 MouseTweaks 的滚轮搬运。</b>
         *
         * <p>
         * 滚轮在共享存储网格上只应该翻页（{@link GuiSharedTerminal#handleMouseInput()}），
         * 不该往外掏东西。
         */
        @Override
        public boolean isWheelTweakDisabled() {
            return true;
        }

        @Override
        public Object getModContainer() {
            return inventorySlots;
        }

        @Override
        public int getModSlotCount(Object modContainer) {
            return ((Container) modContainer).inventorySlots.size();
        }

        @Override
        public Object getModSlot(Object modContainer, int slotNumber) {
            Container container = (Container) modContainer;
            if (slotNumber < 0 || slotNumber >= container.inventorySlots.size()) return null;
            return container.getSlot(slotNumber);
        }

        /** 原版语义是「鼠标底下那一格」，没有就是 null（只有滚轮那条路径会问它）。 */
        @Override
        public Object getModSelectedSlot(Object modContainer, int slotNumber) {
            Object slot = getModSlot(modContainer, slotNumber);
            if (!(slot instanceof Slot)) return null;

            Slot real = (Slot) slot;
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            return func_146978_c(real.xDisplayPosition, real.yDisplayPosition, 16, 16, mouseX, mouseY) ? real : null;
        }

        @Override
        public boolean isCraftingOutputSlot(Object modContainer, Object modSlot) {
            return modSlot instanceof Slot && ((Slot) modSlot).slotNumber == ContainerSharedTerminal.RESULT_SLOT;
        }

        /**
         * MouseTweaks 的搬运动作最终都落到这里。默认走原版那条
         * {@code handleMouseClick(slot, slotNumber, 鼠标键, shift ? 1 : 0)}，
         * 也就是它在原版界面上会做的事 —— 本模组不拦，语义交给
         * {@code ContainerSharedTerminal.slotClick} 决定。
         */
        @Override
        public void clickModSlot(Object modContainer, Object modSlot, int mouseButton, boolean shift) {
            Slot slot = modSlot instanceof Slot ? (Slot) modSlot : null;
            handleMouseClick(slot, slot == null ? -999 : slot.slotNumber, mouseButton, shift ? 1 : 0);
        }

        /** 本界面不需要为右键拖拽做特殊处理（原版路径下同样什么都没做）。 */
        @Override
        public void disableRMBDragIfRequired(Object modContainer, Object modSlot, boolean shouldForce) {}
    }
}
