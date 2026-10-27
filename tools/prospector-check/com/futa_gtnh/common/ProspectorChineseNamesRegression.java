package com.futa_gtnh.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.StatCollector;

import com.futa_gtnh.mixins.FutaGtnhMixinPlugin;
import com.gtnewhorizon.gtnhlib.util.data.BlockMeta;

import cpw.mods.fml.common.Loader;
import detrav.net.ProspectingPacket;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.common.ores.UnificationOreAdapter;
import it.unimi.dsi.fastutil.Pair;

/** Dedicated-server scanner checks with GT's real ore adapter and unchanged wire codec. */
public final class ProspectorChineseNamesRegression {

    private ProspectorChineseNamesRegression() {}

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(Launch.classLoader);
        Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        File minecraftHome = new File("build/prospector-check");
        minecraftHome.mkdirs();
        Field home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, minecraftHome);
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field namedMods = Loader.class.getDeclaredField("namedMods");
        namedMods.setAccessible(true);
        namedMods.set(Loader.instance(), new java.util.HashMap<>());
        Field logSide = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side");
        logSide.setAccessible(true);
        logSide.set(null, cpw.mods.fml.relauncher.Side.SERVER);
        Bootstrap.func_151354_b();
        check(Arrays.stream(ProspectingPacket.class.getDeclaredMethods()).anyMatch(m -> m.getName().contains("chineseOreName")), "production mixin transformed packet");
        check(new FutaGtnhMixinPlugin().shouldApplyMixin("detrav.net.ProspectingPacket", "com.futa_gtnh.mixins.MixinProspectingPacket"), "GT hook independent of Tinkers");
        String globalLanguage = StatCollector.translateToLocal("tile.oreIron.name");
        File directory = Files.createTempDirectory("futa-prospector-check").toFile();
        File dictionary = new File(directory, "GregTech_zh_CN.lang");
        try {
            ProspectorChineseNames.reload(dictionary);
            check("铜矿石".equals(ProspectorChineseNames.formatOreName("Material.copper", false, "Copper Ore")), "bundled copper");
            check("小型铜矿石".equals(ProspectorChineseNames.formatOreName("Material.copper", true, "Small Copper Ore")), "small ore differs");
            check("锆石矿石".equals(ProspectorChineseNames.formatOreName("Material.zircon", false, "Zircon Ore")), "extended material");
            check("Unknown Ore".equals(ProspectorChineseNames.formatOreName("Material.missing", false, "Unknown Ore")), "unknown preserves original name");

            // Register fixtures in GT's adapter table without running world generation or registering new blocks.
            Field tableField = UnificationOreAdapter.class.getDeclaredField("MAT_BLOCK_TABLE");
            tableField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> table = (Map<Object, Object>) tableField.get(null);
            table.put(new BlockMeta(Blocks.iron_ore, 0), Pair.of(OrePrefixes.ore, Materials.Copper));
            table.put(new BlockMeta(Blocks.iron_ore, 1), Pair.of(OrePrefixes.oreSmall, Materials.Copper));
            ProspectingPacket packet = new ProspectingPacket(0, 0, 0, 0, 1, 0);
            packet.addBlock(0, 30, 0, Blocks.iron_ore, 0);
            packet.addBlock(1, 30, 0, Blocks.iron_ore, 0);
            packet.addBlock(2, 30, 0, Blocks.iron_ore, 1);
            check(packet.objects.size() == 2, "repeated ores grouped; small ores separate");
            check(packet.map.size() == 3, "all scanned coordinates retained");
            check(packet.objects.values().stream().anyMatch(v -> "铜矿石".equals(v.left())), "hook writes Chinese into packet");
            check(packet.objects.values().stream().anyMatch(v -> "小型铜矿石".equals(v.left())), "hook retains small ore identity");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            packet.encode(bytes);
            ProspectingPacket decoded = (ProspectingPacket) ProspectingPacket.decode(new ByteArrayInputStream(bytes.toByteArray()));
            check(packet.objects.equals(decoded.objects), "original codec retains names, IDs and colors");
            check(packet.map.equals(decoded.map), "original codec retains marker coordinates");
            check(packet.oreMaterialNames.equals(decoded.oreMaterialNames), "material IDs retained for icons");

            byte[] overrides = ("# Configuration file\nlanguagefile {\n S:Material.copper=自定义铜\n S:\"Material.zircon\"=自定义锆石\n S:Material.missing=新增材料\n S:Material.copper.ChemicalFormula=Cu\n}\n").getBytes(StandardCharsets.UTF_8);
            Files.write(dictionary.toPath(), overrides);
            ProspectorChineseNames.reload(dictionary);
            check("自定义铜矿石".equals(ProspectorChineseNames.getOreName(Blocks.iron_ore, 0, "Copper Ore")), "server overrides invalidate name cache");
            check("自定义锆石矿石".equals(ProspectorChineseNames.formatOreName("Material.zircon", false, "Zircon Ore")), "quoted Forge keys");
            check("新增材料矿石".equals(ProspectorChineseNames.formatOreName("Material.missing", false, "Unknown Ore")), "server can add translations");
            check(Arrays.equals(overrides, Files.readAllBytes(dictionary.toPath())), "translation source unchanged");
            check(globalLanguage.equals(StatCollector.translateToLocal("tile.oreIron.name")), "global language unchanged");
            Files.delete(dictionary.toPath());
            ProspectorChineseNames.reload(dictionary);
            check("铜矿石".equals(ProspectorChineseNames.getOreName(Blocks.iron_ore, 0, "Copper Ore")), "server restart restores bundled fallback");
            System.out.println("Prospector Chinese names: transformed server hook, ore grouping, wire round trip and dictionary checks passed.");
        } finally {
            Files.deleteIfExists(dictionary.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
