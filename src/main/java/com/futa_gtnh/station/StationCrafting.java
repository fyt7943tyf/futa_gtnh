package com.futa_gtnh.station;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.exchange.CraftFiller;
import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import tconstruct.tools.inventory.CraftingStationContainer;

/**
 * 合成站的「NEI 配方直填」服务端半边。
 *
 * <p>
 * 复用终端那套 {@link CraftFiller}（同样是 3×3、同样的守恒取料），这里只提供两样
 * 合成站特有的东西：合成栏（{@code CraftingStationContainer.craftMatrix}）和
 * 「取一次产物」的做法。
 *
 * <p>
 * <b>为什么需要它</b>：匠魂自带的 NEI handler 只把旁边那块存储区当普通箱子，
 * 材料不在当前显示的那一页就判定「没有原料」—— 仓库里明明有几千个也没用。
 * 服务端直填和「材料在第几页」完全无关。
 *
 * <p>
 * 本类里全是 tconstruct 的类型，所以调用点必须先确认匠魂在场
 * （{@link StationViews#canCraftFromSharedStorage}）。
 */
public final class StationCrafting {

    private StationCrafting() {}

    /** 合成站容器里的成品槽号（{@code CraftingStationContainer} 里第一个加进去的就是它）。 */
    private static final int RESULT_SLOT = 0;

    /**
     * 处理来自客户端界面的「填合成栏 / 自动合成」请求。
     *
     * @return 是否处理了（false 表示这个容器不是我们的合成站）
     */
    public static boolean handleCraft(EntityPlayerMP player, Container open, PacketStorageAction packet,
        SharedStorage storage, DeltaRecorder recorder) {
        if (!(open instanceof CraftingStationContainer)) return false;

        CraftingStationContainer station = (CraftingStationContainer) open;
        IInventory matrix = station.craftMatrix;
        if (matrix == null) return false;

        CraftFiller.handle(player, station, matrix, new StationResultTaker(station), packet, storage, recorder);
        return true;
    }

    /** 收尾：把这次改动的增量广播出去，并同步真实槽位（和终端那边的收尾一致）。 */
    public static void broadcast(Container container, PacketStorageDelta delta) {
        if (delta != null && delta.hasOverflowed()) {
            // 改动太多、一个增量包装不下：改用全量快照（分片发送，体积可控）
            SharedStorageManager.resyncAll();
            container.detectAndSendChanges();
            return;
        }
        SharedStorageManager.broadcastDelta(delta);
        container.detectAndSendChanges();
    }

    /**
     * 取一次产物：和玩家左键点产物格<b>完全同一条路径</b>。
     *
     * <p>
     * 顺序照抄原版 {@code Container.slotClick}：先确认整份产物放得下（原版在这里有个
     * 口子 —— 只要背包能塞下一部分，材料就会被整份扣掉、塞不下的产物在下一次重算配方时
     * 蒸发），取出来放到光标上，调匠魂成品槽的 {@code onPickupFromSlot}
     * （它负责消耗合成栏、处理强化配方里的 NBT，也是自动补料认的「刚合成过一次」信号），
     * 最后收进背包。
     */
    private static final class StationResultTaker implements CraftFiller.ResultTaker {

        private final CraftingStationContainer station;

        StationResultTaker(CraftingStationContainer station) {
            this.station = station;
        }

        @Override
        public ItemStack takeOnce(EntityPlayerMP player) {
            // 光标上有东西就不动手：自动合成是一串连续动作，光标被占着容易把东西吞掉
            if (player.inventory.getItemStack() != null) return null;

            Slot slot = this.station.getSlot(RESULT_SLOT);
            if (slot == null || !slot.getHasStack()) return null;

            ItemStack live = slot.getStack();
            if (live == null || live.stackSize <= 0) return null;
            if (!fitsInInventory(player, live)) return null;

            ItemStack taken = slot.decrStackSize(live.stackSize);
            if (taken == null || taken.stackSize <= 0) return null;

            player.inventory.setItemStack(taken);
            slot.onPickupFromSlot(player, taken);
            slot.onSlotChanged();

            player.inventory.addItemStackToInventory(taken);
            if (taken.stackSize > 0) {
                // 理论上不会（上面算过容量）：留在光标上。
                // 下一次 takeOnce 看到光标非空会直接停，不会把这一份覆盖掉
                player.inventory.setItemStack(taken);
            } else {
                player.inventory.setItemStack(null);
            }
            player.updateHeldItem();
            return taken;
        }

        /** @return 玩家背包（含快捷栏）是否装得下 {@code stack} 的全部数量 */
        private static boolean fitsInInventory(EntityPlayer player, ItemStack stack) {
            int limit = Math.min(stack.getMaxStackSize(), 64);
            if (limit <= 0) return false;

            int remaining = stack.stackSize;
            ItemStack[] main = player.inventory.mainInventory;
            for (int i = 0; i < main.length && remaining > 0; i++) {
                ItemStack existing = main[i];
                if (existing == null) {
                    remaining -= limit;
                    continue;
                }
                if (existing.getItem() != stack.getItem() || existing.getItemDamage() != stack.getItemDamage()
                    || !ItemStack.areItemStackTagsEqual(existing, stack)) {
                    continue;
                }
                remaining -= Math.max(0, Math.min(remaining, limit - existing.stackSize));
            }
            return remaining <= 0;
        }
    }
}
