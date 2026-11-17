package com.futa_gtnh.vatcheck;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

/** Transform the pinned Bacterial Vat on the dedicated-server Mixin path, without starting the game. */
public final class VatCheckTweaker implements ITweaker {

    public VatCheckTweaker() throws ReflectiveOperationException {
        Class<?> container = Class.forName("org.spongepowered.asm.launch.platform.MixinContainer");
        Field agents = container.getDeclaredField("agentClasses");
        agents.setAccessible(true);
        ((List<?>) agents.get(null)).clear();
    }

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {}

    @Override
    public void injectIntoClassLoader(LaunchClassLoader loader) {
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);
        Mixins.addConfiguration("mixins.futa_vat_check.json");
    }

    @Override
    public String getLaunchTarget() {
        return "com.futa_gtnh.common.VatVerticalFacingRegression";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
