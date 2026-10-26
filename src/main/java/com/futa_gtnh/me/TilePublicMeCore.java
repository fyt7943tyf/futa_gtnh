package com.futa_gtnh.me;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.GridFlags;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.util.AECableType;
import appeng.tile.TileEvent;
import appeng.tile.events.TileEventType;
import appeng.tile.grid.AENetworkTile;

/** One of six real controller ports. Only the registered primary supplies public-network power. */
public final class TilePublicMeCore extends AENetworkTile implements IAEPowerStorage {

    private UUID storageId;
    private int port = -1;

    public TilePublicMeCore() {
        getProxy().setFlags(GridFlags.DENSE_CAPACITY);
        getProxy().setIdlePowerUsage(0);
    }

    void bind(UUID id, int index) {
        storageId = id;
        port = index;
        markDirty();
    }

    boolean belongsTo(UUID id, int index) {
        return id != null && id.equals(storageId) && port == index;
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection direction) {
        return AECableType.DENSE;
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readPublicMe(NBTTagCompound tag) {
        if (tag.hasKey("futaPublicMeMost") && tag.hasKey("futaPublicMeLeast")) {
            storageId = new UUID(tag.getLong("futaPublicMeMost"), tag.getLong("futaPublicMeLeast"));
        }
        port = tag.hasKey("futaPublicMePort") ? tag.getInteger("futaPublicMePort") : -1;
    }

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writePublicMe(NBTTagCompound tag) {
        if (storageId != null) {
            tag.setLong("futaPublicMeMost", storageId.getMostSignificantBits());
            tag.setLong("futaPublicMeLeast", storageId.getLeastSignificantBits());
        }
        tag.setInteger("futaPublicMePort", port);
    }

    @Override
    public boolean isAEPublicPowerStorage() {
        // AE may discover loaded nodes before ServerStarted restores the runtime registry.
        return port == 0 && storageId != null;
    }

    @Override
    public boolean isInfinite() {
        return isAEPublicPowerStorage();
    }

    @Override
    public double getAEMaxPower() {
        return isAEPublicPowerStorage() ? 1_000_000_000D : 0D;
    }

    @Override
    public double getAECurrentPower() {
        return getAEMaxPower();
    }

    @Override
    public AccessRestriction getPowerFlow() {
        return AccessRestriction.READ;
    }

    @Override
    public double injectAEPower(double amount, Actionable mode) {
        return amount;
    }

    @Override
    public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
        return isAEPublicPowerStorage() && Double.isFinite(amount) ? Math.max(0D, amount) : 0D;
    }
}
