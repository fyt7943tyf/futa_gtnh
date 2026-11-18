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
 * 点击总线本身 / 点击面外侧已有总线 / 在外侧位置新建 multiPart 主方块再贴部件。
 * 部件状态灌回遵守 AE2 的加载契约（{@code GridNode.loadFromNBT} 禁止在入网后调用）：
 * 已入网的部件走「摘网 → readFromNBT → 重挂」的 AE2 规范顺序，见
 * {@link #restorePartState}——旧实现在这一步必抛异常且被吞，表现为「放到线缆上
 * 是空部件且胶带不消耗」。</li>
 * </ul>
 *
 * <p>
 * 玩法原型：现代版 ExtendedAE（GlodBlock/ExtendedAE，LGPL-3.0）的
 * me_packing_tape / package。代码为 1.7.10 的 AE2 GTNH fork 从零实现，
 * 对应类注释见 {@code ItemMePackingTape}。
 *
 * <p>
 * <b>白名单 = 存储设备组（内建）+ 按类判定的设备全家桶 + 配置追加</b>。
 * 内建四件方块（ME 驱动器、ME 接口方块、ME 箱子、IO 传输总线）走 AE2 自己的
 * definitions API，外加 AE2FC 二合一接口/P2P 二合一；部件全家桶由
 * {@link #PACKABLE_PART_BASES} 类判定探测（AE2 全部总线/P2P 隧道/接口/发信器/
 * 平面 + AE2FC 流体版 + 第三方同基类插件）。配置项 {@code meTapeWhitelist} 可追加，
 * 格式 {@code block:modid:注册名} 或 {@code part:modid:物品名:子id}（子id 可用
 * {@code *} 通配该物品全部变体）。AE2 所有部件共用一个物品、按子 id 区分，
 * 匹配一律「物品注册名 + 子id」精确键。
 */
public final class MeTapeOps {

    private MeTapeOps() {}

    private static Boolean available;

    /** 方块白名单（数字 id），懒解析：第一次实际使用时 AE2 一定已注册完。 */
    private static Set<Integer> whitelistedBlocks;

    /** 部件白名单（"modid:物品名#子id" 精确键）。 */
    private static Set<String> whitelistedParts;

    /** 部件白名单通配前缀（"modid:物品名#"，来自 config 的 子id=* 条目）。 */
    private static Set<String> wildcardPartPrefixes;

    /**
     * 「可封装部件」的判定基类（用户选型：设备全家桶）：总线三兄弟 + 全部 P2P
     * 隧道 + 接口 + 电平发信器 + 消灭/成型平面 + 样板中继器。AE2FC 的流体部件
     * （流体存储/输入/输出总线、二合一接口、P2P 二合一）全是这些基类的子类，
     * 第三方 AE2 插件的同基类部件也自动覆盖。终端/监视器/线缆/面板不在此列。
     */
    private static final Class<?>[] PACKABLE_PART_BASES = { appeng.parts.automation.PartAnnihilationPlane.class,
        appeng.parts.automation.PartExportBus.class, appeng.parts.automation.PartFormationPlane.class,
        appeng.parts.automation.PartImportBus.class, appeng.parts.automation.PartLevelEmitter.class,
        appeng.parts.misc.PartInterface.class, appeng.parts.misc.PartPatternRepeater.class,
        appeng.parts.misc.PartStorageBus.class, appeng.parts.p2p.PartP2PTunnel.class };

    /** @return AE2 是否在场；结果缓存。 */
    public static boolean isAvailable() {
        if (available == null) available = Loader.isModLoaded("appliedenergistics2");
        return available;
    }

    /**
     * 客户端粗判：这个方块看着像可封装目标吗（白名单方块 / AE 线缆总线）。
     *
     * <p>
     * 只用于 {@code onItemUseFirst} 的客户端分支——不是目标就放行原版流程
     * （机器该开 GUI 开 GUI），免得胶带把右键吞掉；真正的白名单校验在服务端
     * {@link #pack} 重做。部件（线缆总线上的接口）在客户端就是「点是 AE 的
     * multiPart 方块」，具体是哪个部件由服务端 {@code selectPart} 判。
     */
    public static boolean isRoughTarget(net.minecraft.block.Block block) {
        if (!isAvailable() || block == null) return false;
        ensureWhitelist();
        if (isBlockWhitelisted(block)) return true;
        // AE 线缆总线（部件贴在它上面/装在它里面）
        com.google.common.base.Optional<ItemBlock> multiPart = AEApi.instance()
            .definitions()
            .blocks()
            .multiPart()
            .maybeItemBlock();
        return multiPart.isPresent() && multiPart.get().field_150939_a == block;
    }

    // ==================================================================
    // 白名单
    // ==================================================================

    private static void ensureWhitelist() {
        if (whitelistedBlocks != null) return;
        whitelistedBlocks = new HashSet<>();
        whitelistedParts = new HashSet<>();
        wildcardPartPrefixes = new HashSet<>();

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

        // AE2FC（本模组硬依赖，整包必带）：ME 二合一接口（方块 + 线缆部件）与
        // P2P 二合一部件。AE2FC 的设备不在 AE2 的 definitions 里，走注册名解析。
        // 注册名对照 AE2FC-Rework 1.5.x 的类常量：tile.fluid_interface.name =
        // 「ME二合一接口」，部件物品注册名 ae2fc:part_fluid_interface /
        // ae2fc:part_fluid_p2p_interface（均为 IPartItem，走线缆部件通道）
        if (Loader.isModLoaded("ae2fc")) {
            addBlockByName("ae2fc", "fluid_interface", "ME 二合一接口方块");
            addPartByItemName("ae2fc", "part_fluid_interface", "ME 二合一接口部件");
            addPartByItemName("ae2fc", "part_fluid_p2p_interface", "P2P 二合一部件");
        }

        // 按类判定的全家桶探测（见 PACKABLE_PART_BASES）：一次性覆盖 AE2 全部
        // 总线/P2P 隧道（含二合一）/接口/发信器/平面 + AE2FC 流体版 + 未来第三方
        // 同基类插件，不再逐个维护注册名
        addProbeableParts();

        for (String entry : Config.meTapeWhitelist) {
            parseExtraEntry(entry);
        }
    }

    /**
     * 全家桶探测：遍历物品注册表里所有 {@code IPartItem} 的子项，构造一次部件
     * 对象（纯内存操作，不入世界不进网格），命中 {@link #PACKABLE_PART_BASES}
     * 任一基类就把该子项的精确键加入白名单。构造探测失败的第三方部件安静跳过。
     */
    private static void addProbeableParts() {
        java.util.List<ItemStack> variants = new java.util.ArrayList<>();
        java.util.Iterator<?> items = net.minecraft.item.Item.itemRegistry.iterator();
        while (items.hasNext()) {
            Object entry = items.next();
            if (!(entry instanceof net.minecraft.item.Item item) || !(item instanceof IPartItem partItem)) continue;
            variants.clear();
            try {
                item.getSubItems(item, null, variants);
            } catch (Throwable failure) {
                continue;
            }
            for (ItemStack variant : variants) {
                try {
                    IPart part = partItem.createPartFromItemStack(variant.copy());
                    if (part == null) continue;
                    for (Class<?> base : PACKABLE_PART_BASES) {
                        if (base.isInstance(part)) {
                            whitelistedParts.add(partKey(variant));
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    // 探测性构造失败（第三方部件对空 NBT/环境有额外假设）：跳过该子项
                }
            }
        }
    }

    /** 按注册名把 {@code modId:name} 方块加入白名单；解析不到记警告（不炸）。 */
    private static void addBlockByName(String modId, String name, String label) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            whitelistedBlocks.add(Block.getIdFromBlock(block));
        } else {
            FutaGtnhMod.LOG.warn("ME 胶带：白名单里的 {}（{}:{}）没解析到方块，跳过", label, modId, name);
        }
    }

    /**
     * 把一个部件物品的<b>全部已注册子 id</b>（def 物品栈）加入部件白名单。
     * AE2 系部件共用一个物品、按子 id 区分，逐变体入表才不会漏。
     */
    private static void addPartByItemName(String modId, String itemName, String label) {
        net.minecraft.item.Item item = GameRegistry.findItem(modId, itemName);
        if (item == null) {
            FutaGtnhMod.LOG.warn("ME 胶带：白名单里的 {}（{}:{}）没解析到物品，跳过", label, modId, itemName);
            return;
        }
        java.util.List<ItemStack> variants = new java.util.ArrayList<>();
        item.getSubItems(item, null, variants);
        for (ItemStack variant : variants) {
            whitelistedParts.add(partKey(variant));
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

    /** 配置追加条目：block:modid:注册名 / part:modid:物品名:子id（子id 可为 * 通配全部变体） */
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
            if (item == null) {
                FutaGtnhMod.LOG.warn("ME 胶带：白名单条目没解析到物品：{}", trimmed);
            } else if ("*".equals(parts[3])) {
                wildcardPartPrefixes.add(parts[1] + ":" + parts[2] + "#");
            } else {
                whitelistedParts.add(parts[1] + ":" + parts[2] + "#" + parts[3]);
            }
        } else {
            FutaGtnhMod.LOG.warn("ME 胶带：白名单条目格式不对（block:modid:注册名 或 part:modid:物品名:子id[|*]）：{}", trimmed);
        }
    }

    private static boolean isBlockWhitelisted(Block block) {
        return whitelistedBlocks.contains(Block.getIdFromBlock(block));
    }

    private static boolean isPartWhitelisted(ItemStack def) {
        String key = partKey(def);
        if (whitelistedParts.contains(key)) return true;
        int hash = key.indexOf('#');
        return hash >= 0 && wildcardPartPrefixes.contains(key.substring(0, hash + 1));
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
            packageStack = packPart(player, world, x, y, z, hitX, hitY, hitZ, host);
        } else {
            packageStack = packBlock(player, world, x, y, z, tile);
        }
        if (packageStack == null) return false;

        if (!player.capabilities.isCreativeMode && tapeStack != null) {
            tapeStack.damageItem(1, player);
            // vanilla damageItem 断裂时只把 stackSize-- 到 0：size=0 的栈留在槽位
            // 会被客户端渲染成“还有胶带”且后续守卫全过（同纸箱的幽灵堆叠问题）
            com.futa_gtnh.shared.InventoryOps.removeIfEmpty(player, tapeStack);
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
            // 点到线缆本体 / 面板（facade）/ 空面：没有可封装的部件
            notify(player, "futa_gtnh.me_tape.msg.not_packable");
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

        // removePart/cleanup 会触发总线方块的 breakBlock（清空最后一条时）——
        // AE2 的 breakBlock 会掉落剩余部件，抑制窗口防重复（数据已在 extra 里）
        MeTapeDropGuard.begin(world);
        try {
            host.removePart(selected.side, false);
            if (host.isEmpty()) host.cleanup();
        } finally {
            MeTapeDropGuard.end();
        }

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

        // setBlockToAir 会触发 AE2 的 AEBaseTileBlock.breakBlock —— 它把方块实体
        // 库存（驱动器元件、接口样板）原样掉落，而数据已在本封装的 tileTag 里，
        // 不抑制就是复制。窗口与纸箱装箱的 DropGuard 同一套机制
        MeTapeDropGuard.begin(world);
        try {
            world.setBlockToAir(x, y, z);
        } finally {
            MeTapeDropGuard.end();
        }

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
            // 统一消耗姿势（shared/InventoryOps）：size=0 幽灵堆叠会被客户端
            // 渲染成“还在手里”且守卫全过，表现为无限放置
            com.futa_gtnh.shared.InventoryOps.consumeOne(player, packageStack);
        }
        world.playSoundEffect(x + 0.5D, y + 0.5D, z + 0.5D, "random.orb", 0.5F, 1.1F);
        return true;
    }

    /** 方块设备：放到点击面外侧的位置，NBT 喂回新方块实体后同步一次。 */
    private static boolean placeBlock(EntityPlayer player, World world, int px, int py, int pz, String blockName,
        NBTTagCompound tileTag) {
        int separator = blockName.indexOf(':');
        if (separator <= 0) return false;
        if (tileTag == null || tileTag.hasNoTags()) return false; // 数据损坏的封装物品不放
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

        // 提交点：addPart 返回非 null 即视为部件已放进总线（消耗由 place() 统一处理）。
        // 之后的状态恢复一律 best-effort——旧实现里恢复阶段的异常会把「部件已放置
        // 但胶带未消耗」的死状态整个吞进包处理器的 catch(Throwable)，连提示都没有。
        ForgeDirection placed = host.addPart(def.copy(), side, player);
        if (placed == null) return false;
        IPart part = host.getPart(placed);
        if (part != null) restorePartState(tile, part, extra, player);
        host.markForSave();
        return true;
    }

    /**
     * 把封装时保存的部件状态灌回去，且必须遵守 AE2 的加载契约。
     *
     * <p>
     * AE2 的规范顺序是「构造 → readFromNBT → addToWorld」：节点的
     * {@code loadFromNBT} 在 {@code myGrid != null} 时直接抛
     * {@code IllegalStateException("Loading data after part of a grid")}。点击
     * <b>已有总线</b>时 {@code host.addPart} 已经把部件入网，直接
     * {@code part.readFromNBT(extra)} 必炸——恢复被中断、异常被包处理器吞掉，
     * 表现为「放了空内容的部件且胶带不消耗」。这里按 AE2 自己的加载路径重放：
     * 已入网的部件先 {@code removeFromWorld()} 摘网（节点销毁）→ 恢复 NBT
     * （{@code AENetworkProxy} 在节点不存在时只是暂存节点数据，子类状态本次就恢复）
     * → {@code addToWorld()} 重新入网（建节点时补加载暂存数据）。新建总线
     * （onReady 还没跑、未入网）保持 AE2 原生顺序，直接恢复即可。
     */
    private static void restorePartState(TileEntity tile, IPart part, NBTTagCompound extra, EntityPlayer player) {
        try {
            boolean joined = part.getGridNode() != null;
            if (joined) part.removeFromWorld();
            part.readFromNBT(extra);
            if (joined && part.getGridNode() == null) part.addToWorld();
            // addPart 内部的 markForUpdate 发生在状态恢复之前（默认态）——恢复完
            // 再同步一次，客户端立刻拿到真实内容，不用等重登
            if (tile instanceof AEBaseTile baseTile) baseTile.markForUpdate();
        } catch (Throwable failure) {
            // 残余机型恢复失败：部件照常保留（默认状态），胶带照常消耗，日志留堆栈定位
            FutaGtnhMod.LOG.warn(
                "ME 胶带：部件状态恢复失败（{}），部件已按默认状态放置",
                part.getClass()
                    .getName(),
                failure);
            notify(player, "futa_gtnh.me_package.msg.restore_failed");
        }
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
