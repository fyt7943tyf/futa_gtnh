package com.futa_gtnh.supply;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.futa_gtnh.Config;
import com.futa_gtnh.locator.TeleportHelper;

/** Click-only navigation to a server-owned ledger entry; never accept client coordinates or create portals. */
public final class SupplyNavigation {

    private SupplyNavigation() {}

    public static NBTTagCompound navigate(EntityPlayerMP player, Object key, String id) {
        try {
            return perform(player, key, id);
        } catch (RuntimeException error) {
            com.futa_gtnh.FutaGtnhMod.LOG.warn("原料追踪：传送失败", error);
            return failure("travelFailed");
        }
    }

    private static NBTTagCompound perform(EntityPlayerMP player, Object key, String id) {
        if (!SupplyTracker.enabled() || !Config.enableSupplyTeleport) return failure("teleportDisabled");
        SupplySource source = SupplyTracker.LEDGER.source(key, id, SupplyTracker.minute());
        if (source == null || !source.hasPosition()) return failure("noDestination");
        if (player == null || player.isDead || player.worldObj == null || player.ridingEntity != null)
            return failure("cannotTravel");
        WorldServer destination = source.dim == player.dimension && player.worldObj instanceof WorldServer
            ? (WorldServer) player.worldObj
            : DimensionManager.getWorld(source.dim);
        if (destination == null || source.y >= destination.getHeight() - 1
            || !destination.blockExists(source.x, source.y, source.z)) return failure("destinationUnloaded");
        TileEntity tile = destination.getTileEntity(source.x, source.y, source.z);
        if (tile == null || tile.isInvalid()) return failure("destinationGone");
        int[] spot = TeleportHelper.findSafeSpotNearby(destination, player, source.x, source.y, source.z);
        if (spot == null) return failure("noSafeSpot");
        int previousDimension = player.dimension;
        player.closeScreen();
        if (previousDimension != source.dim) {
            // The End exit bypasses Teleporter, so place before player/chunk registration too.
            if (previousDimension == 1) player.setLocationAndAngles(
                spot[0] + 0.5D,
                spot[1],
                spot[2] + 0.5D,
                player.rotationYaw,
                player.rotationPitch);
            player.mcServer.getConfigurationManager()
                .transferPlayerToDimension(player, source.dim, new DirectTeleporter(destination, spot));
            // Vanilla's transferEntityToWorld skips spawning and the teleporter when leaving the End.
            if (previousDimension == 1) {
                player.setLocationAndAngles(
                    spot[0] + 0.5D,
                    spot[1],
                    spot[2] + 0.5D,
                    player.rotationYaw,
                    player.rotationPitch);
                destination.spawnEntityInWorld(player);
            }
        }
        player.setPositionAndUpdate(spot[0] + 0.5D, spot[1], spot[2] + 0.5D);
        player.fallDistance = 0;
        player.motionX = player.motionY = player.motionZ = 0;
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("target", source.write());
        result.setString("message", "arrived");
        return result;
    }

    private static NBTTagCompound failure(String message) {
        NBTTagCompound result = new NBTTagCompound();
        result.setString("message", message);
        return result;
    }

    static final class DirectTeleporter extends Teleporter {

        private final int x, y, z;

        DirectTeleporter(WorldServer world, int[] spot) {
            super(world);
            x = spot[0];
            y = spot[1];
            z = spot[2];
        }

        @Override
        public void placeInPortal(Entity entity, double previousX, double previousY, double previousZ, float yaw) {
            entity.setLocationAndAngles(x + 0.5D, y, z + 0.5D, entity.rotationYaw, entity.rotationPitch);
        }
    }
}
