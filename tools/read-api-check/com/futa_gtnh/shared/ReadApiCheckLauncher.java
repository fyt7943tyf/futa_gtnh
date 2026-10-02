package com.futa_gtnh.shared;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.HashMap;
import java.util.regex.Pattern;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

/** Provides Forge's expected class loader for registry-only checks, without launching a game. */
public final class ReadApiCheckLauncher {

    private ReadApiCheckLauncher() {}

    public static void main(String[] args) throws Throwable {
        String[] paths = System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator));
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = new File(paths[i]).toURI().toURL();
        try (LaunchClassLoader loader = new LaunchClassLoader(urls)) {
            Launch.classLoader = loader;
            Launch.blackboard = new HashMap<>();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class.forName("com.futa_gtnh.shared.SharedStorageReadApiRegression", true, loader)
                    .getMethod("main", String[].class)
                    .invoke(null, (Object) args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }
}
