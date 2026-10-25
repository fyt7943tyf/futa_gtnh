package com.futa_gtnh.common;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

/** Wake dormant vanilla flows without deleting sources or changing the pump's fluid accounting. */
public final class PumpFluidUpdates {

    private static final int[][] NEIGHBORS = { { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 },
        { 0, 0, 1 } };
    private static final int SCAN_BUDGET = 512;
    private static final int UPDATE_BUDGET = 64;
    public static final int MAX_SCAN_RADIUS = 32;
    private static final Map<World, Scan> SCANS = new WeakHashMap<>();

    private PumpFluidUpdates() {}

    /** Retain GT's original flags and return value; only successful vanilla fluid removals need this hook. */
    public static boolean setPumpBlock(World world, int x, int y, int z, Block replacement, int metadata, int flags) {
        Block removed = world.getBlock(x, y, z);
        boolean changed = world.setBlock(x, y, z, replacement, metadata, flags);
        if (changed && !world.isRemote
            && replacement == Blocks.air
            && (removed == Blocks.lava || removed == Blocks.flowing_lava
                || removed == Blocks.water
                || removed == Blocks.flowing_water)) {
            for (int[] offset : NEIGHBORS) refreshFlow(world, x + offset[0], y + offset[1], z + offset[2]);
        }
        return changed;
    }

    /** Mirror vanilla's stationary-to-flowing transition, then schedule the normal delayed fluid tick. */
    public static boolean refreshFlow(World world, int x, int y, int z) {
        if (world == null || world.isRemote
            || y < 0
            || y >= 256
            || !world.blockExists(x, y, z)
            || !world.checkChunksExist(x - 1, 0, z - 1, x + 1, 0, z + 1)) return false;
        Block block = world.getBlock(x, y, z);
        Block flowing;
        if (block == Blocks.lava || block == Blocks.flowing_lava) flowing = Blocks.flowing_lava;
        else if (block == Blocks.water || block == Blocks.flowing_water) flowing = Blocks.flowing_water;
        else return false;
        int metadata = world.getBlockMetadata(x, y, z);
        if (metadata == 0) return false;
        // Flag 2 synchronizes the block without synchronously waking an entire lake (or its source blocks).
        if (block != flowing && !world.setBlock(x, y, z, flowing, metadata, 2)) return false;
        world.scheduleBlockUpdate(x, y, z, flowing, flowing.tickRate(world));
        world.markBlockForUpdate(x, y, z);
        return true;
    }

    /** One bounded, loaded-chunk-only repair scan per dimension. All access is on the server thread. */
    public static boolean startScan(World world, int x, int y, int z, int radius, ICommandSender sender) {
        if (world == null || world.isRemote || radius < 1 || radius > MAX_SCAN_RADIUS || SCANS.containsKey(world))
            return false;
        SCANS.put(world, new Scan(x, y, z, radius, sender));
        return true;
    }

    public static void tickWorld(World world) {
        if (world == null || world.isRemote) return;
        Scan scan = SCANS.get(world);
        if (scan == null) return;
        int updated = 0;
        for (int examined = 0; examined < SCAN_BUDGET && updated < UPDATE_BUDGET
            && scan.cursor < scan.volume; examined++) {
            int cursor = scan.cursor++;
            int x = scan.minX + cursor % scan.width;
            int z = scan.minZ + cursor / scan.width % scan.width;
            int y = scan.minY + cursor / (scan.width * scan.width);
            if (refreshFlow(world, x, y, z)) {
                updated++;
                scan.refreshed++;
            }
        }
        if (scan.cursor == scan.volume) {
            SCANS.remove(world);
            ICommandSender sender = scan.sender.get();
            if (sender != null && sender.getEntityWorld() == world) {
                sender.addChatMessage(new ChatComponentText("流体刷新完成：已唤醒 " + scan.refreshed + " 个非源水/岩浆方块，等待原版流体更新。"));
            }
        }
    }

    public static void forget(World world) {
        SCANS.remove(world);
    }

    public static void clear() {
        SCANS.clear();
    }

    private static final class Scan {

        final int minX;
        final int minY;
        final int minZ;
        final int width;
        final int volume;
        final WeakReference<ICommandSender> sender;
        int cursor;
        int refreshed;

        Scan(int x, int y, int z, int radius, ICommandSender sender) {
            minX = x - radius;
            minY = Math.max(0, y - radius);
            minZ = z - radius;
            width = radius * 2 + 1;
            volume = width * width * Math.max(0, Math.min(255, y + radius) - minY + 1);
            this.sender = new WeakReference<>(sender);
        }
    }
}
