package com.futa_gtnh.bees;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.DimensionManager;

import com.futa_gtnh.FutaGtnhMod;
import com.google.gson.Gson;

/** Keeps optional Gendustry, Forestry and Scala types out of FUTA's binary API. */
final class MutatronAccess {

    static final Gson JSON = new Gson();
    private final Class<?> advanced = Class.forName("net.bdew.gendustry.api.blocks.IAdvancedMutatron");
    private final Class<?> rootType = Class.forName("forestry.api.apiculture.IBeeRoot");
    private final Class<?> mutationType = Class.forName("forestry.api.genetics.IMutation");
    private final Class<?> alleleType = Class.forName("forestry.api.genetics.IAllele");
    private final Class<?> beeType = Class.forName("forestry.api.apiculture.IBee");
    private final Class<?> genomeType = Class.forName("forestry.api.apiculture.IBeeGenome");
    private final Method getCandidates = advanced.getMethod("getPossibleMutations");
    private final Method setMutation = advanced.getMethod("setMutation", int.class);
    private final Method getParent1 = advanced.getMethod("getParent1");
    private final Method getParent2 = advanced.getMethod("getParent2");
    private final Method isWorking = advanced.getMethod("isWorking");
    private final Method getProgress = advanced.getMethod("getProgress");
    private final Method getMember = rootType.getMethod("getMember", ItemStack.class);
    private final Method getGenome = beeType.getMethod("getGenome");
    private final Method getPrimary = genomeType.getMethod("getPrimary");
    private final Method getUid = alleleType.getMethod("getUID");
    private final Method getAllele0 = mutationType.getMethod("getAllele0");
    private final Method getAllele1 = mutationType.getMethod("getAllele1");
    private final Method getMutations = rootType.getMethod("getMutations", boolean.class);
    private final Class<?> logic = Class.forName("net.bdew.gendustry.forestry.GeneticsHelper$");
    private final Method getMutationSpecies = logic.getMethod("getMutationSpecies", mutationType);
    private final Method getValidMutations = logic.getMethod(
        "getValidMutations",
        ItemStack.class,
        ItemStack.class,
        Class.forName("forestry.api.apiculture.IBeeHousing"));
    private final Class<?> seq = Class.forName("scala.collection.Seq");
    private final Method seqSize = seq.getMethod("size");
    private final Method seqAt = seq.getMethod("apply", int.class);
    private final Method getTemplate = rootType.getMethod("getTemplate", String.class);
    private final Method asIndividual = rootType.getMethod(
        "templateAsIndividual",
        Array.newInstance(alleleType, 0)
            .getClass());
    private final Method getStack = rootType
        .getMethod("getMemberStack", Class.forName("forestry.api.genetics.IIndividual"), int.class);
    private final Class<?> machine = Class.forName("net.bdew.gendustry.machines.mutatron.MachineMutatron$");
    private final Method getOverrides = machine.getMethod("mutatronOverrides");
    private final Method getRule = Class.forName("scala.Function1")
        .getMethod("apply", Object.class);
    private Catalog cached;
    private File savedWorld;

    MutatronAccess() throws ReflectiveOperationException {}

    boolean isAdvanced(TileEntity tile) {
        return advanced.isInstance(tile);
    }

    boolean working(TileEntity tile) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(isWorking.invoke(tile));
    }

    Object progress(TileEntity tile) throws ReflectiveOperationException {
        return getProgress.invoke(tile);
    }

    Map<?, ?> candidates(TileEntity tile) throws ReflectiveOperationException {
        return (Map<?, ?>) getCandidates.invoke(tile);
    }

    ItemStack parent(TileEntity tile, boolean princess) throws ReflectiveOperationException {
        return (ItemStack) (princess ? getParent1 : getParent2).invoke(tile);
    }

    void select(TileEntity tile, int slot) throws ReflectiveOperationException {
        setMutation.invoke(tile, slot);
    }

    boolean eligible(TileEntity tile, String target, String princess, String drone)
        throws ReflectiveOperationException {
        Object root = beeRoot();
        ItemStack p = previewParent(root, princess, 1);
        ItemStack d = previewParent(root, drone, 0);
        if (p == null || d == null) return false;
        return hasCandidate(tile, target, p, d);
    }

    boolean hasCandidate(TileEntity tile, String target, ItemStack princess, ItemStack drone)
        throws ReflectiveOperationException {
        Object helper = logic.getField("MODULE$")
            .get(null);
        Object housing = tile.getClass()
            .getMethod("fakeBeeHousing")
            .invoke(tile);
        Object valid = getValidMutations.invoke(helper, princess, drone, housing);
        int size = (int) seqSize.invoke(valid);
        // The advanced machine has six selector slots.
        for (int i = 0; i < Math.min(size, 6); i++) {
            if (target.equals(uid(getMutationSpecies.invoke(helper, seqAt.invoke(valid, i))))) return true;
        }
        return false;
    }

    private ItemStack previewParent(Object root, String species, int type) throws ReflectiveOperationException {
        Object template = getTemplate.invoke(root, species);
        return template == null ? null : (ItemStack) getStack.invoke(root, asIndividual.invoke(root, template), type);
    }

    String primary(ItemStack stack) throws ReflectiveOperationException {
        if (stack == null) return null;
        Object bee = getMember.invoke(beeRoot(), stack);
        return bee == null ? null : uid(getPrimary.invoke(getGenome.invoke(bee)));
    }

    private Object beeRoot() throws ReflectiveOperationException {
        Object root = Class.forName("forestry.api.apiculture.BeeManager")
            .getField("beeRoot")
            .get(null);
        if (root == null) throw new IllegalStateException("Forestry bee registry is not available yet");
        return root;
    }

    private String uid(Object allele) throws ReflectiveOperationException {
        return allele == null ? null : (String) getUid.invoke(allele);
    }

    Catalog catalog(TileEntity tile) throws ReflectiveOperationException {
        if (tile.getWorldObj().isRemote) throw new IllegalStateException("Server-side catalog required");
        File world = DimensionManager.getCurrentSaveRootDirectory();
        if (cached != null && java.util.Objects.equals(world, savedWorld)) return cached;

        Object helper = logic.getField("MODULE$")
            .get(null);
        Object overrides = getOverrides.invoke(
            machine.getField("MODULE$")
                .get(null));
        Map<String, Set<Recipe>> ordered = new TreeMap<>();
        for (Object mutation : (Collection<?>) getMutations.invoke(beeRoot(), true)) {
            String target = uid(getMutationSpecies.invoke(helper, mutation));
            String a = uid(getAllele0.invoke(mutation));
            String b = uid(getAllele1.invoke(mutation));
            if (target == null || a == null || b == null) continue;
            String rule = String.valueOf(getRule.invoke(overrides, target));
            if (!"ENABLED".equals(rule) && !"REQUIREMENTS".equals(rule)) continue;
            Recipe recipe = a.compareTo(b) <= 0 ? new Recipe(a, b, rule) : new Recipe(b, a, rule);
            ordered.computeIfAbsent(target, key -> new TreeSet<>())
                .add(recipe);
        }
        String payload = JSON.toJson(ordered);
        String version = hash(payload);
        if (world != null) {
            try {
                Path file = world.toPath()
                    .resolve("futa_gtnh/mutatron-recipes.json");
                Files.createDirectories(file.getParent());
                Map<String, Object> document = new TreeMap<>();
                document.put("schema", 1);
                document.put("version", version);
                document.put("recipes", ordered);
                Path temp = Files.createTempFile(file.getParent(), "mutatron-recipes-", ".tmp");
                try {
                    Files.write(
                        temp,
                        JSON.toJson(document)
                            .getBytes(StandardCharsets.UTF_8));
                    try {
                        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException e) {
                FutaGtnhMod.LOG.warn("Could not save the bee mutation catalog; keeping the in-memory copy", e);
            }
        }
        Map<String, String> slices = new TreeMap<>();
        ordered.forEach((target, recipes) -> slices.put(target, JSON.toJson(recipes)));
        savedWorld = world;
        cached = new Catalog(version, slices);
        return cached;
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Recipe implements Comparable<Recipe> {

        private final List<String> parents;
        private final String rule;

        Recipe(String first, String second, String rule) {
            parents = Arrays.asList(first, second);
            this.rule = rule;
        }

        @Override
        public int compareTo(Recipe other) {
            int first = parents.get(0)
                .compareTo(other.parents.get(0));
            if (first != 0) return first;
            int second = parents.get(1)
                .compareTo(other.parents.get(1));
            return second == 0 ? rule.compareTo(other.rule) : second;
        }
    }

    static final class Catalog {

        private final String version;
        private final Map<String, String> byTarget;

        Catalog(String version, Map<String, String> byTarget) {
            this.version = version;
            this.byTarget = byTarget;
        }

        String version() {
            return version;
        }

        String recipes(String uid) {
            return byTarget.getOrDefault(uid, "[]");
        }
    }
}
