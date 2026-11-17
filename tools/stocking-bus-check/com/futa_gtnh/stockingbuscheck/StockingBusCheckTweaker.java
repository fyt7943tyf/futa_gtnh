package com.futa_gtnh.stockingbuscheck;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

/** Transform the GT stocking bus and multiblock base on the dedicated-server Mixin path, without starting the game. */
public final class StockingBusCheckTweaker implements ITweaker {

    public StockingBusCheckTweaker() throws ReflectiveOperationException {
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
        Mixins.addConfiguration("mixins.futa_stocking_bus_standin.json");
        Mixins.addConfiguration("mixins.futa_stocking_bus_check.json");
    }

    @Override
    public String getLaunchTarget() {
        return "com.futa_gtnh.ae2.StockingBusRegression";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
