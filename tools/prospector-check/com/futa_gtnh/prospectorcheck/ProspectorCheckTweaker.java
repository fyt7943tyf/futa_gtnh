package com.futa_gtnh.prospectorcheck;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

/** Exercise the production mixin through the same LaunchWrapper service as Forge. */
public final class ProspectorCheckTweaker implements ITweaker {

    public ProspectorCheckTweaker() throws ReflectiveOperationException {
        // This is a registry-only check: do not auto-start every coremod found on the test classpath.
        Class<?> container = Class.forName("org.spongepowered.asm.launch.platform.MixinContainer");
        Field agents = container.getDeclaredField("agentClasses");
        agents.setAccessible(true);
        ((List<?>) agents.get(null)).clear();
    }

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {}

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);
        Mixins.addConfiguration("mixins.futa_prospector_check.json");
    }

    @Override
    public String getLaunchTarget() {
        return "com.futa_gtnh.common.ProspectorChineseNamesRegression";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
