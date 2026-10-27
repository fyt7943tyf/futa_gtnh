package com.futa_gtnh.common;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.regex.Pattern;

/** Give legacy LaunchWrapper the URLClassLoader it expects on Java 17 and newer. */
public final class ProspectorCheckLauncher {

    private ProspectorCheckLauncher() {}

    public static void main(String[] args) throws Throwable {
        String[] paths = System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator));
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = new File(paths[i]).toURI().toURL();
        // Log4j's JVM shutdown hooks still need this loader after the check's main method returns.
        URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName("net.minecraft.launchwrapper.Launch", true, loader)
                .getMethod("main", String[].class)
                .invoke(null, (Object) args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
