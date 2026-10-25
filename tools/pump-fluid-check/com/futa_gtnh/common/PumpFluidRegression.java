package com.futa_gtnh.common;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;

import com.futa_gtnh.command.CommandFluidRefresh;
import com.futa_gtnh.mixins.FutaGtnhMixinPlugin;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

/** Real vanilla liquid ticks in an in-memory world, plus real transformation of GT's pump class. */
public final class PumpFluidRegression {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(net.minecraft.launchwrapper.Launch.classLoader);
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        Bootstrap.func_151354_b();
        transformPump();
        dormantLava();
        sixNeighbors();
        waterAndLiveSources();
        removalGuards();
        scanBoundsAndLifecycle();
        PumpFluidUpdates.clear();
        System.out.println("GT pump fluid refresh: " + checks + " checks passed (real pump Mixin and vanilla liquid ticks)");
    }

    private static void transformPump() throws Exception {
        String name = "gregtech.common.tileentities.machines.basic.MTEPump";
        check(new FutaGtnhMixinPlugin().shouldApplyMixin(name, "com.futa_gtnh.mixins.MixinGTPump"),
            "required GT hook is independent of optional TinkersConstruct");
        Class<?> pump = Class.forName(name, false, net.minecraft.launchwrapper.Launch.classLoader);
        boolean handler = false;
        for (Method method : pump.getDeclaredMethods()) {
            if (method.getName().contains("futa$refreshAdjacentFlows")) handler = true;
        }
        check(handler, "real pinned GT pump transforms and contains the required redirect");
        check(new CommandFluidRefresh().getRequiredPermissionLevel() == 2, "world repair requires OP");
    }

    private static void dormantLava() {
        TestWorld before = channel(Blocks.lava);
        before.setBlock(0, 64, 0, Blocks.air, 0, 2);
        before.runTicks(200);
        check(before.getBlock(1, 64, 0) == Blocks.lava, "reproduced GT flag-2 removal leaving a dormant non-source");

        TestWorld after = channel(Blocks.lava);
        check(PumpFluidUpdates.setPumpBlock(after, 0, 64, 0, Blocks.air, 0, 2), "successful removal keeps GT return value");
        check(after.lastRemovalFlags == 2, "pump's original removal flags preserved");
        check(after.getBlock(1, 64, 0) == Blocks.flowing_lava, "adjacent dormant lava wakes");
        check(after.getBlockMetadata(1, 64, 0) == 2, "waking preserves fluid level");
        check(after.getBlock(3, 64, 0) == Blocks.lava, "no synchronous flood-fill of the lake");
        after.runTicks(600);
        for (int x = 0; x <= 3; x++) check(after.getBlock(x, 64, 0) == Blocks.air, "source-free channel recedes using vanilla ticks");

        TestWorld dynamic = new TestWorld();
        dynamic.put(0, 64, 0, Blocks.flowing_lava, 6);
        check(PumpFluidUpdates.refreshFlow(dynamic, 0, 64, 0), "already-dynamic lava with a lost tick is scheduled again");
        dynamic.runTicks(100);
        check(dynamic.getBlock(0, 64, 0) == Blocks.air, "already-dynamic orphan lava disappears");
    }

    private static void waterAndLiveSources() {
        TestWorld water = channel(Blocks.water);
        PumpFluidUpdates.setPumpBlock(water, 0, 64, 0, Blocks.air, 0, 2);
        water.runTicks(200);
        for (int x = 0; x <= 3; x++) check(water.getBlock(x, 64, 0) == Blocks.air, "water follows its normal decay too");

        TestWorld live = channel(Blocks.lava);
        check(!PumpFluidUpdates.refreshFlow(live, 0, 64, 0), "source blocks are not forcibly awakened or removed");
        PumpFluidUpdates.refreshFlow(live, 1, 64, 0);
        live.runTicks(300);
        check(live.getBlock(0, 64, 0) == Blocks.lava && live.getBlockMetadata(0, 64, 0) == 0, "lava source retained");
        check(live.getBlock(1, 64, 0) != Blocks.air, "a flow still supplied by a source survives normal updates");
    }

    private static void sixNeighbors() {
        TestWorld world = new TestWorld();
        world.put(0,64,0,Blocks.lava,0);
        int[][] offsets = {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for (int[] d : offsets) world.put(d[0],64+d[1],d[2],Blocks.lava,8);
        PumpFluidUpdates.setPumpBlock(world,0,64,0,Blocks.air,0,2);
        check(world.scheduled.size()==6,"all six neighbors, including falling lava above and below, are scheduled");
        for (int[] d : offsets) check(world.getBlockMetadata(d[0],64+d[1],d[2])==8,"falling level preserved");
        TestWorld replacement = channel(Blocks.lava);
        PumpFluidUpdates.setPumpBlock(replacement,0,64,0,Blocks.stone,0,2);
        check(replacement.scheduled.isEmpty(),"a non-air replacement retains original behavior");
    }

    private static void removalGuards() {
        TestWorld world = channel(Blocks.lava);
        world.failWrites = true;
        check(!PumpFluidUpdates.setPumpBlock(world, 0, 64, 0, Blocks.air, 0, 2), "failed removal stays false");
        check(world.scheduled.isEmpty(), "failed removal does not wake neighbors");
        world.failWrites = false;
        world.isRemote = true;
        PumpFluidUpdates.setPumpBlock(world, 0, 64, 0, Blocks.air, 0, 2);
        check(world.scheduled.isEmpty(), "client does not schedule world physics");
        world.isRemote = false;
        world.put(0, 64, 0, Blocks.stone, 0);
        PumpFluidUpdates.setPumpBlock(world, 0, 64, 0, Blocks.air, 0, 2);
        check(world.scheduled.isEmpty(), "unrelated blocks do not trigger the fluid hook");
        world.loaded = false;
        check(!PumpFluidUpdates.refreshFlow(world, 1, 64, 0), "unloaded target skipped without requesting a chunk");
        world.loaded = true;
        world.neighborsLoaded = false;
        check(!PumpFluidUpdates.refreshFlow(world, 1, 64, 0), "unloaded adjacent chunk skipped too");
        world.neighborsLoaded = true;
        check(!PumpFluidUpdates.refreshFlow(world, 0, -1, 0), "below-world coordinate skipped");
        check(!PumpFluidUpdates.refreshFlow(world, 0, 256, 0), "above-world coordinate skipped");
        check(!PumpFluidUpdates.refreshFlow(world, 0, 64, 0), "air is unchanged");
        Block modFluid = new OtherFluid();
        world.put(0, 64, 0, modFluid, 4);
        check(!PumpFluidUpdates.refreshFlow(world, 0, 64, 0), "modded fluids retain their own behavior");
        PumpFluidUpdates.setPumpBlock(world, 0, 64, 0, Blocks.air, 0, 2);
        check(world.scheduled.isEmpty(), "modded fluid removal does not wake vanilla neighbors");
    }

    private static void scanBoundsAndLifecycle() {
        PumpFluidUpdates.clear();
        TestWorld world = new TestWorld();
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) world.put(x, 48, z, Blocks.lava, 4);
        world.put(0, 49, 0, Blocks.lava, 0);
        check(!PumpFluidUpdates.startScan(world, 0, 64, 0, 33, null), "scan radius bounded");
        check(PumpFluidUpdates.startScan(world, 0, 64, 0, 16, null), "repair scan accepted");
        check(!PumpFluidUpdates.startScan(world, 0, 64, 0, 16, null), "duplicate dimension scan refused");
        ModEventHandler handler = new ModEventHandler();
        handler.onWorldTick(new TickEvent.WorldTickEvent(Side.SERVER, TickEvent.Phase.START, world));
        check(world.scheduled.isEmpty(), "repair runs only at world tick END");
        handler.onWorldTick(new TickEvent.WorldTickEvent(Side.SERVER, TickEvent.Phase.END, world));
        check(world.scheduled.size() == 64, "dense scan caps updates at 64 per tick");
        int ticks = 0;
        while (!PumpFluidUpdates.startScan(world, 0, 64, 0, 1, null) && ticks++ < 100) PumpFluidUpdates.tickWorld(world);
        check(ticks < 100, "incremental scan completes");
        check(world.scheduled.size() == 33 * 33, "every non-source in the requested cube refreshed");
        check(world.getBlock(0, 49, 0) == Blocks.lava && world.getBlockMetadata(0, 49, 0) == 0, "scan preserves sources");
        PumpFluidUpdates.forget(world);
        check(PumpFluidUpdates.startScan(world, 0, 64, 0, 1, null), "world unload cancels pending work");
        PumpFluidUpdates.clear();
        check(PumpFluidUpdates.startScan(world, 0, 64, 0, 1, null), "server stop clears old jobs");
        PumpFluidUpdates.clear();
        TestWorld empty = new TestWorld();
        PumpFluidUpdates.startScan(empty, 0, 0, 0, 16, null);
        empty.reads = 0;
        PumpFluidUpdates.tickWorld(empty);
        check(empty.reads == 512, "empty scan caps examined positions at 512 per tick");
        check(empty.badY == 0, "bottom-of-world scan clips Y");
    }

    private static TestWorld channel(Block fluid) {
        TestWorld world = new TestWorld();
        for (int x = 0; x <= 3; x++) world.put(x, 64, 0, fluid, x * 2);
        return world;
    }

    private static void check(boolean success, String description) {
        if (!success) throw new AssertionError(description);
        checks++;
    }

    private static final class Cell {
        final Block block;
        final int metadata;
        Cell(Block block, int metadata) { this.block = block; this.metadata = metadata; }
    }

    private static final class OtherFluid extends Block {
        OtherFluid() { super(Material.lava); }
    }

    private static final class Scheduled {
        final int x, y, z;
        final Block block;
        final long due;
        Scheduled(int x, int y, int z, Block block, long due) { this.x=x; this.y=y; this.z=z; this.block=block; this.due=due; }
    }

    private static final class TestWorld extends World {
        final Map<ChunkPosition, Cell> cells = new HashMap<>();
        final List<Scheduled> scheduled = new ArrayList<>();
        long time;
        int lastRemovalFlags;
        int reads;
        int badY;
        boolean loaded = true;
        boolean neighborsLoaded = true;
        boolean failWrites;

        TestWorld() {
            super(new SaveHandlerMP(), "pump-fluid-check",
                new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                new WorldProviderSurface(), new Profiler());
        }
        void put(int x, int y, int z, Block block, int meta) { cells.put(new ChunkPosition(x,y,z), new Cell(block,meta)); }
        void runTicks(int count) {
            Random random = new Random(12);
            for (int tick=0; tick<count; tick++) {
                time++;
                List<Scheduled> ready = new ArrayList<>();
                scheduled.removeIf(entry -> { if (entry.due <= time) { ready.add(entry); return true; } return false; });
                for (Scheduled entry : ready) if (getBlock(entry.x,entry.y,entry.z)==entry.block)
                    entry.block.updateTick(this,entry.x,entry.y,entry.z,random);
            }
        }
        @Override protected IChunkProvider createChunkProvider() { return null; }
        @Override protected int func_152379_p() { return 0; }
        @Override public Entity getEntityByID(int id) { return null; }
        @Override public boolean blockExists(int x,int y,int z) { return loaded && y>=0 && y<256; }
        @Override public boolean checkChunksExist(int a,int b,int c,int d,int e,int f) { return loaded && neighborsLoaded; }
        @Override public long getTotalWorldTime() { return time; }
        @Override public Block getBlock(int x,int y,int z) {
            if (!loaded) throw new AssertionError("unloaded chunk requested");
            reads++; if (y<0 || y>255) badY++;
            Cell cell=cells.get(new ChunkPosition(x,y,z)); return cell==null ? Blocks.stone : cell.block;
        }
        @Override public int getBlockMetadata(int x,int y,int z) {
            Cell cell=cells.get(new ChunkPosition(x,y,z)); return cell==null ? 0 : cell.metadata;
        }
        @Override public boolean setBlock(int x,int y,int z,Block block,int meta,int flags) {
            if (failWrites) return false;
            Block old=getBlock(x,y,z);
            if (old==block && getBlockMetadata(x,y,z)==meta) return false;
            put(x,y,z,block,meta);
            if (block==Blocks.air) lastRemovalFlags=flags;
            if (block!=old) block.onBlockAdded(this,x,y,z);
            if ((flags&1)!=0) notifyBlocksOfNeighborChange(x,y,z,old);
            return true;
        }
        @Override public boolean setBlockMetadataWithNotify(int x,int y,int z,int meta,int flags) {
            put(x,y,z,getBlock(x,y,z),meta); return true;
        }
        @Override public void notifyBlocksOfNeighborChange(int x,int y,int z,Block block) {
            for (int[] d : new int[][] {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}})
                getBlock(x+d[0],y+d[1],z+d[2]).onNeighborBlockChange(this,x+d[0],y+d[1],z+d[2],block);
        }
        @Override public void scheduleBlockUpdate(int x,int y,int z,Block block,int delay) {
            for (Scheduled entry : scheduled) if (entry.x==x && entry.y==y && entry.z==z && entry.block==block) return;
            scheduled.add(new Scheduled(x,y,z,block,time+delay));
        }
        @Override public void markBlockForUpdate(int x,int y,int z) {}
        @Override public void playSoundEffect(double x,double y,double z,String sound,float volume,float pitch) {}
        @Override public void spawnParticle(String type,double x,double y,double z,double a,double b,double c) {}
    }
}
