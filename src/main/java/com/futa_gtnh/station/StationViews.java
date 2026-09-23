package com.futa_gtnh.station;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.PacketStationView;
import com.futa_gtnh.shared.SharedStorageManager;

import tconstruct.tools.inventory.CraftingStationContainer;
import tconstruct.tools.logic.CraftingStationLogic;

/**
 * 「哪个合成站挂着共享存储」的登记处。
 *
 * <p>
 * 用 {@link WeakHashMap} 以方块实体为键：合成站被拆掉、区块被卸载之后，
 * 对应的表项会跟着方块实体一起被回收，不会越积越多。客户端和服务端各有一份
 * （同一个逻辑坐标在两边的方块实体是两个不同的对象），互不干扰。
 *
 * <p>
 * <b>本类里全是 tconstruct 的类型</b>，所以只能在确认匠魂在场之后才碰它 ——
 * 调用点见 {@code MixinCraftingStationLogic}（插件已经拦掉了缺席的情况）和
 * {@code TinkersAutoFill.isAvailable()} 守卫下的 {@code SharedStorageManager}。
 */
public final class StationViews {

    private StationViews() {}

    /**
     * 匠魂把「旁边的容器」的槽位排在玩家背包之后，起始号是 46
     * （{@code CraftingStationContainer.SIDE_INVENTORY_FIRST_SLOT}）。
     */
    public static final int CHEST_FIRST = 46;

    /** 玩家背包在容器里的区间，与匠魂常量一致（10 ~ 45 是主背包，37 ~ 45 是快捷栏）。 */
    public static final int PLAYER_FIRST = 10;
    public static final int PLAYER_END = 46;

    private static final Map<CraftingStationLogic, SharedStorageInventory> VIEWS = new WeakHashMap<>();

    private static boolean announcedClient;
    private static boolean announcedServer;
    private static boolean failed;

    /**
     * 拿到这个合成站的「共享存储箱子」，没有就建一个。
     *
     * @param remote 客户端传 true：那一边只需要一个只读镜像
     */
    public static SharedStorageInventory get(CraftingStationLogic logic, boolean remote) {
        if (logic == null) return null;
        SharedStorageInventory existing = VIEWS.get(logic);
        if (existing != null) return existing;
        SharedStorageInventory created = new SharedStorageInventory(remote);
        VIEWS.put(logic, created);
        return created;
    }

    /** @return 已经挂上的那个箱子；没挂上返回 null（匠魂原生的箱子不受影响） */
    public static SharedStorageInventory existing(CraftingStationLogic logic) {
        return logic == null ? null : VIEWS.get(logic);
    }

    /**
     * 这个玩家是不是正开着「挂着共享存储的合成站」。
     *
     * <p>
     * 增量广播用它决定要不要多发一份：合成站旁边的箱子显示的就是共享存储本身，
     * 别人往存储里放东西，开着合成站的玩家也该立刻看到。
     */
    public static boolean isStationViewer(EntityPlayerMP player) {
        if (player == null) return false;
        try {
            Container open = player.openContainer;
            if (!(open instanceof CraftingStationContainer)) return false;
            return existing(((CraftingStationContainer) open).logic) != null;
        } catch (Throwable t) {
            // 匠魂版本对不上之类的意外：当作「不是观众」，绝不能影响广播主流程
            return false;
        }
    }

    /**
     * 这个容器是不是「挂着共享存储的合成站」。
     *
     * <p>
     * NEI 的「填入合成栏 / 自动合成」请求走它：只有真的开着这样的界面，
     * 才允许通过那条通道动共享存储（和终端界面同一个信任边界）。
     */
    public static boolean canCraftFromSharedStorage(Container container) {
        if (!(container instanceof CraftingStationContainer)) return false;
        try {
            return existing(((CraftingStationContainer) container).logic) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 玩家推来「我这一页显示哪些东西」。
     *
     * <p>
     * <b>这里是信任边界</b>：只接受「玩家自己正开着的那个合成站」的视图，
     * 而且只取物品键 —— 数量、能不能取，全部由共享存储在服务端现查，
     * 所以伪造这个包最多让别人的界面显示错乱，拿不到任何额外物品。
     */
    public static void applyClientView(EntityPlayerMP player, PacketStationView packet) {
        if (player == null || packet == null) return;

        Container open = player.openContainer;
        if (!(open instanceof CraftingStationContainer)) return;

        CraftingStationLogic logic = ((CraftingStationContainer) open).logic;
        if (logic == null || logic.getWorldObj() == null) return;

        // 必须就是玩家开着的那个方块：换过界面 / 走远了都对不上
        if (logic.xCoord != packet.getX() || logic.yCoord != packet.getY()
            || logic.zCoord != packet.getZ()
            || logic.getWorldObj().provider.dimensionId != packet.getDimension()) {
            return;
        }

        SharedStorageInventory inventory = existing(logic);
        if (inventory == null || inventory.isRemote()) return;

        inventory.setView(packet.getKeys());

        if (packet.isRequestSync()) {
            // 客户端手里还没有共享存储的快照（比如刚上线就直接来开合成站）：
            // 给它发一份全量，之后靠增量包保持同步
            SharedStorageManager.sendSnapshotTo(player);
        }
    }

    /**
     * 挂载成功时各报一次日志。
     *
     * <p>
     * 这条日志是「功能到底有没有起来」的唯一线索：玩家看到合成站旁边没有存储区时，
     * 先看日志里有没有这一行 —— 没有就是匠魂探测没过（{@code FutaGtnhMixinPlugin}），
     * 有就是别的原因（旁边放了真箱子等）。
     */
    public static void announceMountOnce(boolean remote) {
        if (remote) {
            if (announcedClient) return;
            announcedClient = true;
        } else {
            if (announcedServer) return;
            announcedServer = true;
        }
        FutaGtnhMod.LOG.info("匠魂合成站：共享存储已挂上（{}侧，{} 格，旁边没放箱子时自动出现）", remote ? "客户" : "服务", SharedStorageInventory.SIZE);
    }

    /** 挂载失败只报一次完整堆栈，之后静默 —— 别让每次开界面都刷屏。 */
    public static void reportMountFailure(Throwable t) {
        if (failed) return;
        failed = true;
        FutaGtnhMod.LOG.warn("共享存储：给合成站挂共享存储失败，合成站按原版打开（本模组其余功能不受影响）", t);
    }

    static {
        // 类被加载时留一行 debug：排查时先看这行在不在，
        // 就知道是匠魂没加载（类根本没被碰）还是挂载逻辑没跑到
        FutaGtnhMod.LOG.debug("合成站共享存储：挂载模块已就绪");
    }
}
