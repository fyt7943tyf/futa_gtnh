package com.futa_gtnh.cardboard;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * 纸箱的方块实体：只保存「捕获到的方块数据标签」一份。
 *
 * <p>
 * 数据标签的结构由 {@link CardboardBoxStore} 决定（内联 NBT 或落盘 uuid），
 * 这里不做任何解析 —— 解析只发生在拆箱那一刻。{@code canUpdate} 关掉：
 * 纸箱放下来就是死的，不参与任何 tick 逻辑。
 */
public class TileEntityCardboardBox extends TileEntity {

    private static final String KEY_STORED = "storedData";

    /** 捕获数据的 NBT 标签；空纸箱为 null。原样透传 {@link CardboardBoxStore} 的产物。 */
    private NBTTagCompound storedData;

    /** @return 是否装着东西 */
    public boolean hasData() {
        return storedData != null;
    }

    public NBTTagCompound getStoredDataTag() {
        return storedData;
    }

    public void setStoredDataTag(NBTTagCompound tag) {
        storedData = tag;
        markDirty();
    }

    /** 数据被真正消费（拆箱）后调用：清引用 + 删落盘文件。 */
    public void clearStoredData() {
        CardboardBoxStore.discard(storedData, worldObj);
        storedData = null;
        markDirty();
    }

    @Override
    public boolean canUpdate() {
        return false;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (storedData != null) tag.setTag(KEY_STORED, storedData);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        storedData = tag.hasKey(KEY_STORED) ? tag.getCompoundTag(KEY_STORED) : null;
    }
}
