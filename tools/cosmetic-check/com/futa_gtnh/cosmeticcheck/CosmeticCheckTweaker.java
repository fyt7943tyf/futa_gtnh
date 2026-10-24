package com.futa_gtnh.cosmeticcheck;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

/** Run the real CAR cache and production Mixin without starting a graphical client. */
public final class CosmeticCheckTweaker implements ITweaker {

    public CosmeticCheckTweaker() throws ReflectiveOperationException {
        Class<?> container = Class.forName("org.spongepowered.asm.launch.platform.MixinContainer");
        Field agents = container.getDeclaredField("agentClasses");
        agents.setAccessible(true);
        ((List<?>) agents.get(null)).clear();
    }

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {}

    @Override
    public void injectIntoClassLoader(LaunchClassLoader loader) {
        MixinEnvironment.getDefaultEnvironment().setSide(
            Boolean.getBoolean("futa.cosmetic.absent") ? MixinEnvironment.Side.SERVER : MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("mixins.futa_cosmetic_check.json");
    }

    @Override
    public String getLaunchTarget() {
        return Boolean.getBoolean("futa.cosmetic.absent") ? "com.futa_gtnh.cosmeticcheck.CosmeticAbsentRegression"
            : "com.futa_gtnh.cosmetic.client.CosmeticArmorRegression";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
