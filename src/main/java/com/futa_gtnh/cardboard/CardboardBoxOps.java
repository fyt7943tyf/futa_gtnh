package com.futa_gtnh.cardboard;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 纸箱的服务端操作：装箱（{@link #pack}）与拆箱（{@link #unpack}）。
 *
 * <p>
 * 两条路径都在服务端主线程上跑（包处理器 / 方块交互），客户端只负责发请求。
 * 置换方块期间用 {@link CardboardBoxDropGuard} 关掉物品实体生成窗口，
 * 防止个别模组的方块实体在失效时把内容物吐进世界（数据已经完整在快照里）。
 */
public final class CardboardBoxOps {

    private CardboardBoxOps() {}

    /**
     * 装箱：把 (x,y,z) 的方块替换成「已装箱」的纸箱方块，数据写进纸箱方块实体。
     *
     * @return 是否成功；失败时给玩家发了原因提示
     */
    public static boolean pack(EntityPlayer player, World world, int x, int y, int z, ItemStack heldStack) {
        if (player == null || world == null || world.isRemote) return false;

        Block target = world.getBlock(x, y, z);
        if (target.isAir(world, x, y, z)) return false;
        if (target == FutaGtnhMod.blockCardboardBox) return false;

        TileEntity targetTile = world.getTileEntity(x, y, z);
        if (targetTile == null) {
            notify(player, "futa_gtnh.cardboard_box.msg.no_tile");
            return false;
        }
        if (CardboardBoxBlacklist.isBlocked(world, x, y, z)) {
            notify(player, "futa_gtnh.cardboard_box.msg.blacklisted");
            return false;
        }
        if (target.getBlockHardness(world, x, y, z) == -1.0F) {
            notify(player, "futa_gtnh.cardboard_box.msg.unbreakable");
            return false;
        }
        if (!world.canMineBlock(player, x, y, z)) {
            notify(player, "futa_gtnh.cardboard_box.msg.protected");
            return false;
        }
        // 装箱本质是「替换方块」，和 AE2 自己的拆卸一样过一遍 BreakEvent：
        // 领地/保护类模组有机会否决
        net.minecraftforge.event.world.BlockEvent.BreakEvent breakEvent = new net.minecraftforge.event.world.BlockEvent.BreakEvent(
            x,
            y,
            z,
            world,
            target,
            world.getBlockMetadata(x, y, z),
            player);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(breakEvent);
        if (breakEvent.isCanceled()) {
            notify(player, "futa_gtnh.cardboard_box.msg.protected");
            return false;
        }

        Block boxBlock = FutaGtnhMod.blockCardboardBox;
        if (boxBlock == null) return false;

        CardboardBoxData data = CardboardBoxData.capture(world, x, y, z);
        NBTTagCompound dataTag = CardboardBoxStore.writeDataTag(data, world, true);
        if (data.tileTag != null && !dataTag.hasKey(CardboardBoxData.KEY_TILE_TAG)
            && !CardboardBoxStore.isFileBacked(dataTag)) {
            // 落盘失败且不允许内联：数据会丢，宁可不装
            notify(player, "futa_gtnh.cardboard_box.msg.too_large");
            return false;
        }

        CardboardBoxDropGuard.begin(world);
        boolean placed;
        try {
            placed = world.setBlock(x, y, z, boxBlock, 1, 3);
        } finally {
            CardboardBoxDropGuard.end();
        }
        if (!placed) {
            CardboardBoxStore.discard(dataTag, world);
            notify(player, "futa_gtnh.cardboard_box.msg.failed");
            return false;
        }

        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityCardboardBox) {
            ((TileEntityCardboardBox) tile).setStoredDataTag(dataTag);
        } else {
            // 纸箱方块实体没建出来（极不该发生）：退回数据，避免凭空蒸发
            CardboardBoxStore.discard(dataTag, world);
            world.setBlockToAir(x, y, z);
            notify(player, "futa_gtnh.cardboard_box.msg.failed");
            return false;
        }

        if (!player.capabilities.isCreativeMode && heldStack != null) {
            --heldStack.stackSize;
            player.inventory.markDirty();
        }
        world.playSoundEffect(x + 0.5D, y + 0.5D, z + 0.5D, "dig.wood", 0.7F, 1.0F);
        return true;
    }

    /**
     * 拆箱：纸箱位置的方块恢复原样（坐标已改写的 NBT 喂回新方块实体），
     * 弹出一个空纸箱物品，并删掉可能的落盘数据。
     *
     * @return 是否成功；失败（放置检查不过 / 数据丢失）时给玩家发提示
     */
    public static boolean unpack(World world, int x, int y, int z, TileEntityCardboardBox box, EntityPlayer player) {
        if (world == null || world.isRemote || box == null) return false;

        NBTTagCompound dataTag = box.getStoredDataTag();
        if (dataTag == null) return false;
        CardboardBoxData data = CardboardBoxStore.readData(dataTag, world);
        Block block = data.block();
        if (block == null || block == Blocks.air) {
            notify(player, "futa_gtnh.cardboard_box.msg.unknown_block");
            return false;
        }
        if (data.tileTag == null) {
            notify(player, "futa_gtnh.cardboard_box.msg.data_lost");
            return false;
        }

        // 放置检查：窗口内纸箱报告自己可替换（setBlock 会直接顶掉它）
        BlockCardboardBox.testingPlace = true;
        boolean placeable;
        try {
            placeable = block.canPlaceBlockAt(world, x, y, z);
        } finally {
            BlockCardboardBox.testingPlace = false;
        }
        if (!placeable) {
            notify(player, "futa_gtnh.cardboard_box.msg.cannot_restore");
            return false;
        }

        // meta 用捕获值原样还原，不重新走 onBlockPlaced —— 那会按「当前点击面」
        // 重算朝向，丢掉原朝向；GT 机器也不需要 onBlockPlacedBy（元方机器全在 TE NBT 里）
        // setBlock 失败时纸箱原封不动，数据也不动，玩家换地方再试即可。
        if (!world.setBlock(x, y, z, block, data.meta, 3)) {
            notify(player, "futa_gtnh.cardboard_box.msg.failed");
            return false;
        }

        TileEntity restored = world.getTileEntity(x, y, z);
        if (restored != null) {
            data.updateLocation(x, y, z);
            restored.readFromNBT(data.tileTag);
            world.markTileEntityChunkModified(x, y, z, restored);
        }

        box.clearStoredData();
        spawnEmptyBox(world, x, y, z);
        world.playSoundEffect(x + 0.5D, y + 0.5D, z + 0.5D, "dig.wood", 0.7F, 1.2F);
        return true;
    }

    private static void spawnEmptyBox(World world, int x, int y, int z) {
        Block boxBlock = FutaGtnhMod.blockCardboardBox;
        if (boxBlock == null) return;
        EntityItem item = new EntityItem(world, x + 0.5D, y + 0.5D, z + 0.5D, new ItemStack(boxBlock, 1, 0));
        item.delayBeforeCanPickup = 10;
        world.spawnEntityInWorld(item);
    }

    private static void notify(EntityPlayer player, String key) {
        if (player == null) return;
        player.addChatMessage(new ChatComponentTranslation(key));
    }
}
