package com.futa_gtnh.client;

import java.util.Collections;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import cpw.mods.fml.client.IModGuiFactory;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * FML 的 Mods 列表「Config」按钮入口（主菜单 → Mods → FutaGTNH → Config）。
 *
 * <p>
 * 这是 1.7.10 FML 的官方机制：{@code GuiModList} 会用<b>单个 {@code (GuiScreen)}
 * 参数的构造器</b>反射实例化 {@link #mainConfigGuiClass()} 返回的类，
 * 参数是当前的 Mods 列表界面，作为 ESC 的返回目标传给
 * {@link GuiClientConfig#GuiClientConfig(GuiScreen)}。
 *
 * <p>
 * 客户端功能开关全是静态配置，不依赖已加载的世界，主菜单里打开也没有问题 ——
 * 所以不需要 EZMiner 那样的「主菜单守卫」中间页，直接返回配置界面本体。
 * FML 只在客户端实例化 guiFactory（服务端不碰这个注解值），@Mod 上的字符串引用安全。
 */
@SideOnly(Side.CLIENT)
public class ClientGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraftInstance) {
        // 配置界面直接读 Config 静态字段，无状态可初始化
    }

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return GuiClientConfig.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }
}
