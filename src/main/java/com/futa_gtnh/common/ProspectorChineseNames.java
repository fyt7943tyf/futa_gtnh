package com.futa_gtnh.common;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;

import com.futa_gtnh.FutaGtnhMod;

import gregtech.common.ores.OreInfo;
import gregtech.common.ores.OreManager;

/** A private Chinese dictionary for scanner results; never changes Minecraft's global language. */
public final class ProspectorChineseNames {

    private static final String DICTIONARY = "/assets/futa_gtnh/prospector/zh_CN.lang";
    private static volatile Map<String, String> translations = bundledTranslations();
    private static final Map<Block, Map<Integer, String>> ORE_NAMES = new ConcurrentHashMap<>();

    private ProspectorChineseNames() {}

    /** Load the server's GT translations over the bundled fallback at each server start. */
    public static void reload(File gregtechChineseFile) {
        Map<String, String> names = new HashMap<>(bundledTranslations());
        if (gregtechChineseFile.isFile()) {
            try (
                BufferedReader reader = Files.newBufferedReader(gregtechChineseFile.toPath(), StandardCharsets.UTF_8)) {
                readTranslations(reader, names);
            } catch (IOException error) {
                FutaGtnhMod.LOG.warn("读取探矿仪中文材料译名失败，使用内置译名：{}", gregtechChineseFile, error);
            }
        }
        translations = Collections.unmodifiableMap(names);
        ORE_NAMES.clear();
        FutaGtnhMod.LOG.info("探矿仪服务端中文矿名已启用，加载 {} 个材料译名", names.size());
    }

    public static String getOreName(Block block, int metadata, String originalName) {
        Map<Integer, String> names = ORE_NAMES.computeIfAbsent(block, ignored -> new ConcurrentHashMap<>());
        return names.computeIfAbsent(metadata, ignored -> resolveOreName(block, metadata, originalName));
    }

    private static String resolveOreName(Block block, int metadata, String originalName) {
        // OreInfo instances are pooled; always return them, including on missing translations.
        try (OreInfo<?> info = OreManager.getOreInfo(block, metadata)) {
            if (info == null || info.material == null) return originalName;
            return formatOreName(info.material.getLocalizedNameKey(), info.isSmall, originalName);
        }
    }

    static String formatOreName(String materialKey, boolean small, String originalName) {
        String material = translations.get(materialKey);
        if (material == null || material.isEmpty()) return originalName;
        return (small ? "小型" : "") + material + "矿石";
    }

    private static Map<String, String> bundledTranslations() {
        Map<String, String> names = new HashMap<>();
        try (InputStream stream = ProspectorChineseNames.class.getResourceAsStream(DICTIONARY)) {
            if (stream == null) throw new IOException("Missing " + DICTIONARY);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                readTranslations(reader, names);
            }
        } catch (IOException error) {
            FutaGtnhMod.LOG.warn("加载探矿仪内置中文材料译名失败，未翻译的矿石保留原名", error);
        }
        return Collections.unmodifiableMap(names);
    }

    /** Accept both ordinary UTF-8 lang entries and GT's Forge configuration S:key=value entries. */
    private static void readTranslations(BufferedReader reader, Map<String, String> names) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("S:")) line = line.substring(2);
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            String key = line.substring(0, separator)
                .trim();
            if (key.startsWith("\"") && key.endsWith("\"")) key = key.substring(1, key.length() - 1);
            if (!key.startsWith("Material.") || key.endsWith(".ChemicalFormula")) continue;
            String value = line.substring(separator + 1)
                .trim();
            if (!value.isEmpty()) names.put(key, value);
        }
    }
}
