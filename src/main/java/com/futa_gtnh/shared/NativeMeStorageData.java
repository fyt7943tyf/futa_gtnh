package com.futa_gtnh.shared;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;

import com.futa_gtnh.me.NativeMeCellArray;

/** Minecraft owns persistence; spendable inventory is exclusively standard AE cell ItemStack NBT. */
public final class NativeMeStorageData extends WorldSavedData {

    public static final String NAME = "futa_gtnh_public_me_cells";
    public static final int VERSION = 1;
    private UUID storageId;
    private NativeMeCellArray cells;
    private SharedStorage access;
    private NBTTagCompound migration = new NBTTagCompound();
    private boolean blocked;

    public NativeMeStorageData(String name) {
        this(name, new NativeMeCellArray());
    }

    NativeMeStorageData(String name, NativeMeCellArray cells) {
        super(name);
        this.cells = cells;
    }

    void initialize(UUID id, SharedStorage legacy, NBTTagCompound origin) {
        cells.validateMedia();
        storageId = id;
        access = legacy;
        migration = (NBTTagCompound) origin.copy();
        access.attachNativeCells(cells, this::markDirty);
        markDirty();
    }

    public UUID storageId() {
        return storageId;
    }

    public SharedStorage access() {
        return access;
    }

    public NativeMeCellArray cells() {
        return cells;
    }

    NBTTagCompound migration() {
        return (NBTTagCompound) migration.copy();
    }

    public void blockSaving(boolean value) {
        blocked = value;
    }

    public boolean hasUnsavedChanges() {
        return super.isDirty();
    }

    @Override
    public boolean isDirty() {
        return !blocked && super.isDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (!tag.hasKey("version", 3) || tag.getInteger("version") != VERSION
            || !tag.hasKey("storageId", 8)
            || !tag.hasKey("metadata", 10)
            || !tag.hasKey("migration", 10))
            throw new IllegalStateException("Unsupported or incomplete native AE storage data");
        storageId = UUID.fromString(tag.getString("storageId"));
        migration = (NBTTagCompound) tag.getCompoundTag("migration")
            .copy();
        cells.validateMedia();
        cells.readFromNBT(tag);
        access = new SharedStorage();
        NBTTagCompound metadata = tag.getCompoundTag("metadata");
        if (SharedStorage.readFormatVersion(metadata) > SharedStorage.FORMAT_VERSION)
            throw new IllegalStateException("Unsupported native ME service metadata");
        access.readFromNbt(metadata);
        access.attachNativeCells(cells, this::markDirty);
        if (cells.frozenCellCount() > 0) com.futa_gtnh.FutaGtnhMod.LOG
            .warn("公共 ME：{} 枚原生存储元件含有缺失模组条目，已保留整枚元件原始 NBT 并暂停发布；恢复模组后重启可重新挂载", cells.frozenCellCount());
        markDirty();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        if (access == null || storageId == null) throw new IllegalStateException("Native AE data is not initialized");
        tag.setInteger("version", VERSION);
        tag.setString("storageId", storageId.toString());
        tag.setTag("migration", migration.copy());
        tag.setTag("metadata", access.writeServiceMetadata());
        cells.writeToNBT(tag);
    }
}
