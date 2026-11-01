package com.futa_gtnh.aetape;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemMePackage;

import appeng.api.AEApi;
import appeng.api.definitions.IBlocks;
import appeng.api.definitions.ITileDefinition;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartItemStack;
import appeng.api.parts.SelectedPart;
import appeng.tile.AEBaseTile;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

/**
 * ME 封装胶带的服务端实现 —— 本包里所有对 AE2 的触碰都从这走。
 *
 * <p>
 * 两条主路径：
 * <ul>
 * <li>{@link #pack 封装}：白名单命中的设备从世界里取下，完整数据（驱动器里的
 * 元件、接口的样板/配置/升级、朝向、优先级、颜色）全部进
 * {@link ItemMePackage 封装物品}。方块设备走「完整方块实体 NBT」
 * （AE2 的 {@code AEBaseTile.writeToNBT} 是 final 的，会把 inv / 朝向 /
 * 各 TileEvent 处理器的状态一网打尽）；线缆部件走 AE2 自己的 def/extra 惯例
 * （def = 部件物品栈，extra = {@code part.writeToNBT}），拆卸流程对齐
 * {@code PartPlacement.wrenchLogic}（含 BreakEvent 兼容保护模组）。</li>
 * <li>{@link #place 放回}：方块设备在点击面外侧 setBlock + readFromNBT +
 * markForUpdate；部件设备对齐 {@code PartPlacement.placeItemPart} 的三段式 ——
 * 点击总线本身 / 点击面外侧已有总线 / 在外侧位置新建 multiPart 主方块再贴部件。</li>
 * </ul>
 *
 * <p>
 * 玩法原型：现代版 ExtendedAE（GlodBlock/ExtendedAE，LGPL-3.0）的
 * me_packing_tape / package。代码为 1.7.10 的 AE2 GTNH fork 从零实现，
 * 对应类注释见 {@code ItemMePackingTape}。
 *
 * <p>
 * <b>白名单默认是「存储设备组」</b>（用户选型）：ME 驱动器、ME 接口方块、
 * ME 接口部件、ME 箱子、IO 传输总线。配置项 {@code meTapeWhitelist} 可追加，
 * 格式 {@code block:modid:注册名} 或 {@code part:modid:物品名:子id}。
 * 内建四件方块走 AE2 自己的 definitions API（比写死注册名稳），部件用
 * def 物品栈的「物品+子id」精确匹配 —— AE2 所有部件共用一个物品，只按物品名
 * 匹配会把全部部件都放进来。
 */
public final class MeTapeOps {

    private MeTapeOps() {}

    private static Boolean available;

    /** 方块白名单（数字 id），懒解析：第一次实际使用时 AE2 一定已注册完。 */
    private static Set<Integer> whitelistedBlocks;

    /** 部件白名单（"modid:物品名#子id" 精确键）。 */
    private static Set<String> whitelistedParts;

    /** @return AE2 是否在场；结果缓存。 */
    public static boolean isAvailable() {
        if (available == null) available = Loader.isModLoaded("appliedenergistics2");
        return available;
    }

    // ==================================================================
    // 白名单
    // ==================================================================

    private static void ensureWhitelist() {
        if (whitelistedBlocks != null) return;
        whitelistedBlocks = new HashSet<>();
        whitelistedParts = new HashSet<>();

        IBlocks blocks = AEApi.instance()
            .definitions()
            .blocks();
        addBlock(blocks.drive(), "ME 驱动器");
        addBlock(blocks.iface(), "ME 接口方块");
        addBlock(blocks.chest(), "ME 箱子");
        addBlock(blocks.iOPort(), "IO 传输总线");

        // 接口部件：def 物品栈（物品 + 子id）精确入表。
        // 1.7.10 的 AE2 API 返回的是 Guava Optional，不是 java.util.Optional
        com.google.common.base.Optional<ItemStack> ifacePart = AEApi.instance()
            .definitions()
            .parts()
            .iface()
            .maybeStack(1);
        if (ifacePart.isPresent()) whitelistedParts.add(partKey(ifacePart.get()));

        for (String entry : Config.meTapeWhitelist) {
            parseExtraEntry(entry);
        }
    }

    private static void addBlock(ITileDefinition definition, String label) {
        if (definition.maybeBlock()
            .isPresent()) {
            whitelistedBlocks.add(
                Block.getIdFromBlock(
                    definition.maybeBlock()
                        .get()));
        } else {
            FutaGtnhMod.LOG.warn("ME 胶带：白名单里的 {} 没解析到方块，跳过", label);
        }
    }

    /** 配置追加条目：block:modid:注册名 / part:modid:物品名:子id */
    private static void parseExtraEntry(String entry) {
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) return;
        String[] parts = trimmed.split(":");
        if (parts.length == 3 && "block".equals(parts[0])) {
            Block block = GameRegistry.findBlock(parts[1], parts[2]);
            if (block != null) whitelistedBlocks.add(Block.getIdFromBlock(block));
            else FutaGtnhMod.LOG.warn("ME 胶带：白名单条目没解析到方块：{}", trimmed);
        } else if (parts.length == 4 && "part".equals(parts[0])) {
            net.minecraft.item.Item item = GameRegistry.findItem(parts[1], parts[2]);
            if (item != null) whitelistedParts.add(parts[1] + ":" + parts[2] + "#" + parts[3]);
            else FutaGtnhMod.LOG.warn("ME 胶带：白名单条目没解析到物品：{}", trimmed);
        } else {
            FutaGtnhMod.LOG.warn("ME 胶带：白名单条目格式不对（block:modid:注册名 或 part:modid:物品名:子id）：{}", trimmed);
        }
    }

    private static boolean isBlockWhitelisted(Block block) {
        return whitelistedBlocks.contains(Block.getIdFromBlock(block));
    }

    private static boolean isPartWhitelisted(ItemStack def) {
        return whitelistedParts.contains(partKey(def));
    }

    /** @return 部件 def 物品栈的精确键（物品注册名 + 子id）。 */
    private static String partKey(ItemStack stack) {
        UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(stack.getItem());
        if (id == null) return "#unknown";
        return id.modId + ":" + id.name + "#" + stack.getItemDamage();
    }

    // ==================================================================
    // 封装（设备 → 物品）
    // ==================================================================

    /**
     * 封装 (x,y,z) 的设备。hit 坐标用于线缆总线的部件选取（和 AE2 拆卸同一套
     * {@code selectPart} 判定）。
     *
     * @return 是否成功；失败时给玩家发了原因提示
     */
    public static boolean pack(EntityPlayer player, World world, int x, int y, int z, int side, float hitX, float hitY,
        float hitZ, ItemStack tapeStack) {
        if (!isAvailable() || player == null || world == null || world.isRemote) return false;
        ensureWhitelist();

        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) return false;

        if (!player.canPlayerEdit(x, y, z, side, tapeStack)) {
            notify(player, "futa_gtnh.me_tape.msg.protected");
            return false;
        }

        ItemStack packageStack;
        if (tile instanceof IPartHost host) {
            packageStack = packPart(player, world, x, y, z, hitX, hitY, hitZ, (IPartHost) tile);
        } else {
            packageStack = packBlock(player, world, x, y, z, tile);
        }
        if (packageStack == null) return false;

        if (!player.capabilities.isCreativeMode && tapeStack != null) {
            tapeStack.damageItem(1, player);
        }
        givePackage(player, world, x, y, z, packageStack);
        world.playSoundEffect(x + 0.5D, y + 0.5D, z + 0.5D, "random.orb", 0.5F, 1.4F);
        notifyFormat(
            player,
            "futa_gtnh.me_tape.msg.packed",
            ItemMePackage.deviceName(ItemMePackage.dataTag(packageStack)));
        return true;
    }

    /** 封装线缆总线上的一个部件；白名单未命中返回 null（已发提示）。 */
    private static ItemStack packPart(EntityPlayer player, World world, int x, int y, int z, float hitX, float hitY,
        float hitZ, IPartHost host) {
        SelectedPart selected = host.selectPart(Vec3.createVectorHelper(hitX, hitY, hitZ));
        if (selected.part == null) {
            // 点到线缆本体 / 空面：这里没有可封装的部件
            return null;
        }
        ItemStack def = selected.part.getItemStack(PartItemStack.Wrench);
        if (def == null || !isPartWhitelisted(def)) {
            notify(player, "futa_gtnh.me_tape.msg.not_whitelisted");
            return null;
        }

        // 和 AE2 自己的拆卸一样过一遍 BreakEvent：领地/保护类模组有机会否决
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
            x,
            y,
            z,
            world,
            world.getBlock(x, y, z),
            world.getBlockMetadata(x, y, z),
            player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            notify(player, "futa_gtnh.me_tape.msg.protected");
            return null;
        }

        NBTTagCompound extra = new NBTTagCompound();
        selected.part.writeToNBT(extra);

        host.removePart(selected.side, false);
        if (host.isEmpty()) host.cleanup();

        ItemStack packageStack = newPackageStack(true);
        NBTTagCompound data = ItemMePackage.dataTag(packageStack);
        NBTTagCompound defTag = new NBTTagCompound();
        def.copy()
            .writeToNBT(defTag);
        data.setTag("def", defTag);
        data.setTag("extra", extra);
        return packageStack;
    }

    /** 封装整方块设备；白名单未命中 / 方块实体缺失返回 null（已发提示）。 */
    private static ItemStack packBlock(EntityPlayer player, World world, int x, int y, int z, TileEntity tile) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return null;
        if (!isBlockWhitelisted(block)) {
            notify(player, "futa_gtnh.me_tape.msg.not_whitelisted");
            return null;
        }

        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
            x,
            y,
            z,
            world,
            block,
            world.getBlockMetadata(x, y, z),
            player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            notify(player, "futa_gtnh.me_tape.msg.protected");
            return null;
        }

        NBTTagCompound tileTag = new NBTTagCompound();
        tile.writeToNBT(tileTag);
        UniqueIdentifier blockId = GameRegistry.findUniqueIdentifierFor(block);
        if (blockId == null) return null;

        world.setBlockToAir(x, y, z);

        ItemStack packageStack = newPackageStack(false);
        NBTTagCompound data = ItemMePackage.dataTag(packageStack);
        data.setString("block", blockId.modId + ":" + blockId.name);
        data.setTag("tile", tileTag);
        return packageStack;
    }

    private static ItemStack newPackageStack(boolean isPart) {
        ItemStack stack = new ItemStack(FutaGtnhMod.mePackageItem, 1, 0);
        stack.stackTagCompound = new NBTTagCompound();
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("isPart", isPart);
        stack.stackTagCompound.setTag(ItemMePackage.TAG_DATA, data);
        return stack;
    }

    /** 封装结果优先进玩家背包，装不下才落地。设备贵重，别让它躺岩浆边上。 */
    private static void givePackage(EntityPlayer player, World world, int x, int y, int z, ItemStack packageStack) {
        if (!player.inventory.addItemStackToInventory(packageStack.copy())) {
            EntityItem item = new EntityItem(world, x + 0.5D, y + 0.5D, z + 0.5D, packageStack);
            item.delayBeforeCanPickup = 10;
            world.spawnEntityInWorld(item);
        }
    }

    // ==================================================================
    // 放回（物品 → 设备）
    // ==================================================================

    /** 把封装物品放回世界（点击了某个方块的一面）。 */
    public static boolean place(EntityPlayer player, World world, int x, int y, int z, int side,
        ItemStack packageStack) {
        if (!isAvailable() || player == null || world == null || world.isRemote) return false;
        NBTTagCompound data = ItemMePackage.dataTag(packageStack);
        if (data == null) return false;
        if (side < 0 || side >= 6) return false;
        if (!player.canPlayerEdit(x, y, z, side, packageStack)) {
            notify(player, "futa_gtnh.me_tape.msg.protected");
            return false;
        }

        ForgeDirection clicked = ForgeDirection.getOrientation(side);
        boolean placed;
        if (data.getBoolean("isPart")) {
            ItemStack def = ItemStack.loadItemStackFromNBT(data.getCompoundTag("def"));
            NBTTagCompound extra = data.getCompoundTag("extra");
            placed = placePart(player, world, x, y, z, clicked, def, extra);
        } else {
            placed = placeBlock(
                player,
                world,
                x + clicked.offsetX,
                y + clicked.offsetY,
                z + clicked.offsetZ,
                data.getString("block"),
                data.getCompoundTag("tile"));
        }

        if (!placed) {
            notify(player, "futa_gtnh.me_package.msg.failed");
            return false;
        }

        if (!player.capabilities.isCreativeMode && packageStack != null) {
            --packageStack.stackSize;
            player.inventory.markDirty();
        }
        world.playSoundEffect(x + 0.5D, y + 0.5D, z + 0.5D, "random.orb", 0.5F, 1.1F);
        return true;
    }

    /** 方块设备：放到点击面外侧的位置，NBT 喂回新方块实体后同步一次。 */
    private static boolean placeBlock(EntityPlayer player, World world, int px, int py, int pz, String blockName,
        NBTTagCompound tileTag) {
        int separator = blockName.indexOf(':');
        if (separator <= 0) return false;
        Block block = GameRegistry.findBlock(blockName.substring(0, separator), blockName.substring(separator + 1));
        if (block == null) return false;

        Block at = world.getBlock(px, py, pz);
        if (!at.isAir(world, px, py, pz) && !at.isReplaceable(world, px, py, pz)) return false;
        if (!block.canPlaceBlockAt(world, px, py, pz)) return false;

        // 领地/保护类模组的否决窗口（对齐 AE2 PartPlacement 放部件前的 PlaceEvent）
        BlockEvent.PlaceEvent event = new BlockEvent.PlaceEvent(
            BlockSnapshot.getBlockSnapshot(world, px, py, pz),
            world.getBlock(px, py, pz),
            player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return false;

        if (!world.setBlock(px, py, pz, block, 0, 3)) return false;

        TileEntity tile = world.getTileEntity(px, py, pz);
        if (tile != null && tileTag != null) {
            tileTag.setInteger("x", px);
            tileTag.setInteger("y", py);
            tileTag.setInteger("z", pz);
            tile.readFromNBT(tileTag);
            // AEBaseTile.readFromNBT 是 final 的完整恢复；markForUpdate 让客户端
            // 立刻拿到描述包（朝向 / 颜色 / 面板状态），onReady 由下一 tick 的
            // 初始化队列触发，会按恢复出的状态重建网络节点。
            if (tile instanceof AEBaseTile) ((AEBaseTile) tile).markForUpdate();
        }
        return true;
    }

    /**
     * 部件设备：对齐 {@code PartPlacement.placeItemPart} 的三段式放置。
     * <ol>
     * <li>点击的是线缆总线 → 部件贴到被点击的那一面；</li>
     * <li>点击面外侧的格子已是总线 → 部件贴到背向被点方块的那一面；</li>
     * <li>外侧格子是空气/可替换 → 先放一个 AE 的 multiPart 主方块（等同原版
     * 拿部件物品右键方块），部件贴在面向被点方块的那一面 ——
     * 「右键箱子侧面贴接口」就是这么发生的。</li>
     * </ol>
     */
    private static boolean placePart(EntityPlayer player, World world, int x, int y, int z, ForgeDirection clicked,
        ItemStack def, NBTTagCompound extra) {
        if (def == null || !(def.getItem() instanceof IPartItem)) return false;

        // ① 点的就是总线
        if (tryAddPart(world, x, y, z, clicked, def, extra, player)) return true;

        // ②③ 点击面外侧的格子
        int px = x + clicked.offsetX;
        int py = y + clicked.offsetY;
        int pz = z + clicked.offsetZ;
        ForgeDirection back = clicked.getOpposite();

        TileEntity outside = world.getTileEntity(px, py, pz);
        if (!(outside instanceof IPartHost)) {
            Block at = world.getBlock(px, py, pz);
            if (!at.isAir(world, px, py, pz) && !at.isReplaceable(world, px, py, pz)) return false;
            if (createHost(world, px, py, pz, player, back.ordinal()) == null) return false;
        }
        return tryAddPart(world, px, py, pz, back, def, extra, player);
    }

    /** 往 (x,y,z) 的总线上加一个部件并灌回数据；任何一步失败返回 false。 */
    private static boolean tryAddPart(World world, int x, int y, int z, ForgeDirection side, ItemStack def,
        NBTTagCompound extra, EntityPlayer player) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IPartHost)) return false;
        IPartHost host = (IPartHost) tile;
        if (!host.canAddPart(def, side)) return false;

        ForgeDirection placed = host.addPart(def.copy(), side, player);
        if (placed == null) return false;
        IPart part = host.getPart(placed);
        if (part == null) return false;
        part.readFromNBT(extra);
        host.markForSave();
        return true;
    }

    /** 在空位放下 AE 的 multiPart 主方块（线缆总线），返回它的 IPartHost。 */
    private static IPartHost createHost(World world, int px, int py, int pz, EntityPlayer player, int face) {
        com.google.common.base.Optional<ItemBlock> itemBlock = AEApi.instance()
            .definitions()
            .blocks()
            .multiPart()
            .maybeItemBlock();
        if (!itemBlock.isPresent()) return null;

        BlockEvent.PlaceEvent event = new BlockEvent.PlaceEvent(
            BlockSnapshot.getBlockSnapshot(world, px, py, pz),
            world.getBlock(px, py, pz),
            player);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return null;

        ItemBlock multiPart = itemBlock.get();
        if (!multiPart.field_150939_a.canPlaceBlockAt(world, px, py, pz)) return null;
        if (!multiPart.placeBlockAt(new ItemStack(multiPart), player, world, px, py, pz, face, 0.5F, 0.5F, 0.5F, 0)) {
            return null;
        }
        TileEntity tile = world.getTileEntity(px, py, pz);
        return tile instanceof IPartHost ? (IPartHost) tile : null;
    }

    private static void notify(EntityPlayer player, String key) {
        if (player == null) return;
        player.addChatMessage(new ChatComponentTranslation(key));
    }

    private static void notifyFormat(EntityPlayer player, String key, Object... args) {
        if (player == null) return;
        player.addChatMessage(new ChatComponentTranslation(key, args));
    }
}
