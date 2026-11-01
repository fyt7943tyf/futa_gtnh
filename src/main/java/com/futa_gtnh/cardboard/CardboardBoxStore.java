package com.futa_gtnh.cardboard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 纸箱数据的 NBT 存取层，含「超大 NBT 落盘」。
 *
 * <p>
 * <b>为什么需要落盘：</b>满配机器（大库存、多覆盖板、完整多方块控制器状态）的
 * 方块实体 NBT 压缩后可能远超几十 KB。这种数据放进物品栈 NBT 有两个坏处：
 * 物品栈随玩家数据全量序列化，复制/移动背包时开销翻倍；1.7.10 的网络同步
 * （拣物品、GUI 展示）对大 NBT 也很敏感。思路取自 GT-Not-Leisure（把超限数据
 * 挪进世界数据目录，物品里只留一个 uuid），这里独立实现。
 *
 * <p>
 * 落盘文件固定放<b>主世界</b>存档目录的 {@code data/} 下（和原版地图数据同级），
 * 而不是「当时所在维度」的目录 —— 纸箱是随身物品，打包和使用很可能不在同一个
 * 维度，只有锚定主世界才保证两边找得到同一个文件。uuid 用
 * {@link UUID#randomUUID()}，不泄露任何可预测性。
 */
public final class CardboardBoxStore {

    private CardboardBoxStore() {}

    /** tileTag 压缩后超过这个字节数就走落盘。16 KB 对绝大多数机器足够宽裕。 */
    public static final int MAX_INLINE_SIZE = 16384;

    /** 物品栈 / 方块实体里挂在数据标签下的落盘引用键。 */
    private static final String KEY_STORE_UUID = "storeUUID";
    private static final String FILE_PREFIX = "futa_gtnh_cardboard_";

    /**
     * 把捕获数据写成可挂进物品栈/方块实体的 NBT。
     *
     * @param allowFile
     *                  true = 超过 {@link #MAX_INLINE_SIZE} 时落盘并只留 uuid；
     *                  false = 拒绝落盘，超限时丢弃 tileTag（调用方只该在「展示用」
     *                  场景传 false）
     */
    public static NBTTagCompound writeDataTag(CardboardBoxData data, World world, boolean allowFile) {
        NBTTagCompound tag = new NBTTagCompound();
        boolean oversize = false;
        if (data.tileTag != null) {
            byte[] compressed;
            try {
                compressed = CompressedStreamTools.compress(data.tileTag);
            } catch (IOException e) {
                FutaGtnhMod.LOG.warn("纸箱：压缩方块实体 NBT 失败，按超限处理", e);
                compressed = null;
            }
            oversize = compressed == null || compressed.length > MAX_INLINE_SIZE;
        }

        data.writeMetaToTag(tag);
        if (data.tileTag == null) return tag;

        if (!oversize) {
            tag.setTag(CardboardBoxData.KEY_TILE_TAG, data.tileTag);
        } else if (allowFile && tryStoreToFile(data, tag, world)) {
            // 落盘成功：元数据 + storeUUID，tileTag 不内联
        } else {
            // 不允许落盘（或落盘失败）：只保留元数据，内容丢弃
        }
        return tag;
    }

    /** 从 NBT 还原捕获数据；带落盘引用时读文件，文件丢失时退回「只有元数据」。 */
    public static CardboardBoxData readData(NBTTagCompound tag, World world) {
        if (tag.hasKey(KEY_STORE_UUID)) {
            String uuid = tag.getString(KEY_STORE_UUID);
            NBTTagCompound tileTag = loadFile(uuid, world);
            if (tileTag != null) {
                CardboardBoxData meta = CardboardBoxData.readInline(tag);
                return new CardboardBoxData(meta.blockId, meta.meta, meta.gtMetaTileId, meta.tileEntityId, tileTag);
            }
            FutaGtnhMod.LOG.warn("纸箱：落盘数据 {} 丢失（换存档/手动删除？），内容无法恢复", uuid);
        }
        return CardboardBoxData.readInline(tag);
    }

    /** 数据被真正消费（拆箱）或物品销毁时，删掉落盘文件；没有引用时是空操作。 */
    public static void discard(NBTTagCompound tag, World world) {
        if (tag == null || !tag.hasKey(KEY_STORE_UUID)) return;
        File file = storeFile(tag.getString(KEY_STORE_UUID), world);
        if (file == null) return;
        if (!file.delete() && file.exists()) {
            FutaGtnhMod.LOG.warn("纸箱：删除落盘数据 {} 失败", file.getName());
        }
    }

    /** @return 这个数据标签的内容是不是放在落盘文件里（tooltip / 校验用） */
    public static boolean isFileBacked(NBTTagCompound tag) {
        return tag != null && tag.hasKey(KEY_STORE_UUID);
    }

    private static boolean tryStoreToFile(CardboardBoxData data, NBTTagCompound out, World world) {
        File file = storeFile(
            UUID.randomUUID()
                .toString(),
            world);
        if (file == null) return false;
        file.getParentFile()
            .mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(data.tileTag, stream);
        } catch (IOException e) {
            FutaGtnhMod.LOG.warn("纸箱：写落盘数据 {} 失败，退回内联", file.getName(), e);
            return false;
        }
        out.setString(
            KEY_STORE_UUID,
            file.getName()
                .substring(
                    FILE_PREFIX.length(),
                    file.getName()
                        .length() - ".dat".length()));
        return true;
    }

    private static NBTTagCompound loadFile(String uuid, World world) {
        File file = storeFile(uuid, world);
        if (file == null || !file.isFile()) return null;
        try {
            return CompressedStreamTools.readCompressed(new java.io.FileInputStream(file));
        } catch (IOException e) {
            FutaGtnhMod.LOG.warn("纸箱：读取落盘数据 {} 失败", file.getName(), e);
            return null;
        }
    }

    private static File storeFile(String uuid, World world) {
        File dir = worldDataDir(world);
        if (dir == null) return null;
        return new File(dir, FILE_PREFIX + uuid + ".dat");
    }

    /** 主世界存档目录下的 data/；世界没加载时返回 null。 */
    private static File worldDataDir(World world) {
        World effective = world;
        if (effective == null || effective.isRemote) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.worldServers == null
                || server.worldServers.length == 0
                || server.worldServers[0] == null) return null;
            effective = server.worldServers[0];
        } else if (effective.provider.dimensionId != 0) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null && server.worldServers != null
                && server.worldServers.length > 0
                && server.worldServers[0] != null) {
                effective = server.worldServers[0];
            }
        }
        try {
            File worldDir = effective.getSaveHandler()
                .getWorldDirectory();
            return worldDir == null ? null : new File(worldDir, "data");
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("纸箱：定位存档数据目录失败", t);
            return null;
        }
    }
}
