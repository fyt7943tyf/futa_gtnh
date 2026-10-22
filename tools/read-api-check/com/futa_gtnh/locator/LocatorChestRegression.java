package com.futa_gtnh.locator;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;

import com.futa_gtnh.Config;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLInjectionData;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

/** Real incremental locator scans over loaded chunk fixtures and vanilla chest inventories. */
public final class LocatorChestRegression {

    private static final UUID PLAYER = UUID.randomUUID();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        Bootstrap.func_151354_b();
        Field mods = Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        mods.set(Loader.instance(), Collections.emptyMap());
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, new File("build/locator-chest-check"));
        Config.locatorSearchRadius = 3;
        Config.locatorBlocksPerTick = 1000;
        Config.locatorScanTimeoutTicks = 100;

        for (Block type : new Block[] { Blocks.chest, Blocks.trapped_chest }) {
            for (int facing : new int[] { 0, 2, 3, 4, 5 }) {
                for (boolean filled : new boolean[] { false, true }) {
                    TestWorld world = new TestWorld();
                    TileEntityChest chest = world.chest(1, 4, 0, type, facing, filled ? new ItemStack(Items.diamond, 9) : null);
                    NBTTagCompound before = new NBTTagCompound();
                    chest.writeToNBT(before);
                    LocatorScan scan = finish(new LocatorScan(world, PLAYER, new ItemStack(type), 0, 4, 0));
                    check(scan.hasResult(), "placed chest found: " + type + " facing=" + facing + " filled=" + filled);
                    check(scan.getBestX() == 1 && scan.getBestY() == 4 && scan.getBestZ() == 0, "exact chest coordinates");
                    check(scan.targetStillAt(1, 4, 0), "tracking recheck uses the same block identity");
                    world.put(1, 4, 0, type, facing == 2 ? 5 : 2, chest);
                    check(scan.targetStillAt(1, 4, 0), "rotating a chest does not lose tracking");
                    LocatorScan repeated = finish(scan.restartAt(0, 4, 0));
                    check(repeated.hasResult(), "repeat search preserves chest match");
                    NBTTagCompound after = new NBTTagCompound();
                    chest.writeToNBT(after);
                    check(before.equals(after), "scan does not change chest items or NBT");
                }
            }
        }

        TestWorld distinct = new TestWorld();
        distinct.chest(0, 4, 0, Blocks.trapped_chest, 3, new ItemStack(Items.diamond));
        check(!finish(new LocatorScan(distinct, PLAYER, new ItemStack(Blocks.chest), 0, 4, 0)).hasResult(), "trapped chest is not a normal chest");
        distinct.put(0, 4, 0, Blocks.wool, 14, null);
        check(!finish(new LocatorScan(distinct, PLAYER, new ItemStack(Blocks.wool, 1, 0), 0, 4, 0)).hasResult(), "different wool colors stay distinct");
        check(finish(new LocatorScan(distinct, PLAYER, new ItemStack(Blocks.wool, 1, 14), 0, 4, 0)).hasResult(), "same wool color still matches");
        TestWorld negative = new TestWorld();
        negative.chest(-1, 4, -1, Blocks.chest, 5, new ItemStack(Items.diamond));
        LocatorScan negativeScan = finish(new LocatorScan(negative, PLAYER, new ItemStack(Blocks.chest), 0, 4, 0));
        check(negativeScan.hasResult() && negativeScan.getBestX() == -1 && negativeScan.getBestZ() == -1, "negative coordinates and chunk boundaries");

        TestWorld inventories = new TestWorld();
        inventories.chest(0, 4, 0, Blocks.chest, 2, null);
        inventories.chest(1, 4, 0, Blocks.chest, 4, new ItemStack(Items.diamond, 3));
        inventories.chest(2, 4, 0, Blocks.chest, 5, new ItemStack(Items.diamond, 8));
        LocatorScan item = finish(new LocatorScan(inventories, PLAYER, new ItemStack(Items.diamond), 0, 4, 0, true));
        check(item.hasResult() && item.getBestX() == 1, "item mode finds nearest filled chest and skips empty chest");
        check(!finish(new LocatorScan(inventories, PLAYER, new ItemStack(Items.emerald), 0, 4, 0, true)).hasResult(), "missing item does not match");
        TestWorld unloaded = new TestWorld();
        unloaded.chest(1, 4, 0, Blocks.chest, 5, new ItemStack(Items.diamond));
        unloaded.loaded = false;
        check(!finish(new LocatorScan(unloaded, PLAYER, new ItemStack(Blocks.chest), 0, 4, 0)).hasResult(), "block mode skips unloaded chunks");
        check(!finish(new LocatorScan(unloaded, PLAYER, new ItemStack(Items.diamond), 0, 4, 0, true)).hasResult(), "item mode skips unloaded chunks");
        check(unloaded.provided == 0 && unloaded.tileReads == 0, "search never loads or reads an unloaded chunk");
        System.out.println("Locator chest regression: " + checks + " checks passed (all facings, contents, tracking, variants and loaded chunks).");
    }

    private static LocatorScan finish(LocatorScan scan) {
        for (int i = 0; i < 100 && !scan.isDone(); i++) scan.tick();
        check(scan.isDone(), "incremental scan finishes within budget");
        return scan;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }

    private static final class Cell {
        final Block block;
        final int metadata;
        final TileEntity tile;

        Cell(Block block, int metadata, TileEntity tile) {
            this.block = block;
            this.metadata = metadata;
            this.tile = tile;
        }
    }

    private static final class TestWorld extends World {
        final Map<String, Cell> cells = new HashMap<>();
        final Map<String, Chunk> chunks = new HashMap<>();
        final IChunkProvider providerFixture;
        boolean loaded = true;
        int provided;
        int tileReads;

        TestWorld() {
            super(new SaveHandlerMP(), "locator-chest-check", new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                new WorldProviderSurface(), new Profiler());
            providerFixture = (IChunkProvider) Proxy.newProxyInstance(IChunkProvider.class.getClassLoader(), new Class<?>[] { IChunkProvider.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("chunkExists")) return loaded && chunks.containsKey(args[0] + ":" + args[1]);
                    if (method.getName().equals("provideChunk")) {
                        if (!loaded) throw new AssertionError("unloaded chunk requested");
                        provided++;
                        return chunks.get(args[0] + ":" + args[1]);
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        }

        void put(int x, int y, int z, Block block, int metadata, TileEntity tile) {
            cells.put(key(x, y, z), new Cell(block, metadata, tile));
            chunks.computeIfAbsent((x >> 4) + ":" + (z >> 4), ignored -> new TestChunk(this, x >> 4, z >> 4));
            if (tile != null) {
                tile.xCoord = x; tile.yCoord = y; tile.zCoord = z; tile.setWorldObj(this);
            }
        }

        TileEntityChest chest(int x, int y, int z, Block block, int facing, ItemStack contents) {
            TileEntityChest chest = new TileEntityChest();
            if (contents != null) chest.setInventorySlotContents(5, contents);
            put(x, y, z, block, facing, chest);
            return chest;
        }

        @Override protected IChunkProvider createChunkProvider() { return null; }
        @Override protected int func_152379_p() { return 0; }
        @Override public Entity getEntityByID(int id) { return null; }
        @Override public int getHeight() { return 16; }
        @Override public IChunkProvider getChunkProvider() { return providerFixture; }
        @Override public Block getBlock(int x, int y, int z) {
            Cell cell = cells.get(key(x, y, z)); return cell == null ? Blocks.air : cell.block;
        }
        @Override public int getBlockMetadata(int x, int y, int z) {
            Cell cell = cells.get(key(x, y, z)); return cell == null ? 0 : cell.metadata;
        }
        @Override public TileEntity getTileEntity(int x, int y, int z) {
            tileReads++;
            Cell cell = cells.get(key(x, y, z)); return cell == null ? null : cell.tile;
        }
    }

    private static final class TestChunk extends Chunk {
        final TestWorld source;
        TestChunk(TestWorld world, int x, int z) { super(world, x, z); source = world; }
        @Override public Block getBlock(int x, int y, int z) { return source.getBlock((xPosition << 4) + x, y, (zPosition << 4) + z); }
        @Override public int getBlockMetadata(int x, int y, int z) { return source.getBlockMetadata((xPosition << 4) + x, y, (zPosition << 4) + z); }
    }
}
