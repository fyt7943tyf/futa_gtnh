package com.futa_gtnh.exchange;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.network.PacketTerminalFluid;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 服务端处理客户端发来的存储操作请求。
 *
 * <p>
 * 这里是<b>唯一的信任边界</b>。客户端发过来的每一个数字都要重新校验：
 * <ul>
 * <li>请求的玩家必须<b>真的开着这个界面</b> —— 否则就是伪造包，直接丢掉；</li>
 * <li>数量上限做夹取，不信任客户端给的值；</li>
 * <li>「够不够扣」由 {@link InventoryExchange} 在存储侧再查一次，
 * 而不是拿客户端看到的数量当真。</li>
 * </ul>
 *
 * <p>
 * 另外做了简单的频率限制：一次操作最多同时影响 36 个格子，正常点鼠标
 * 不可能一秒发几百个包。限流不是为了防「快」（那是玩家自己的手速），
 * 而是为了挡「脚本刷包」——每个包都会触发一次全服增量广播，放大效应很明显。
 */
public final class StorageActionHandler {

    private StorageActionHandler() {}

    /**
     * 同一 tick 内允许处理的操作数上限。
     *
     * <p>
     * 这个值必须<b>明显高于合法操作的峰值</b>，否则会误伤真实玩家：
     * 拖着光标划过整个 9×5 网格一次就会产生 45 个操作包，
     * 而 Shift + 双击自己背包里的一格最多会派发 36 个存入包 ——
     * 这些都是同一 tick 内到达的。取 128 留出余量，
     * 同时仍远低于脚本刷包的速率（那至少是每秒几千个）。
     */
    private static final int MAX_ACTIONS_PER_TICK = 128;

    /** 单次操作的数量上限（毫巴 / 个数）。超过就夹到这个值。 */
    private static final long MAX_AMOUNT_PER_ACTION = 1_000_000_000_000L;

    /** 玩家 UUID -> [上次记录的 ticksExisted, 本 tick 已处理的操作数] */
    private static final Map<UUID, int[]> RATE_LIMIT = new HashMap<>();

    public static void handle(EntityPlayerMP player, PacketStorageAction packet) {
        if (player == null || packet == null) return;

        // 必须真的开着共享存储界面，否则视为伪造
        if (!(player.openContainer instanceof ContainerSharedTerminal)) return;
        if (isFlooding(player)) return;

        ContainerSharedTerminal container = (ContainerSharedTerminal) player.openContainer;
        SharedStorage storage = SharedStorageManager.getStorage();
        PacketStorageDelta delta = new PacketStorageDelta();
        DeltaRecorder recorder = new DeltaRecorder(storage, delta);
        long amount = clamp(packet.getAmount());

        try {
            switch (packet.getAction()) {
                case PacketStorageAction.WITHDRAW_ITEM: {
                    ItemKey key = packet.getItemKey();
                    InventoryExchange.withdrawItem(player, key, amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.FILL_CONTAINER: {
                    FluidKey key = packet.getFluidKey();
                    InventoryExchange.fillContainers(player, key, amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.TAKE_FLUID_DISPLAY: {
                    FluidKey key = packet.getFluidKey();
                    InventoryExchange.takeFluidDisplay(player, key, amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.DEPOSIT_INV_SLOT: {
                    InventoryExchange.depositSlot(player, packet.getInvSlot(), amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.DEPOSIT_ALL: {
                    InventoryExchange.depositAll(player, packet.getInvSlot(), storage, recorder);
                    break;
                }
                case PacketStorageAction.DEPOSIT_CURSOR: {
                    InventoryExchange.depositCursor(player, amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.DEPOSIT_MATCHING: {
                    InventoryExchange.depositMatching(player, packet.getItemKey(), amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.DEPOSIT_CRAFT_SLOT: {
                    // 合成栏在容器上而不在玩家身上，所以拿服务端自己那个
                    InventoryExchange.depositFrom(
                        player,
                        container.getCraftMatrix(),
                        packet.getInvSlot(),
                        amount,
                        storage,
                        recorder);
                    break;
                }
                case PacketStorageAction.DRAIN_CONTAINERS: {
                    InventoryExchange.drainContainers(player, storage, recorder);
                    break;
                }
                case PacketStorageAction.DRAIN_CURSOR: {
                    InventoryExchange.drainCursorContainer(player, storage, recorder);
                    break;
                }
                case PacketStorageAction.SET_TERMINAL_FLUID: {
                    handleSetTerminalFluid(player, container, packet.getFluidKey());
                    return;
                }
                case PacketStorageAction.FILL_CRAFT_MATRIX:
                case PacketStorageAction.AUTOCRAFT: {
                    // NEI 合成联动：布局在 keyTag 里，倍率在 amount 里。
                    // CraftFiller 内部会自己 detectAndSendChanges ——
                    // 就算存储一点没动（材料全来自背包），合成栏也是要同步的，
                    // 不能依赖尾部那段「delta 非空才同步」的逻辑。
                    CraftFiller.handle(player, container, packet, storage, recorder);
                    return;
                }
                default:
                    FutaGtnhMod.LOG
                        .warn("共享存储：收到未知操作 {}（玩家 {}），已忽略", packet.getAction(), player.getCommandSenderName());
                    return;
            }
        } catch (Throwable t) {
            // 单次操作出错不能让整个服务器崩掉，也不能让玩家背包和存储对不上。
            //
            // 注意这里<b>不是直接 return</b>：出错前可能已经改了一部分状态
            // （比如 36 格存到第 20 格时炸了），不把「现在到底是什么样」告诉客户端，
            // 大家会一直看着旧数字直到重开界面。全量重发是幂等的，用它兜底最省事。
            FutaGtnhMod.LOG
                .error("共享存储：处理玩家 {} 的操作 {} 时出错，将重发全量以对齐状态", player.getCommandSenderName(), packet.getAction(), t);
            SharedStorageManager.resyncAll();
            container.detectAndSendChanges();
            return;
        }

        if (delta.hasOverflowed()) {
            // 这次操作改动的条目太多 / NBT 太大，一个增量包装不下。
            // 改用全量快照 —— 那条路径是按字节分片的，体积可控。
            SharedStorageManager.resyncAll();
            container.detectAndSendChanges();
            return;
        }

        SharedStorageManager.broadcastDelta(delta);

        // 玩家背包的槽位由容器自己同步（那些是真实槽位），
        // 增量包只负责共享存储网格那部分
        if (!delta.isEmpty()) {
            container.detectAndSendChanges();
        }
    }

    private static void handleSetTerminalFluid(EntityPlayerMP player, ContainerSharedTerminal container, FluidKey key) {
        TileEntitySharedTerminal terminal = container.getTerminal();
        if (terminal == null) return;
        if (key != null && !SharedStorageManager.getStorage()
            .hasFluid(key)) {
            return;
        }
        terminal.setOutputFluid(key);
        NetworkHandler.INSTANCE.sendTo(new PacketTerminalFluid(key), player);
    }

    /** {@code <= 0} 表示「尽可能多」，原样保留；正数则夹到上限。 */
    private static long clamp(long amount) {
        if (amount <= 0L) return amount;
        return Math.min(amount, MAX_AMOUNT_PER_ACTION);
    }

    /**
     * 简单令牌桶：同一个 tick 内的操作数超过阈值就丢弃后续请求。
     *
     * <p>
     * 用 {@code ticksExisted} 当 tick 标记，省掉一个全局 tick 计数器；
     * 玩家下线时由 {@link #forget} 清掉记录，避免 UUID 越积越多。
     */
    private static boolean isFlooding(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        int tick = player.ticksExisted;

        int[] state = RATE_LIMIT.get(id);
        if (state == null) {
            state = new int[] { tick, 0 };
            RATE_LIMIT.put(id, state);
        }

        if (state[0] != tick) {
            state[0] = tick;
            state[1] = 0;
        }

        if (state[1] >= MAX_ACTIONS_PER_TICK) {
            return true;
        }
        state[1]++;
        return false;
    }

    public static void forget(EntityPlayerMP player) {
        if (player != null) {
            RATE_LIMIT.remove(player.getUniqueID());
        }
    }

    /** 供配置变更时说明用：当前是否禁止远程（按键）打开。 */
    public static boolean remoteAccessAllowed() {
        return Config.allowRemoteAccess;
    }
}
