package com.futa_gtnh.lootassist;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 一条地牢记录：一个 lootgames 地牢在某个维度里的位置 + 全服共享的完成标记。
 *
 * <p>
 * 每条记录有<b>两个点位</b>：
 *
 * <ul>
 * <li><b>地下主方块</b>（{@code x/y/z}）：棋盘西北角的 GameMaster 方块，
 * y &lt; 0 表示「种子推算出来的候选，还没验证过」（这个类称之为未验证）。</li>
 * <li><b>地表入口</b>（{@code entranceX/Y/Z}）：地牢生成时向上开挖的阶梯出口，
 * 玩家在地面上能直接走到/看到的那个洞口 —— 列表里导航用的是它。
 * 只有验证过（区块真的生成了地牢）才会有有效值，否则是 -1。</li>
 * </ul>
 *
 * <p>
 * 纯数据类，没有任何服务端依赖，客户端缓存（{@code client.ClientLootassistCache}）
 * 直接复用它做镜像，省一套 DTO。
 */
public final class LootassistEntry {

    /** 地牢主方块的 x（= 区块中心 + 8），与 dim/z 一起构成全服唯一的键。 */
    public final int dim;
    public final int x;
    public final int z;

    /** 地下主方块的 y；-1 = 尚未验证（种子推算的候选点）。 */
    public int y = -1;

    /** 地表入口坐标；未验证时全部为 -1。 */
    public int entranceX = -1;
    public int entranceY = -1;
    public int entranceZ = -1;

    /** 全服共享的「已完成」标记。 */
    public boolean completed;
    /** 标记完成的玩家名（取消完成时清空）。 */
    public String completedBy = "";
    /** 标记完成的时间戳（epoch millis；0 = 未标记）。 */
    public long completedAt;

    public LootassistEntry(int dim, int x, int z) {
        this.dim = dim;
        this.x = x;
        this.z = z;
    }

    /** @return 全服唯一的条目键（维度 + 主方块坐标） */
    public String key() {
        return dim + ":" + x + ":" + z;
    }

    /** @return 主方块的 y 是否已经通过区块扫描验证过 */
    public boolean verified() {
        return y >= 0;
    }

    public void writeTo(NBTTagCompound tag) {
        tag.setInteger("dim", dim);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setInteger("ex", entranceX);
        tag.setInteger("ey", entranceY);
        tag.setInteger("ez", entranceZ);
        tag.setBoolean("done", completed);
        tag.setString("doneBy", completedBy);
        tag.setLong("doneAt", completedAt);
    }

    public static LootassistEntry readFrom(NBTTagCompound tag) {
        LootassistEntry entry = new LootassistEntry(tag.getInteger("dim"), tag.getInteger("x"), tag.getInteger("z"));
        entry.y = tag.getInteger("y");
        entry.entranceX = tag.getInteger("ex");
        entry.entranceY = tag.getInteger("ey");
        entry.entranceZ = tag.getInteger("ez");
        entry.completed = tag.getBoolean("done");
        entry.completedBy = tag.getString("doneBy");
        entry.completedAt = tag.getLong("doneAt");
        return entry;
    }

    /** 网络同步用：逐字段写入 ByteBuf（与 {@code PacketLootassistDelta} 保持一致）。 */
    public void writeFields(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(entranceX);
        buf.writeInt(entranceY);
        buf.writeInt(entranceZ);
        buf.writeBoolean(completed);
        cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, completedBy);
        buf.writeLong(completedAt);
    }

    /** @return 从网络字节读出的一条记录（顺序必须与 {@link #writeFields} 一致） */
    public static LootassistEntry readFields(io.netty.buffer.ByteBuf buf) {
        int dim = buf.readInt();
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        LootassistEntry entry = new LootassistEntry(dim, x, z);
        entry.y = y;
        entry.entranceX = buf.readInt();
        entry.entranceY = buf.readInt();
        entry.entranceZ = buf.readInt();
        entry.completed = buf.readBoolean();
        entry.completedBy = cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf);
        entry.completedAt = buf.readLong();
        return entry;
    }
}
