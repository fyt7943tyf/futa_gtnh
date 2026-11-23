package com.futa_gtnh.bees;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.common.Loader;
import li.cil.oc.api.API;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.server.driver.CompoundBlockEnvironment;
import li.cil.oc.server.network.Network$;
import scala.Tuple2;
import scala.collection.JavaConverters;

/** Uses OC's adapter component to exercise its concrete callback dispatch. */
public final class BeeOcCallbackCheck {

    private BeeOcCallbackCheck() {}

    public static void main(String[] args) throws Throwable {
        Thread.currentThread().setName("Server thread");
        String[] paths = System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator));
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = new File(paths[i]).toURI().toURL();
        try (LaunchClassLoader loader = new LaunchClassLoader(urls)) {
            Launch.classLoader = loader;
            Launch.blackboard = new HashMap<>();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                loader.loadClass(BeeOcCallbackCheck.class.getName()).getMethod("run").invoke(null);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }

    public static void run() throws Exception {
        Loader.injectData("7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList());
        API.network = Network$.MODULE$;
        verify(new BeeOcDrivers.Mutatron(new TileEntity(), null), "futa_mutatron");
        verify(new BeeOcDrivers.Engineer(new TileEntity()), "futa_bee_engineer");
        System.out.println("OC compound adapter dispatches beePosition on both concrete drivers");
    }

    private static void verify(ManagedEnvironment machine, String name) throws Exception {
        List<Tuple2<String, ManagedEnvironment>> entries = Collections.singletonList(new Tuple2<>(name, machine));
        CompoundBlockEnvironment adapter = new CompoundBlockEnvironment(
            name,
            JavaConverters.asScalaBufferConverter(entries).asScala().toSeq());
        Component component = adapter.node();
        if (!component.methods().contains("beePosition")) throw new AssertionError(name + " does not expose beePosition");
        try {
            component.invoke("beePosition", null);
            throw new AssertionError(name + " accepted a tile without a world");
        } catch (IllegalStateException expected) {
            if (!"Bee machine is no longer attached".equals(expected.getMessage())) throw expected;
        }
    }
}
