package com.futa_gtnh.common;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import net.minecraft.init.Bootstrap;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.mixins.FutaGtnhMixinPlugin;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

/** Real transformation of GT's Bacterial Vat plus the Y offset the fluid area needs when facing UP/DOWN. */
public final class VatVerticalFacingRegression {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(Launch.classLoader);
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        Bootstrap.func_151354_b();

        String name = "bartworks.common.tileentities.multis.MTEBioVat";
        check(new FutaGtnhMixinPlugin().shouldApplyMixin(name, "com.futa_gtnh.mixins.MixinBioVatVerticalFacing"),
            "hook is enabled while the pinned vat still lacks getYDir");
        Class<?> vat = Class.forName(name, false, Launch.classLoader);
        Method handler = null;
        for (Method method : vat.getDeclaredMethods()) {
            if (method.getName().contains("futa$shiftVatAreaY")) handler = method;
        }
        check(handler != null, "real pinned vat transforms and contains the redirect (5 methods, require=5; handler delegates to BioVatAreaOffset)");
        // back = UP (controller facing DOWN) must move the area 2 up; back = DOWN (facing UP) 2 down.
        check(shifted(ForgeDirection.UP) == 66, "controller facing down: area shifted +2 in Y");
        check(shifted(ForgeDirection.DOWN) == 62, "controller facing up: area shifted -2 in Y");
        for (ForgeDirection horizontal : new ForgeDirection[] { ForgeDirection.NORTH, ForgeDirection.SOUTH,
            ForgeDirection.EAST, ForgeDirection.WEST }) {
            check(shifted(horizontal) == 64, "horizontal " + horizontal + ": Y unchanged");
        }
        System.out.println("Bacterial Vat vertical facing: " + checks + " checks passed (real vat Mixin)");
    }

    private static int shifted(ForgeDirection back) throws Exception {
        Class<?> tileType = Class.forName("gregtech.api.interfaces.tileentity.IGregTechTileEntity", false, Launch.classLoader);
        InvocationHandler fake = (proxy, method, arguments) -> {
            if ("getYCoord".equals(method.getName())) return (short) 64;
            if ("getBackFacing".equals(method.getName())) return back;
            throw new UnsupportedOperationException(method.getName());
        };
        Object tile = Proxy.newProxyInstance(Launch.classLoader, new Class<?>[] { tileType }, fake);
        return BioVatAreaOffset.shiftedY((gregtech.api.interfaces.tileentity.IGregTechTileEntity) tile);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
