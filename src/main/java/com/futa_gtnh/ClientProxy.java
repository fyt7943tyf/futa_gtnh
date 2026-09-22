package com.futa_gtnh;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.futa_gtnh.client.GuiLocatorWand;
import com.futa_gtnh.client.GuiSwiftStep;
import com.futa_gtnh.client.KeyHandler;
import com.futa_gtnh.client.LocatorBeamRenderer;
import com.futa_gtnh.client.LocatorState;
import com.futa_gtnh.client.SwiftStepClientHandler;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLocatorAction;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 客户端专属逻辑。继承 {@link CommonProxy}，只在需要覆写客户端行为时才写方法。
 * 注意：如果覆写了父类方法，记得按需调用 {@code super.xxx(event)}。
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        KeyHandler.register();
        SwiftStepClientHandler.register();

        // 世界渲染事件在 MinecraftForge.EVENT_BUS 上，不在 FML 的那条总线上。
        // 挂错了不会报错，只会永远收不到事件 —— 光渲染器会安静地什么都不画。
        MinecraftForge.EVENT_BUS.register(new LocatorBeamRenderer());
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    /**
     * 迅步的调整界面。
     *
     * <p>
     * 覆盖 {@link CommonProxy#openSwiftStepGui}。之所以绕这一道，
     * 是为了让 {@code ItemSwiftStep}（公共类）不用引用 {@code net.minecraft.client.*}。
     */
    @Override
    public void openSwiftStepGui(ItemStack charm) {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiSwiftStep(charm));
    }

    /**
     * 寻物魔杖的选择界面。
     *
     * <p>
     * 覆盖 {@link CommonProxy#openLocatorGui}。这个界面没有服务端容器，
     * 所以直接 {@code displayGuiScreen} 就行 —— 选中方块、传送、取消
     * 三个动作各自发一个包，服务端再校验。
     */
    @Override
    public void openLocatorGui() {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiLocatorWand());
    }

    /**
     * 清掉追踪：本地状态 + 通知服务端。
     *
     * <p>
     * 两件事都必须由客户端发起 —— 光束是客户端画的，而「取消」这个包
     * 服务端自己发给自己没有任何意义。这也正是这个方法要放在代理里的原因。
     */
    @Override
    public void clearLocatorTracking() {
        LocatorState.clear();
        NetworkHandler.INSTANCE.sendToServer(new PacketLocatorAction(PacketLocatorAction.CANCEL));
    }
}
