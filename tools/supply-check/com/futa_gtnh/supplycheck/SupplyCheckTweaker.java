package com.futa_gtnh.supplycheck;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

public final class SupplyCheckTweaker implements ITweaker {
    public SupplyCheckTweaker() throws ReflectiveOperationException {
        Field agents = Class.forName("org.spongepowered.asm.launch.platform.MixinContainer").getDeclaredField("agentClasses");
        agents.setAccessible(true); ((List<?>) agents.get(null)).clear();
    }
    @Override public void acceptOptions(List<String> args, File game, File assets, String profile) {}
    @Override public void injectIntoClassLoader(LaunchClassLoader loader) {
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);
        Mixins.addConfiguration("mixins.futa_supply_check.json");
    }
    @Override public String getLaunchTarget() { return "com.futa_gtnh.ae2.SupplyTrackingRegression"; }
    @Override public String[] getLaunchArguments() { return new String[0]; }
}
