package com.futa_gtnh.client;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.LanguageManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.client.nei.NeiStorageSearch;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import codechicken.nei.ItemList;
import codechicken.nei.ItemStackMap;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.SearchField;
import codechicken.nei.api.ItemInfo;
import codechicken.nei.recipe.StackInfo;
import codechicken.nei.search.IdentifierFilter;
import codechicken.nei.search.OreDictionaryFilter;
import codechicken.nei.search.TooltipFilter;
import codechicken.nei.util.ItemUntranslator;
import cpw.mods.fml.common.Loader;
import sun.misc.Unsafe;

/** Exercises the real shared NEI parser/providers, English index and GT fluid stack identity. */
public final class TerminalNeiSearchRegression {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        File home = new File("build/nei-search-regression"); home.mkdirs();
        set(cpw.mods.fml.relauncher.FMLInjectionData.class, null, "minecraftHome", home);
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", home, Collections.emptyList() });
        set(cpw.mods.fml.relauncher.FMLRelaunchLog.class, null, "side", cpw.mods.fml.relauncher.Side.CLIENT);
        set(Loader.class, Loader.instance(), "namedMods", new HashMap<>());
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        Bootstrap.func_151354_b();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        set(Minecraft.class, null, "theMinecraft", mc);
        mc.gameSettings = new GameSettings();
        LanguageManager languages = new LanguageManager(null, "zh_CN");
        Map<String, Language> languageMap = new HashMap<>();
        languageMap.put("zh_CN", new Language("zh_CN", "CN", "中文", false));
        set(LanguageManager.class, languages, "languageMap", languageMap);
        set(Minecraft.class, mc, "mcLanguageManager", languages);
        NEIClientConfig.world = NEIClientConfig.global;
        NEIClientConfig.setIntSetting("inventory.search.patternMode", 1);
        NEIClientConfig.setIntSetting("inventory.search.spaceMode", 0);
        NEIClientConfig.getSetting("inventory.search.quoteDropItemName").setBooleanValue(true);
        NEIClientConfig.setIntSetting("inventory.search.oreDictSearchMode", 0);
        NEIClientConfig.setIntSetting("inventory.search.tooltipSearchMode", 0);
        NEIClientConfig.setIntSetting("inventory.search.identifierSearchMode", 0);
        SearchField.searchParser.addProvider(new SearchField.SearchParserProvider('\0', "name", EnumChatFormatting.RESET, ItemList.PatternItemFilter::new) {
            public codechicken.nei.SearchTokenParser.SearchMode getSearchMode() { return codechicken.nei.SearchTokenParser.SearchMode.ALWAYS; }
        });
        SearchField.searchParser.addProvider(new SearchField.SearchParserProvider('$', "oreDict", EnumChatFormatting.AQUA, OreDictionaryFilter::new));
        SearchField.searchParser.addProvider(new SearchField.SearchParserProvider('#', "tooltip", EnumChatFormatting.YELLOW, TooltipFilter::new));
        SearchField.searchParser.addProvider(new SearchField.SearchParserProvider('&', "identifier", EnumChatFormatting.GOLD, IdentifierFilter::new));

        Board boardItem = new Board();
        Item.itemRegistry.addObject(4200, "gregtech:search_board", boardItem);
        ItemStack board = new ItemStack(boardItem, 1, 7);
        StorageViewEntry entry = StorageViewEntry.ofItem(ItemKey.of(board), 400);
        ItemStack other = new ItemStack(net.minecraft.init.Items.iron_ingot);
        StorageViewEntry iron = StorageViewEntry.ofItem(ItemKey.of(other), 100);
        OreDictionary.registerOre("circuitBoardRegression", board);
        ItemInfo.itemAliases.put(board, Arrays.asList("boardalias"));
        Map<String, String> english = (Map<String, String>) get(ItemUntranslator.class, ItemUntranslator.getInstance(), "secondNames");
        // NEI's GUID serializer uses a Forge access transformer for NBTTagCompound.tagMap.
        // This standalone JVM has no AT; prime only its GUID cache, then use the real name lookup.
        ((ItemStackMap<String>) get(StackInfo.class, null, "guidcache")).put(board, "regression-board");
        english.put("regression-board", "Coated Circuit Board");
        new net.moecraft.nechar.NEINecharConfig().loadConfig();
        NeiSearchBridge.install(new NeiStorageSearch());
        check(!NeiSearchBridge.searchFieldExists(), "filter works with no NEI search widget");
        check(match("电路板", entry), "localized name");
        check(match("circuit", entry), "English circuit board name");
        check(match("dianluban", entry), "actual NEChar name provider handles pinyin");
        check(match("boardalias", entry), "NEI aliases");
        english.clear();
        ((Map<?, ?>) get(ItemUntranslator.class, ItemUntranslator.getInstance(), "processedNames")).clear();
        check(match("circuitboardregression", entry), "plain query checks ALWAYS ore dictionary");
        check(match("tooltipmarker", entry), "plain query checks ALWAYS tooltip");
        check(match("4200:7", entry), "identifier includes metadata");
        check(match("circuitboardregression tooltipmarker", entry), "AND can match different ALWAYS fields");
        check(match("missing|电路板", entry), "OR query");
        check(!match("-电路板", entry), "negation");
        check(match("tool*marker", entry), "extended wildcard");
        check(match("r/tool.*marker/", entry), "extended regex");
        check(!match("r/[/", entry), "invalid regex does not fall back or match everything");
        Object oldConfiguration = NeiSearchBridge.configurationToken();
        check(oldConfiguration == NeiSearchBridge.configurationToken(), "unchanged config token is stable");
        NEIClientConfig.setIntSetting("inventory.search.oreDictSearchMode", 1);
        NEIClientConfig.setIntSetting("inventory.search.tooltipSearchMode", 1);
        NEIClientConfig.setIntSetting("inventory.search.identifierSearchMode", 1);
        SearchField.searchParser.clearCache();
        check(oldConfiguration != NeiSearchBridge.configurationToken(), "NEI config invalidation refreshes existing queries");
        check(!match("circuitboardregression", entry), "PREFIX mode removes ore dictionary from plain search");
        check(match("$circuitboardregression", entry), "actual NEI ore prefix");
        check(match("#tooltipmarker", entry), "actual NEI tooltip prefix");
        check(match("&4200:7", entry), "actual NEI identifier prefix");
        NEIClientConfig.setIntSetting("inventory.search.oreDictSearchMode", 2);
        SearchField.searchParser.clearCache();
        check(!match("$circuitboardregression", entry), "NEVER mode cannot be forced with prefix");
        NEIClientConfig.setIntSetting("inventory.search.oreDictSearchMode", 1);
        Map<Character, Character> prefixes = (Map<Character, Character>) get(codechicken.nei.SearchTokenParser.class, SearchField.searchParser, "prefixRedefinitions");
        prefixes.put('$', '~');
        SearchField.searchParser.clearCache();
        check(match("~circuitboardregression", entry) && !match("$circuitboardregression", entry), "player prefix remapping");
        SearchField.searchParser.addProvider(new SearchField.SearchParserProvider('%', "testPlugin", EnumChatFormatting.GREEN, p -> stack -> p.matcher("pluginmarker").find()) {
            public codechicken.nei.SearchTokenParser.SearchMode getSearchMode() { return codechicken.nei.SearchTokenParser.SearchMode.PREFIX; }
        });
        check(match("%pluginmarker", entry), "registered plugin provider participates");
        NEIClientConfig.setIntSetting("inventory.search.spaceMode", 1);
        SearchField.searchParser.clearCache();
        check(!match("电路板 涂层", entry), "literal space mode");
        NEIClientConfig.setIntSetting("inventory.search.spaceMode", 0);
        SearchField.searchParser.clearCache();
        check(match("电路板 涂层", entry), "AND space mode follows config");
        NEIClientConfig.setIntSetting("inventory.search.patternMode", 3);
        SearchField.searchParser.clearCache();
        check(match("电路板 -铁", entry) && match("~circuitboardregression", entry), "extended+ actual expression parser");
        NEIClientConfig.setIntSetting("inventory.search.patternMode", 2);
        SearchField.searchParser.clearCache();
        check(match("电路.*", entry), "regex mode");
        ItemStack dropped = board.copy().setStackDisplayName("§aCoated Circuit Board");
        check(NeiSearchBridge.escapedSearchText(dropped).equals(SearchField.getEscapedSearchText(dropped)), "NEI drag quoting/escaping reused");
        check(match(NeiSearchBridge.escapedSearchText(dropped), StorageViewEntry.ofItem(ItemKey.of(dropped), 1)), "dragged name matches in configured regex mode");

        new gregtech.common.items.ItemFluidDisplay();
        Fluid liquid = new Fluid("search_test_water") {
            public String getLocalizedName() { return "测试水"; }
            public String getLocalizedName(net.minecraftforge.fluids.FluidStack stack) { return "测试水"; }
        };
        FluidRegistry.registerFluid(liquid);
        StorageViewEntry water = StorageViewEntry.ofFluid(FluidKey.of(liquid, null), 123456789L);
        check(water != null && StackInfo.isFluidDisplayItem(water.getDisplay()), "real GT fluid display recognized by NEI");
        check(match("&search_test_water", water), "fluid identifier matches actual fluid instead of display item ID");
        check(match("测试水", water), "localized GT fluid name");
        check(StackInfo.getFluid(water.getDisplay()).getFluid() == liquid && water.getAmount() == 123456789L, "search preserves fluid identity and quantity");
        List<StorageViewEntry> filtered = new java.util.ArrayList<>();
        StorageSearch.filter(Arrays.asList(entry, iron), StorageSearch.compile("电路板"), filtered);
        check(filtered.equals(Collections.singletonList(entry)), "filters actual storage entries only");
        check(StorageSearch.compile("") == null, "empty query includes all stored entries");
        check(match("  ", entry) == SearchField.getFilter("  ").matches(entry.getDisplay()), "whitespace follows NEI instead of local trimming");
        NeiSearchBridge.install(null);
        check(match("dianluban", entry), "no-NEI pinyin fallback");
        check(match("*circuitboardregression", entry), "no-NEI legacy ore fallback");
        check(!match("circuitboardregression", entry), "fallback does not silently add NEI ALWAYS fields");
        System.out.println("Terminal NEI search regression: " + assertions + " assertions passed");
    }

    private static boolean match(String text, StorageViewEntry entry) { return StorageSearch.matches(StorageSearch.compile(text), entry); }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); assertions++; }
    private static Object get(Class<?> type, Object target, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Class<?> type, Object target, String name, Object value) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static final class Board extends Item {
        public String getItemStackDisplayName(ItemStack stack) { return "涂层电路板"; }
        public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) { lines.add("tooltipmarker"); }
    }
}
