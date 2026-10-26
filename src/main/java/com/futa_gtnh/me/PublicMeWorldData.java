package com.futa_gtnh.me;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;

/** The public controller's identity and reserved location, independent of its runtime AE grid. */
public final class PublicMeWorldData extends WorldSavedData {

    static final String NAME = "futa_public_me";
    UUID storageId;
    int x;
    int y;
    int z;
    boolean located;

    public PublicMeWorldData(String name) {
        super(name);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (tag.hasKey("storageMost") && tag.hasKey("storageLeast")) {
            storageId = new UUID(tag.getLong("storageMost"), tag.getLong("storageLeast"));
        }
        x = tag.getInteger("x");
        y = tag.getInteger("y");
        z = tag.getInteger("z");
        located = tag.getBoolean("located");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        if (storageId != null) {
            tag.setLong("storageMost", storageId.getMostSignificantBits());
            tag.setLong("storageLeast", storageId.getLeastSignificantBits());
        }
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setBoolean("located", located);
    }
}
