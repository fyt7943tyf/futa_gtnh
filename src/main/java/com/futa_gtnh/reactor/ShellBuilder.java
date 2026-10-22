package com.futa_gtnh.reactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 流体反应堆外壳的一键建造：把核心周围 5×5×5 的壳层（3×3×3 之外的那 98 格）
 * 用 IC2 反应堆压力容器填满。
 *
 * <p>
 * IC2 实验版的流体反应堆 = 完整的固体反应堆（核心 + 6 反应堆仓）被压力容器
 * <b>完全包住</b>。本建造器只摆容器本体，<b>不嵌入</b>检修孔/流体端口/红石端口
 * （用户选定：接口玩家自己挖换，摆完聊天栏会提醒一句）—— 需要几个接口、
 * 开在哪个面是各服务器的习惯，替玩家做主不如留给玩家。
 *
 * <p>
 * 安全约束：
 * <ul>
 * <li>反应堆不满配（相邻反应堆仓 &lt; 6）直接拒绝 —— 半个反应堆包上壳
 * 既点不着也没意义，IC2 的结构判定不会过；</li>
 * <li>只往空气/可替换方块里放；已有容器的格子跳过；被实体（玩家等生物）
 * 占着的格子跳过 —— 不把人封进壳里；</li>
 * <li>材料先扣玩家背包、再扣共享背包（与部署同一套取料顺序），缺料时摆多少算多少
 * 并在报告里写清楚；不预扣不预占，摆一个扣一个，中途断了账也是平的。</li>
 * </ul>
 */
public final class ShellBuilder {

    private ShellBuilder() {}

    /** 壳层半径：核心 ±2 的立方，挖掉核心 ±1 的内部，剩 98 格。 */
    private static final int RADIUS = 2;

    public static void build(EntityPlayerMP player, Ic2ReactorCompat.ReactorRef ref) {
        World world = ((TileEntity) ref.reactor).getWorldObj();
        if (world == null || world.isRemote) return;

        // ---- 前置：满配反应堆 + 能拿到容器物品 ----
        int columns = Ic2ReactorCompat.getColumns(ref);
        int chambers = columns - ReactorLayout.MIN_COLUMNS;
        if (chambers < 6) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.shell_need_chambers", chambers));
            return;
        }

        ItemStack vessel = Ic2ReactorCompat.getVesselStack();
        Block vesselBlock = vessel == null ? null : Block.getBlockFromItem(vessel.getItem());
        if (vessel == null || vesselBlock == null) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.shell_no_vessel"));
            return;
        }
        int vesselMeta = vessel.getItemDamage();

        // ---- 算出要摆哪些格 ----
        int alreadyOk = 0;
        int blocked = 0;
        List<int[]> targets = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    boolean inside = Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1;
                    if (inside) continue; // 3×3×3 内部：核心和反应堆仓住在这里

                    int x = ref.coreX + dx;
                    int y = ref.coreY + dy;
                    int z = ref.coreZ + dz;
                    if (world.getBlock(x, y, z) == vesselBlock && world.getBlockMetadata(x, y, z) == vesselMeta) {
                        alreadyOk++;
                        continue;
                    }
                    if (!world.isAirBlock(x, y, z) && !world.getBlock(x, y, z)
                        .isReplaceable(world, x, y, z)) {
                        blocked++;
                        continue;
                    }
                    if (isBlockedByEntity(world, x, y, z)) {
                        blocked++;
                        continue;
                    }
                    targets.add(new int[] { x, y, z });
                }
            }
        }

        if (targets.isEmpty()) {
            player.addChatMessage(
                new ChatComponentTranslation("futa_gtnh.reactor.msg.shell_complete", alreadyOk, blocked));
            return;
        }

        // ---- 取料：先玩家背包，再共享背包（与部署同一套顺序）。
        // 取料池是本方法私有的局部状态：不预扣不预占，摆一个扣一个，中途断了账也是平的 ----
        SharedStorage storage = SharedStorageManager.getStorage();
        ItemKey vesselKey = ItemKey.of(vessel);
        Map<ItemKey, Integer> pool = new LinkedHashMap<>();
        int needed = targets.size();
        int shortage = 0;
        if (vesselKey != null) {
            int taken = takeFromPlayer(player, pool, vesselKey, needed);
            if (needed - taken > 0) {
                long have = storage.getItemAmount(vesselKey);
                long extracted = storage.extractItem(vesselKey, Math.min(have, needed - taken));
                if (extracted > 0) {
                    pool.merge(vesselKey, (int) extracted, Integer::sum);
                    SharedStorageManager.broadcastItemChange(vesselKey);
                }
            }
            shortage = needed - poolCount(pool);
        }

        // ---- 摆放 ----
        int placed = 0;
        for (int[] cell : targets) {
            if (poolCount(pool) <= 0) break;
            world.setBlock(cell[0], cell[1], cell[2], vesselBlock, vesselMeta, 3);
            consumeOne(pool, vesselKey);
            placed++;
        }
        // 没摆出去的料退回共享背包（理论上只有并发改动才会发生）
        int refund = poolCount(pool);
        if (refund > 0 && vesselKey != null) {
            storage.insertItemManual(vesselKey, refund);
            SharedStorageManager.broadcastItemChange(vesselKey);
        }

        if (shortage > 0) {
            player.addChatMessage(
                new ChatComponentTranslation(
                    "futa_gtnh.reactor.msg.shell_shortage",
                    vessel.getDisplayName(),
                    shortage));
        }
        player.addChatMessage(
            new ChatComponentTranslation("futa_gtnh.reactor.msg.shell_done", placed, alreadyOk, blocked));
        if (placed > 0) {
            // 提醒一句：只摆了容器，能工作的流体反应堆还得有检修孔和流体端口
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.shell_ports_hint"));
        }
    }

    private static int poolCount(Map<ItemKey, Integer> pool) {
        int total = 0;
        for (Integer count : pool.values()) {
            if (count != null) total += count;
        }
        return total;
    }

    private static void consumeOne(Map<ItemKey, Integer> pool, ItemKey preferred) {
        // 优先扣「首选键」的账，再扣其它变体（这里通常只有一个键）
        if (preferred != null && pool.getOrDefault(preferred, 0) > 0) {
            pool.merge(preferred, -1, Integer::sum);
            return;
        }
        for (Map.Entry<ItemKey, Integer> entry : pool.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                entry.setValue(entry.getValue() - 1);
                return;
            }
        }
    }

    private static int takeFromPlayer(EntityPlayerMP player, Map<ItemKey, Integer> pool, ItemKey key, int amount) {
        if (amount <= 0) return 0;
        ItemStack[] main = player.inventory.mainInventory;
        int taken = 0;
        for (int i = 0; i < main.length && taken < amount; i++) {
            ItemStack slot = main[i];
            if (slot == null || !key.equals(ItemKey.of(slot))) continue;
            int take = Math.min(amount - taken, slot.stackSize);
            slot.stackSize -= take;
            if (slot.stackSize <= 0) main[i] = null;
            pool.merge(key, take, Integer::sum);
            taken += take;
        }
        if (taken > 0) player.inventory.markDirty();
        return taken;
    }

    /** 方块要放的位置上有没有生物站着（不把人封进壳里）。 */
    private static boolean isBlockedByEntity(World world, int x, int y, int z) {
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
        return !world.getEntitiesWithinAABB(EntityLivingBase.class, box)
            .isEmpty();
    }
}
