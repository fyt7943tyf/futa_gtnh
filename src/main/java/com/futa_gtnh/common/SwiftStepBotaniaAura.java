package com.futa_gtnh.common;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
            ItemSwiftStep.getBotaniaAuraSpeed(charm));
    }

    /** 各通道独立强度：每 100 个世界刻度额外运行 speed 次，最高总速度约 11 倍。 */
    static int extraTicks(long time, int speed) {
        if (speed <= 0) return 0;
        int phase = (int) Math.floorMod(time, 100L);
        int clamped = ItemSwiftStep.clampGrowthAuraSpeed(speed);
        return ((phase + 1) * clamped) / 100 - (phase * clamped) / 100;
    }

    static void accelerate(World world, double x, double y, double z, int radius, int speed) {
        accelerate(world, x, y, z, radius, speed, true, new SwiftStepAuraStats());
    }

    static void accelerate(World world, double x, double y, double z, int radius, int speed, boolean enabled,
        SwiftStepAuraStats stats) {
        if (world == null || world.isRemote || !enabled) return;
        Budget budget = BUDGETS.computeIfAbsent(world, ignored -> new Budget());
        budget.begin(world.getTotalWorldTime());
        SwiftStepAuraBudget shared = SwiftStepAuraBudget.get(world);
        budget.discover(world, shared);
        if (budget.remaining == 0) return;
        int range = ItemSwiftStep.clampGrowthAuraRadius(radius);
        double rangeSquared = range * range;

        // Alternate groups so a saturated spark field and a saturated flower field both make progress.
        if ((world.getTotalWorldTime() & 1L) == 0) {
            accelerateEntities(world, x, y, z, speed, stats, budget, shared, rangeSquared);
            accelerateTiles(world, x, y, z, speed, stats, budget, shared, rangeSquared);
        } else {
            accelerateTiles(world, x, y, z, speed, stats, budget, shared, rangeSquared);
            accelerateEntities(world, x, y, z, speed, stats, budget, shared, rangeSquared);
        }
    }

    private static void accelerateEntities(World world, double x, double y, double z, int speed,
        SwiftStepAuraStats stats, Budget budget, SwiftStepAuraBudget shared, double rangeSquared) {
        // 先处理供能实体，避免大型花田用尽本刻预算后让火花/魔力脉冲一直排不上。
        Object[] entities = budget.entities.toArray();
        int start = entities.length == 0 ? 0 : Math.floorMod(budget.entityCursor, entities.length);
        for (int index = 0; index < entities.length && budget.remaining > 0 && shared.available(); index++) {
            int slot = (start + index) % entities.length;
            budget.entityCursor = slot + 1;
            Entity entity = (Entity) ((WeakReference<?>) entities[slot]).get();
            if (entity == null) continue;
            if (!inRange(entity.posX, entity.posY, entity.posZ, x, y, z, rangeSquared)) continue;
            if (!matchesType(entity.getClass(), ENTITY_TYPES) || FAILED_TYPES.contains(entity.getClass())) continue;
            stats.botania++;
            int extra = extraTicks(
                world.getTotalWorldTime(),
                SwiftStepGrowthAura.highestSpeed(world, entity.posX, entity.posY, entity.posZ, 2, speed));
            int done = budget.applied.getOrDefault(entity, 0);
            while (done < extra && budget.remaining > 0
                && entity.worldObj == world
                && !entity.isDead
                && inRange(entity.posX, entity.posY, entity.posZ, x, y, z, rangeSquared)
                && world.blockExists(
                    MathHelper.floor_double(entity.posX),
                    MathHelper.floor_double(entity.posY),
                    MathHelper.floor_double(entity.posZ))) {
                if (!shared.claim(Integer.valueOf(entity.getEntityId()), extra)) break;
                stats.updates++;
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

    }

    private static void accelerateTiles(World world, double x, double y, double z, int speed, SwiftStepAuraStats stats,
        Budget budget, SwiftStepAuraBudget shared, double rangeSquared) {
        // 更新可能替换/删除方块实体，快照避免遍历 world 列表时发生并发修改。
        Object[] tiles = budget.tiles.toArray();
        int start = tiles.length == 0 ? 0 : Math.floorMod(budget.tileCursor, tiles.length);
        for (int index = 0; index < tiles.length && budget.remaining > 0 && shared.available(); index++) {
            int slot = (start + index) % tiles.length;
            budget.tileCursor = slot + 1;
            TileEntity tile = (TileEntity) ((WeakReference<?>) tiles[slot]).get();
            if (tile == null) continue;
            if (!inRange(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5, x, y, z, rangeSquared)) continue;
            if (!matchesType(tile.getClass(), TILE_TYPES) || FAILED_TYPES.contains(tile.getClass())) continue;
            stats.botania++;
            int extra = extraTicks(
                world.getTotalWorldTime(),
                SwiftStepGrowthAura
                    .highestSpeed(world, tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5, 2, speed));
            int done = budget.applied.getOrDefault(tile, 0);
            while (done < extra && budget.remaining > 0
                && isPresent(world, tile)
                && tile.canUpdate()
                && inRange(tile.xCoord + 0.5, tile.yCoord + 0.5, tile.zCoord + 0.5, x, y, z, rangeSquared)) {
                if (!shared.claim(SwiftStepAuraBudget.position(tile.xCoord, tile.yCoord, tile.zCoord), extra)) break;
                stats.updates++;
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

    static void changed(World world, int x, int y, int z) {
        Budget budget = BUDGETS.get(world);
        if (budget == null) return;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile != null && matchesType(tile.getClass(), TILE_TYPES) && budget.discovered.put(tile, true) == null) {
            budget.tiles.add(new WeakReference<>(tile));
        }
    }

    static void forget(World world) {
        BUDGETS.remove(world);
    }

    static void clear() {
        BUDGETS.clear();
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
        private final Map<Object, Boolean> discovered = new WeakHashMap<>();
        private final List<WeakReference<TileEntity>> tiles = new ArrayList<>();
        private final List<WeakReference<Entity>> entities = new ArrayList<>();
        private int tileScan;
        private int entityScan;
        private long nextTileScan;
        private long nextEntityScan;
        private long discoveredTick = Long.MIN_VALUE;

        private void discover(World world, SwiftStepAuraBudget budget) {
            long now = world.getTotalWorldTime();
            if (discoveredTick == now) return;
            discoveredTick = now;
            // Bounded discovery, once per dimension/tick, shared by all wearers. No complete-world snapshots.
            if (now >= nextEntityScan) {
                int size = world.loadedEntityList.size();
                for (int n = 0; n < 512 && entityScan < size && budget.scan(); n++) {
                    Entity value = (Entity) world.loadedEntityList.get(entityScan++);
                    if (matchesType(value.getClass(), ENTITY_TYPES) && discovered.put(value, true) == null) {
                        entities.add(new WeakReference<>(value));
                    }
                }
                if (entityScan >= size) {
                    entityScan = 0;
                    nextEntityScan = now + 10;
                }
            }
            if (now >= nextTileScan) {
                int size = world.loadedTileEntityList.size();
                for (int n = 0; n < 512 && tileScan < size && budget.scan(); n++) {
                    TileEntity value = (TileEntity) world.loadedTileEntityList.get(tileScan++);
                    if (matchesType(value.getClass(), TILE_TYPES) && discovered.put(value, true) == null) {
                        tiles.add(new WeakReference<>(value));
                    }
                }
                if (tileScan >= size) {
                    tileScan = 0;
                    nextTileScan = now + 10;
                }
            }
            tiles.removeIf(ref -> {
                TileEntity tile = ref.get();
                return tile == null || tile.isInvalid();
            });
            entities.removeIf(ref -> {
                Entity entity = ref.get();
                return entity == null || entity.isDead;
            });
        }

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
