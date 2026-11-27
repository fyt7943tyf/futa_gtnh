package com.futa_gtnh.ae2;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Bootstrap;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;

import com.futa_gtnh.mixins.FutaGtnhMixinPlugin;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLInjectionData;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

/** Verifies real NeoECOAE jar classes transform cleanly with exact LVT/call-site bindings. */
public final class NeoEcoAeRealJarRegression {

    private static int checks;

    private NeoEcoAeRealJarRegression() {}

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(Launch.classLoader);
        Loader.injectData(
            new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        File homeDir = new File("build/neoecoae-real-jar-check");
        homeDir.mkdirs();
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, homeDir);
        Bootstrap.func_151354_b();

        FutaGtnhMixinPlugin plugin = new FutaGtnhMixinPlugin();
        String[][] targets = {
            { "cn.dancingsnow.neoecoae.platform.gt.upload.GtCircuits",
                "com.futa_gtnh.mixins.MixinNeoEcoAeGtCircuits", "futa$unwrapAnyProgrammingItem" },
            { "cn.dancingsnow.neoecoae.platform.gt.upload.GtRecipeVerifier",
                "com.futa_gtnh.mixins.MixinNeoEcoAeGtRecipeVerifier", "futa$matchOreDictAlt" },
            { "cn.dancingsnow.neoecoae.platform.gt.upload.GtPatternRoutes",
                "com.futa_gtnh.mixins.MixinNeoEcoAeGtPatternRoutes", "futa$requireProgrammingTarget" },
            { "cn.dancingsnow.neoecoae.compat.proghatches.upload.ProgrammableHatchUpload",
                "com.futa_gtnh.mixins.MixinNeoEcoAeProgrammableHatchUpload", "futa$recognizePhSlaveHatch" } };

        for (String[] entry : targets) {
            check(plugin.shouldApplyMixin(entry[0], entry[1]), "Mixin guard enables " + entry[1]);
        }

        inspectGtCircuits(transform(targets[0][0]));
        inspectGtRecipeVerifier(transform(targets[1][0]));
        inspectGtPatternRoutes(transform(targets[2][0]));
        inspectProgrammableHatchUpload(transform(targets[3][0]));

        for (String[] entry : targets) {
            Class<?> transformed = Class.forName(entry[0], false, Launch.classLoader);
            boolean foundHandler = false;
            for (Method method : transformed.getDeclaredMethods()) {
                if (method.getName().contains(entry[2])) {
                    foundHandler = true;
                    break;
                }
            }
            check(foundHandler, "Loaded " + entry[0] + " contains " + entry[2]);
        }

        System.out.println("NeoECOAE real jar Mixin transform: " + checks + " checks passed");
    }

    private static ClassNode transform(String className) throws Exception {
        byte[] bytes = Launch.classLoader.getClassBytes(className);
        check(bytes != null, "Raw bytes present for " + className);
        boolean ranMixin = false;
        for (IClassTransformer transformer : Launch.classLoader.getTransformers()) {
            if (transformer.getClass().getName().startsWith("org.spongepowered.asm.mixin.")) {
                bytes = transformer.transform(className, className, bytes);
                ranMixin = true;
            }
        }
        check(ranMixin, "Found Sponge Mixin transformer for " + className);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void inspectGtCircuits(ClassNode node) {
        MethodNode method = findMethod(node, "normalizeForRecipe");
        boolean callsCompat = false;
        boolean stillCallsUnwrap = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.name.contains("futa$unwrapAnyProgrammingItem")) callsCompat = true;
                if ("unwrap".equals(call.name) && call.owner.endsWith("/GtCircuits")) stillCallsUnwrap = true;
            }
        }
        check(callsCompat, "GtCircuits.normalizeForRecipe calls futa$unwrapAnyProgrammingItem");
        check(!stillCallsUnwrap, "GtCircuits.normalizeForRecipe no longer calls GtCircuits.unwrap");
    }

    private static void inspectGtRecipeVerifier(ClassNode node) {
        MethodNode method = findMethod(node, "ratio");
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() >= 0) insns.add(insn);
        }
        int callIdx = -1;
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.contains("futa$matchOreDictAlt")) {
                callIdx = i;
                break;
            }
        }
        check(callIdx >= 1, "GtRecipeVerifier.ratio calls futa$matchOreDictAlt");
        boolean passesRecipeArg = false;
        for (int i = Math.max(0, callIdx - 5); i < callIdx; i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof VarInsnNode && insn.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) insn).var == 0) {
                passesRecipeArg = true;
                break;
            }
        }
        check(passesRecipeArg, "GtRecipeVerifier.ratio passes aload 0 (GTRecipe) to futa$matchOreDictAlt");
    }

    private static void inspectGtPatternRoutes(ClassNode node) {
        MethodNode method = findMethod(node, "evaluate");
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() >= 0) insns.add(insn);
        }
        int callIdx = -1;
        int verifyIdx = -1;
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (callIdx < 0 && call.name.contains("futa$requireProgrammingTarget")) callIdx = i;
                if (verifyIdx < 0 && "verify".equals(call.name) && call.owner.endsWith("GtRecipeVerifier$Cache")) {
                    verifyIdx = i;
                }
            }
        }
        check(callIdx >= 3 && verifyIdx > callIdx, "GtPatternRoutes.evaluate calls futa$requireProgrammingTarget before Cache.verify");
        AbstractInsnNode a9 = insns.get(callIdx - 3);
        AbstractInsnNode i6 = insns.get(callIdx - 2);
        AbstractInsnNode i7 = insns.get(callIdx - 1);
        AbstractInsnNode s9 = insns.get(callIdx + 1);
        check(
            a9 instanceof VarInsnNode && a9.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) a9).var == 9,
            "evaluate loads aload 9 (conflictReason)");
        check(
            i6 instanceof VarInsnNode && i6.getOpcode() == Opcodes.ILOAD && ((VarInsnNode) i6).var == 6,
            "evaluate loads iload 6 (hasProgrammingCircuit)");
        check(
            i7 instanceof VarInsnNode && i7.getOpcode() == Opcodes.ILOAD && ((VarInsnNode) i7).var == 7,
            "evaluate loads iload 7 (allowProgramming)");
        check(
            s9 instanceof VarInsnNode && s9.getOpcode() == Opcodes.ASTORE && ((VarInsnNode) s9).var == 9,
            "evaluate stores result back to astore 9 (conflictReason)");
    }

    private static void inspectProgrammableHatchUpload(ClassNode node) {
        MethodNode method = findMethod(node, "isProgrammingInput");
        boolean callsCompat = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode
                && ((MethodInsnNode) insn).name.contains("futa$recognizePhSlaveHatch")) {
                callsCompat = true;
                break;
            }
        }
        check(callsCompat, "ProgrammableHatchUpload.isProgrammingInput calls futa$recognizePhSlaveHatch");
    }

    private static MethodNode findMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name)) return method;
        }
        throw new AssertionError("Missing method " + node.name + "#" + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
