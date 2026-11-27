package com.futa_gtnh.ae2;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.mixins.FutaGtnhMixinPlugin;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLInjectionData;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;
import gregtech.GTMod;
import gregtech.api.util.GTRecipe;
import gregtech.common.GTProxy;

/** Unit checks for NeoECOAE pattern-upload compatibility helpers and Mixin guard isolation. */
public final class NeoEcoAeCompatRegression {

    private static int checks;

    private NeoEcoAeCompatRegression() {}

    public static void main(String[] args) throws Exception {
        Loader.injectData(
            new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        File homeDir = new File("build/neoecoae-compat-check");
        homeDir.mkdirs();
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, homeDir);
        Field mods = Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        mods.set(Loader.instance(), Collections.emptyMap());
        Launch.blackboard.put("fml.deobfuscatedEnvironment", Boolean.TRUE);
        Bootstrap.func_151354_b();
        if (GTMod.proxy == null) {
            Field unsafeField = Class.forName("sun.misc.Unsafe")
                .getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            GTMod.proxy = (GTProxy) unsafe.getClass()
                .getMethod("allocateInstance", Class.class)
                .invoke(unsafe, GTProxy.class);
        }

        testOreDictAltMatching();
        testSlaveHatchNameCheck();
        testMixinGuardSkipsWhenAbsent();

        System.out.println("NeoECOAE upload compat: " + checks + " checks passed");
    }

    private static void testOreDictAltMatching() {
        ItemStack defaultBlueLens = new ItemStack(Items.apple, 0, 10);
        ItemStack altBlueLens = new ItemStack(Items.apple, 1, 11);
        ItemStack redLens = new ItemStack(Items.apple, 1, 12);
        ItemStack wafer = new ItemStack(Items.diamond, 1, 0);

        String oreName = "futaTestCraftingLensBlue";
        OreDictionary.registerOre(oreName, new ItemStack(Items.apple, 1, 10));
        OreDictionary.registerOre(oreName, altBlueLens);
        int oreId = OreDictionary.getOreID(oreName);

        GTRecipe.GTRecipe_WithAlt recipeWithAltArray = new GTRecipe.GTRecipe_WithAlt(
            false,
            new ItemStack[] { defaultBlueLens, wafer },
            new ItemStack[] { new ItemStack(Items.emerald, 1) },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            new ItemStack[][] { new ItemStack[] { new ItemStack(Items.apple, 1, 10), altBlueLens }, null },
            null);

        check(
            NeoEcoAeUploadCompat.matchesRecipeAltInput(recipeWithAltArray, defaultBlueLens, altBlueLens),
            "non-representative lens matches mOreDictAlt");
        check(
            !NeoEcoAeUploadCompat.matchesRecipeAltInput(recipeWithAltArray, defaultBlueLens, redLens),
            "different-color lens does not match mOreDictAlt");
        check(
            !NeoEcoAeUploadCompat.matchesRecipeAltInput(recipeWithAltArray, wafer, altBlueLens),
            "non-lens slot does not match lens alt");

        GTRecipe.GTRecipe_WithAlt recipeWithOreIds = new GTRecipe.GTRecipe_WithAlt(
            false,
            new ItemStack[] { defaultBlueLens, wafer },
            new ItemStack[] { new ItemStack(Items.emerald, 1) },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            null,
            null);
        recipeWithOreIds.mOreDictIds = new int[] { oreId, -1 };

        check(
            NeoEcoAeUploadCompat.matchesRecipeAltInput(recipeWithOreIds, defaultBlueLens, altBlueLens),
            "non-representative lens matches mOreDictIds");
        check(
            !NeoEcoAeUploadCompat.matchesRecipeAltInput(recipeWithOreIds, defaultBlueLens, redLens),
            "different-color lens does not match mOreDictIds");

        GTRecipe plainRecipe = new GTRecipe(
            false,
            new ItemStack[] { defaultBlueLens },
            new ItemStack[] { new ItemStack(Items.emerald, 1) },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            0);
        check(
            !NeoEcoAeUploadCompat.matchesRecipeAltInput(plainRecipe, defaultBlueLens, altBlueLens),
            "plain GTRecipe returns false without error");
    }

    private static void testSlaveHatchNameCheck() {
        check(!NeoEcoAeUploadCompat.isPhSlaveProgrammingInput(null), "null is not PH slave");
        check(!NeoEcoAeUploadCompat.isPhSlaveProgrammingInput(new Object()), "plain Object is not PH slave");
    }

    private static void testMixinGuardSkipsWhenAbsent() {
        FutaGtnhMixinPlugin plugin = new FutaGtnhMixinPlugin();
        check(
            !plugin.shouldApplyMixin(
                "cn.dancingsnow.neoecoae.platform.gt.upload.GtCircuits",
                "com.futa_gtnh.mixins.MixinNeoEcoAeGtCircuits"),
            "GtCircuits mixin skipped when neoecoae is absent");
        check(
            !plugin.shouldApplyMixin(
                "cn.dancingsnow.neoecoae.platform.gt.upload.GtRecipeVerifier",
                "com.futa_gtnh.mixins.MixinNeoEcoAeGtRecipeVerifier"),
            "GtRecipeVerifier mixin skipped when neoecoae is absent");
        check(
            !plugin.shouldApplyMixin(
                "cn.dancingsnow.neoecoae.platform.gt.upload.GtPatternRoutes",
                "com.futa_gtnh.mixins.MixinNeoEcoAeGtPatternRoutes"),
            "GtPatternRoutes mixin skipped when neoecoae is absent");
        check(
            !plugin.shouldApplyMixin(
                "cn.dancingsnow.neoecoae.compat.proghatches.upload.ProgrammableHatchUpload",
                "com.futa_gtnh.mixins.MixinNeoEcoAeProgrammableHatchUpload"),
            "ProgrammableHatchUpload mixin skipped when neoecoae is absent");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
