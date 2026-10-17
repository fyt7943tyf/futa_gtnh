package com.futa_gtnh.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemSwiftStep;

/** 服务端追加 Botania 自己的工作刻度；不改世界时间、配方、库存或 mana 数值。 */
public final class SwiftStepBotaniaAura {

    static final int MAX_EXTRA_UPDATES_PER_WORLD_TICK = 4096;
    private static final String POOL = "vazkii.botania.common.block.tile.mana.TilePool";
    private static final Set<String> POOL_TYPES = java.util.Collections.singleton(POOL);
    private static final Set<String> TILE_TYPES = new HashSet<>(
        Arrays.asList(
            POOL,
            "vazkii.botania.common.block.tile.TileSpecialFlower",
            "vazkii.botania.common.block.tile.mana.TileSpreader",
            "vazkii.botania.common.block.tile.TileRuneAltar",
            "vazkii.botania.common.block.tile.TileBrewery",
            "vazkii.botania.common.block.tile.TileEnchanter",
            "vazkii.botania.common.block.tile.TileTerraPlate"));
    private static final Set<String> ENTITY_TYPES = new HashSet<>(
        Arrays.asList("vazkii.botania.common.entity.EntitySpark", "vazkii.botania.common.entity.EntityManaBurst"));
    private static final Map<World, Budget> BUDGETS = new WeakHashMap<>();
    private static final Set<Class<?>> FAILED_TYPES = new HashSet<>();

    private SwiftStepBotaniaAura() {}

    public static void tick(EntityPlayer player, ItemStack charm) {
        if (player == null || !ItemSwiftStep.isBotaniaAuraEnabled(charm)) return;
        accelerate(
            player.worldObj,
            player.posX,
            player.posY,
            player.posZ,
            ItemSwiftStep.getGrowthAuraRadius(charm),
            ItemSwiftStep.getGrowthAuraSpeed(charm));
    }

    /** 与作物光环共用强度：每 100 个世界刻度额外运行 speed 次，最高总速度约 11 倍。 */
    static int extraTicks(long time, int speed) {
        int phase = (int) Math.floorMod(time, 100L);
        int clamped = ItemSwiftStep.clampGrowthAuraSpeed(speed);
        return ((phase + 1) * clamped) / 100 - (phase * clamped) / 100;
    }

    static void accelerate(World world, double x, double y, double z, int radius, int speed) {
        if (world == null || world.isRemote) return;
        int extra = extraTicks(world.getTotalWorldTime(), speed);
        if (extra == 0) return;
        Budget budget = BUDGETS.computeIfAbsent(world, ignored -> new Budget());
        budget.begin(world.getTotalWorldTime());
        if (budget.remaining == 0) return;
        int range = ItemSwiftStep.clampGrowthAuraRadius(radius);
        double rangeSquared = range * range;

        // 先处理供能实体，避免大型花田用尽本刻预算后让火花/魔力脉冲一直排不上。
        Object[] entities = world.loadedEntityList.toArray();
        int start = entities.length == 0 ? 0 : Math.floorMod(budget.entityCursor, entities.length);
        for (int index = 0; index < entities.length && budget.remaining > 0; index++) {
            int slot = (start + index) % entities.length;
            budget.entityCursor = slot + 1;
            if (!(entities[slot] instanceof Entity)) continue;
            Entity entity = (Entity) entities[slot];
            if (!inRange(entity.posX, entity.posY, entity.posZ, x, y, z, rangeSquared)) continue;
            if (!matchesType(entity.getClass(), ENTITY_TYPES) || FAILED_TYPES.contains(entity.getClass())) continue;
            int done = budget.applied.getOrDefault(entity, 0);
            while (done < extra && budget.remaining > 0
                && entity.worldObj == world
                && !entity.isDead
                && inRange(entity.posX, entity.posY, entity.posZ, x, y, z, rangeSquared)
                && world.blockExists(
                    MathHelper.floor_double(entity.posX),
                    MathHelper.floor_double(entity.posY),
                    MathHelper.floor_double(entity.posZ))) {
                budget.applied.put(entity, ++done);
                budget.remaining--;
                try {
                    entity.onUpdate();
                } catch (RuntimeException | LinkageError error) {
                    disable(entity.getClass(), error);
                    break;
                }
            }
        }

        // 更新可能替换/删除方块实体，快照避免遍历 world 列表时发生并发修改。
        Object[] tiles = world.loadedTileEntityList.toArray();
        start = tiles.length == 0 ? 0 : Math.floorMod(budget.tileCursor, tiles.length);
        for (int index = 0; index < tiles.length && budget.remaining > 0; index++) {
            int slot = (start + index) % tiles.length;
            budget.tileCursor = slot + 1;
            if (!(tiles[slot] instanceof TileEntity)) continue;
            TileEntity tile = (TileEntity) tiles[slot];
            if (!inRange(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5, x, y, z, rangeSquared)) continue;
            if (!matchesType(tile.getClass(), TILE_TYPES) || FAILED_TYPES.contains(tile.getClass())) continue;
            int done = budget.applied.getOrDefault(tile, 0);
            while (done < extra && budget.remaining > 0
                && isPresent(world, tile)
                && tile.canUpdate()
                && inRange(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5, x, y, z, rangeSquared)) {
                budget.applied.put(tile, ++done);
                budget.remaining--;
                try {
                    tile.updateEntity();
                    if (isPresent(world, tile) && matchesType(tile.getClass(), POOL_TYPES)) {
                        tickPoolCollisions(world, tile);
                    }
                } catch (RuntimeException | LinkageError error) {
                    disable(tile.getClass(), error);
                    break;
                }
            }
        }
    }

    private static boolean isPresent(World world, TileEntity tile) {
        return tile.getWorldObj() == world && !tile.isInvalid()
            && world.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
            && world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile;
    }

    private static boolean inRange(double tx, double ty, double tz, double x, double y, double z, double squared) {
        return (tx - x) * (tx - x) + (ty - y) * (ty - y) + (tz - z) * (tz - z) <= squared;
    }

    private static boolean matchesType(Class<?> type, Set<String> allowed) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (allowed.contains(current.getName())) return true;
        }
        return false;
    }

    private static void tickPoolCollisions(World world, TileEntity tile) {
        AxisAlignedBB box = AxisAlignedBB
            .getBoundingBox(tile.xCoord, tile.yCoord, tile.zCoord, tile.xCoord + 1, tile.yCoord + 1, tile.zCoord + 1);
        Object[] items = world.getEntitiesWithinAABB(EntityItem.class, box)
            .toArray();
        for (Object candidate : items) {
            EntityItem item = (EntityItem) candidate;
            if (!isPresent(world, tile)) break;
            if (!item.isDead) {
                // 使用原方块碰撞处理，保留配方扣料、催化剂和产物的循环保护年龄。
                world.getBlock(tile.xCoord, tile.yCoord, tile.zCoord)
                    .onEntityCollidedWithBlock(world, tile.xCoord, tile.yCoord, tile.zCoord, item);
            }
        }
    }

    private static void disable(Class<?> type, Throwable error) {
        if (FAILED_TYPES.add(type)) {
            FutaGtnhMod.LOG.warn("迅步：植物魔法加速调用失败，本次运行跳过类型 {}", type.getName(), error);
        }
    }

    private static final class Budget {

        private long time = Long.MIN_VALUE;
        // 弱目标键不反向持有世界，避免卸载世界后被静态缓存保留。
        private final Map<Object, Integer> applied = new WeakHashMap<>();
        private int remaining;
        private int tileCursor;
        private int entityCursor;

        private void begin(long now) {
            if (time == now) return;
            time = now;
            applied.clear();
            remaining = MAX_EXTRA_UPDATES_PER_WORLD_TICK;
        }
    }
}
