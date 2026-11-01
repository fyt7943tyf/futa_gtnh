package com.futa_gtnh.cardboard;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 纸箱捕获的一格方块快照：方块 id / metadata / 方块实体的完整 NBT。
 *
 * <p>
 * 玩法原型来自 GT-Not-Leisure 的「纸箱」（其代码源自 Mekanism 的 Cardboard Box，
 * MIT），这里按本仓的风格重写。语义：
 * <ul>
 * <li>装箱时：读出目标方块的 id、meta，调用 {@code TileEntity.writeToNBT} 拿到完整
 * 状态（机器库存、设置、GT 元方机器、覆盖板全部都在里面）；GT 机器额外记录
 * {@code getMetaTileID()} 供 tooltip 展示。</li>
 * <li>拆箱时：在纸箱位置 {@code setBlock} 回原方块（meta 用捕获值，不重新推导 ——
 * 原版 {@code onBlockPlaced} 会按「当前点击面」重算朝向，反而丢失原朝向），再把
 * 坐标改写后的 NBT 喂回新方块实体。GT 的 BlockMachines 不覆写
 * onBlockPlacedBy/canPlaceBlockAt，元方机器完全由 TE NBT 恢复，这条路径是安全的。</li>
 * </ul>
 *
 * <p>
 * 本类只管「数据」，序列化里的超大 NBT 落盘、装箱/拆箱流程分别在
 * {@link CardboardBoxStore} 和 {@link CardboardBoxOps}。
 */
public final class CardboardBoxData {

    private static final String KEY_BLOCK_ID = "block";
    private static final String KEY_META = "meta";
    private static final String KEY_GT_META_TILE_ID = "gt_mte";
    static final String KEY_TILE_TAG = "tile";
    private static final String KEY_TILE_ID = "tile_id";

    /** 数字方块 id。1.7.10 存档内方块 id 稳定，跨会话用它还原 Block 实例。 */
    public final int blockId;
    /** 捕获时的方块 metadata（原样保留，含朝向信息）。 */
    public final int meta;
    /** GT 机器的 MetaTileID，非 GT 机器为 -1。仅用于展示。 */
    public final int gtMetaTileId;
    /** 方块实体注册名（1.7.10 里就是类名字符串），可能为 null。仅用于展示。 */
    public final String tileEntityId;
    /** 方块实体的完整 NBT；目标没有方块实体时为 null。 */
    public final NBTTagCompound tileTag;

    public CardboardBoxData(int blockId, int meta, int gtMetaTileId, String tileEntityId, NBTTagCompound tileTag) {
        this.blockId = blockId;
        this.meta = meta;
        this.gtMetaTileId = gtMetaTileId;
        this.tileEntityId = tileEntityId;
        this.tileTag = tileTag;
    }

    /** @return 方块 id 对应的 Block；id 无效时为 null。 */
    public Block block() {
        return Block.getBlockById(blockId);
    }

    /** @return 方块的本地化名（tooltip 用）；拿不到时退回注册数字 id。 */
    public String blockDisplayName() {
        Block block = block();
        if (block == null) return "#" + blockId;
        return block.getLocalizedName();
    }

    /** 把 tileTag 里的 x/y/z 改写到新位置 —— readFromNBT 之前必须做。 */
    public void updateLocation(int x, int y, int z) {
        if (tileTag == null) return;
        tileTag.setInteger("x", x);
        tileTag.setInteger("y", y);
        tileTag.setInteger("z", z);
    }

    /**
     * 捕获 (x,y,z) 的方块快照。调用方负责前置校验（黑名单/硬度/方块实体存在）。
     */
    public static CardboardBoxData capture(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);

        NBTTagCompound tileTag = null;
        String tileEntityId = null;
        int gtMetaTileId = -1;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile != null) {
            tileTag = new NBTTagCompound();
            tile.writeToNBT(tileTag);
            tileEntityId = tileTag.getString("id");
            if (tile instanceof IGregTechTileEntity) {
                IGregTechTileEntity gtTile = (IGregTechTileEntity) tile;
                if (gtTile.getMetaTileEntity() != null) gtMetaTileId = gtTile.getMetaTileID();
            }
        }

        return new CardboardBoxData(Block.getIdFromBlock(block), meta, gtMetaTileId, tileEntityId, tileTag);
    }

    /** 只写元数据（方块 id / meta / GT 机器 id / 方块实体名），不写 tileTag。落盘路径用。 */
    public void writeMetaToTag(NBTTagCompound tag) {
        tag.setInteger(KEY_BLOCK_ID, blockId);
        tag.setInteger(KEY_META, meta);
        tag.setInteger(KEY_GT_META_TILE_ID, gtMetaTileId);
        if (tileEntityId != null) tag.setString(KEY_TILE_ID, tileEntityId);
    }

    /** 序列化到 NBT。超大 tileTag 的落盘由 {@link CardboardBoxStore#writeDataTag} 处理，这里只写内联数据。 */
    public void writeToTag(NBTTagCompound tag) {
        writeMetaToTag(tag);
        if (tileTag != null) tag.setTag(KEY_TILE_TAG, tileTag);
    }

    /** 从 NBT 还原（不含落盘文件解析，那条路径见 {@link CardboardBoxStore#readData}）。 */
    public static CardboardBoxData readInline(NBTTagCompound tag) {
        int blockId = tag.getInteger(KEY_BLOCK_ID);
        int meta = tag.getInteger(KEY_META);
        int gtMetaTileId = tag.hasKey(KEY_GT_META_TILE_ID) ? tag.getInteger(KEY_GT_META_TILE_ID) : -1;
        String tileEntityId = tag.hasKey(KEY_TILE_ID) ? tag.getString(KEY_TILE_ID) : null;
        NBTTagCompound tileTag = tag.hasKey(KEY_TILE_TAG) ? tag.getCompoundTag(KEY_TILE_TAG) : null;
        return new CardboardBoxData(blockId, meta, gtMetaTileId, tileEntityId, tileTag);
    }
}
