package com.futa_gtnh.exchange;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.PacketCraftResult;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.network.PacketTerminalIoSync;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.station.StationCrafting;

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

    /** 玩家 UUID -> 最近一次 NEI 步骤回执，用来防止重复包重复扣料。 */
    private static final Map<UUID, CraftReceipt> LAST_CRAFT_REQUESTS = new HashMap<>();

    public static void handle(EntityPlayerMP player, PacketStorageAction packet) {
        if (player == null || packet == null) return;

        // 网络层通常不会重复投递，但 NEI 步骤是「扣料 + 合成」操作，不能把这个
        // 假设当成守恒条件。相同请求编号只回放上一次结果，不再执行一次合成。
        if (replayCraftRequest(player, packet)) return;

        Container open = player.openContainer;

        // 必须真的开着共享存储界面，否则视为伪造。
        // 例外：挂着共享存储的匠魂合成站 —— 那里的 NEI「填合成栏 / 自动合成」也走这条通道
        // （理由见 station/StationCrafting），同样要求玩家真的开着那个界面。
        boolean terminal = open instanceof ContainerSharedTerminal;
        boolean station = !terminal && futa$isSharedChestStation(open);
        if (!terminal && !station) {
            completeCraftRequest(player, packet, 0);
            return;
        }
        if (isFlooding(player)) {
            completeCraftRequest(player, packet, 0);
            return;
        }

        SharedStorage storage = SharedStorageManager.getStorage();
        PacketStorageDelta delta = new PacketStorageDelta();
        DeltaRecorder recorder = new DeltaRecorder(storage, delta);
        long amount = clamp(packet.getAmount());

        if (station) {
            // 合成站只认「填合成栏 / 自动合成」两个动作，其它动作（终端界面专用的）直接忽略
            byte action = packet.getAction();
            if (action != PacketStorageAction.FILL_CRAFT_MATRIX && action != PacketStorageAction.AUTOCRAFT) {
                completeCraftRequest(player, packet, 0);
                return;
            }
            int crafted = -1;
            try {
                crafted = StationCrafting.handleCraft(player, open, packet, storage, recorder);
                if (crafted >= 0) {
                    StationCrafting.broadcast(open, delta);
                }
            } catch (Throwable t) {
                FutaGtnhMod.LOG.error("共享存储：处理玩家 {} 的合成站配方直填时出错，将重发全量以对齐状态", player.getCommandSenderName(), t);
                SharedStorageManager.resyncAll();
                open.detectAndSendChanges();
                crafted = 0;
            }
            completeCraftRequest(player, packet, Math.max(0, crafted));
            return;
        }

        ContainerSharedTerminal container = (ContainerSharedTerminal) open;
        boolean forceContainerSync = false;
        int crafted = 0;

        try {
            switch (packet.getAction()) {
                case PacketStorageAction.WITHDRAW_ITEM: {
                    ItemKey key = packet.getItemKey();
                    InventoryExchange.withdrawItem(player, key, amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.WITHDRAW_ITEM_EMPTY: {
                    InventoryExchange.withdrawItemToEmptySlot(player, packet.getItemKey(), amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.WITHDRAW_ALL: {
                    InventoryExchange.withdrawAllItems(player, storage, recorder);
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
                case PacketStorageAction.WITHDRAW_TO_CURSOR: {
                    InventoryExchange.withdrawToCursor(player, packet.getItemKey(), amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.COLLECT_TO_CURSOR: {
                    // 双击收集既可能只移动玩家背包里的真实槽位，也可能还要从共享存储补足。
                    // 即使 delta 为空，真实槽位和光标也变了，必须强制同步容器。
                    InventoryExchange.collectToCursor(
                        player,
                        container.getCraftMatrix(),
                        packet.getItemKey(),
                        amount,
                        storage,
                        recorder);
                    forceContainerSync = true;
                    break;
                }
                case PacketStorageAction.HOTBAR_SWAP: {
                    InventoryExchange
                        .swapHotbarItem(player, packet.getInvSlot(), packet.getItemKey(), amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.DROP_ITEM: {
                    InventoryExchange.dropItem(player, packet.getItemKey(), amount, storage, recorder);
                    break;
                }
                case PacketStorageAction.DROP_ALL_ITEMS: {
                    InventoryExchange.dropAllItems(player, storage, recorder);
                    break;
                }
                case PacketStorageAction.DROP_MATCHING_ITEMS: {
                    InventoryExchange.dropItem(player, packet.getItemKey(), 0L, storage, recorder);
                    break;
                }
                case PacketStorageAction.DRAIN_CURSOR: {
                    InventoryExchange.drainCursorContainer(player, storage, recorder);
                    break;
                }
                case PacketStorageAction.SET_TERMINAL_IO: {
                    // 六个面怎么主动搬东西：整份配置在 keyTag 里。
                    // 客户端算出来的任何东西都不被信任 —— 配置只影响「搬什么、往哪搬」，
                    // 真正搬的时候每一步仍然由服务端自己校验（见 TerminalIoEngine）
                    handleSetTerminalIo(player, container, packet.getLayoutTag());
                    return;
                }
                case PacketStorageAction.REQUEST_TERMINAL_IO: {
                    PacketTerminalIoSync.send(player, container.getTerminal());
                    return;
                }
                case PacketStorageAction.DUMP_CRAFT_GRID: {
                    // 侧栏「返还原料」：把合成栏 9 格整份退回共享存储。
                    // 逐格走 InventoryExchange.depositFrom —— 和玩家自己 Shift 点某一格
                    // 是同一条代码路径，所以「存不下 / 认不出键」这些边界也一样。
                    long moved = 0L;
                    for (int i = 0; i < ContainerSharedTerminal.CRAFT_SLOTS; i++) {
                        moved += InventoryExchange
                            .depositFrom(player, container.getCraftMatrix(), i, 0L, storage, recorder);
                    }
                    FutaGtnhMod.LOG.info("共享存储：合成栏返还 {} 个原料到仓库（玩家 {}）", moved, player.getCommandSenderName());
                    if (moved > 0L) {
                        player.addChatMessage(
                            new net.minecraft.util.ChatComponentTranslation("futa_gtnh.msg.craft.dumped", moved));
                    }
                    forceContainerSync = true;
                    break;
                }
                case PacketStorageAction.FILL_CRAFT_MATRIX:
                case PacketStorageAction.AUTOCRAFT: {
                    // NEI 合成联动：布局在 keyTag 里，倍率在 amount 里。
                    //
                    // 合成是真的在动共享存储（填栏取料、按配方补料），所以这里
                    // <b>必须走到尾部的增量广播</b>：以前这里直接 return，
                    // 结果是「东西已经消耗掉了，客户端那块库存数字却一动不动，
                    // 关掉界面再打开才对」。容器那部分 CraftFiller 内部已经同步过，
                    // 再用 forceContainerSync 兜一次（幂等）。
                    //
                    // 终端的合成栏是原版的 InventoryCrafting + InventoryCraftResult。
                    // 产物<b>直接进共享存储</b>：从仓库拿的料，做出来的东西回仓库 ——
                    // 一次 Shift 点下去，玩家背包一个格子都不用占。
                    // （不带 Shift 的普通点击仍然走原版，产物照旧留在光标/背包上。）
                    crafted = CraftFiller
                        .handle(player, container, container.getCraftMatrix(), new CraftFiller.ResultTaker() {

                            @Override
                            public ItemStack takeOnce(EntityPlayerMP who) {
                                return container.transferCraftResultToStorage(who, storage, recorder);
                            }
                        }, packet, storage, recorder);
                    forceContainerSync = true;
                    break;
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
            completeCraftRequest(player, packet, 0);
            return;
        }

        if (delta.hasOverflowed()) {
            // 这次操作改动的条目太多 / NBT 太大，一个增量包装不下。
            // 改用全量快照 —— 那条路径是按字节分片的，体积可控。
            SharedStorageManager.resyncAll();
            container.detectAndSendChanges();
            completeCraftRequest(player, packet, crafted);
            return;
        }

        SharedStorageManager.broadcastDelta(delta);

        // 玩家背包的槽位由容器自己同步（那些是真实槽位），
        // 增量包只负责共享存储网格那部分
        if (forceContainerSync || !delta.isEmpty()) {
            container.detectAndSendChanges();
        }
        completeCraftRequest(player, packet, crafted);
    }

    /**
     * 换掉终端方块「六个面怎么主动搬东西」的整份配置。
     *
     * <p>
     * 这里只做<b>形状</b>上的校验（是一份能读的 NBT），不做业务校验：
     * 配置本身只是「搬什么、往哪搬」的意愿，真正搬的每一步都由
     * {@code TerminalIoEngine} 在服务端现查现搬，客户端改不出物品来。
     * 改完回推一份权威值，客户端那份立刻对齐。
     */
    private static void handleSetTerminalIo(EntityPlayerMP player, ContainerSharedTerminal container,
        net.minecraft.nbt.NBTTagCompound tag) {
        TileEntitySharedTerminal terminal = container.getTerminal();
        if (terminal == null || tag == null) return;

        terminal.getIo()
            .readFromNbt(tag);
        terminal.onIoChanged();
        PacketTerminalIoSync.send(player, terminal);
    }

    /** {@code <= 0} 表示「尽可能多」，原样保留；正数则夹到上限。 */
    private static long clamp(long amount) {
        if (amount <= 0L) return amount;
        return Math.min(amount, MAX_AMOUNT_PER_ACTION);
    }

    /** 只有带编号的 AUTOCRAFT 才是 NEI 的同步步骤请求。 */
    private static boolean isCraftRequest(PacketStorageAction packet) {
        return packet != null && packet.getAction() == PacketStorageAction.AUTOCRAFT && packet.getRequestId() != 0L;
    }

    /**
     * 重放相同请求的权威结果。
     *
     * <p>
     * 同一个客户端只允许一个 NEI 步骤在途，所以保存最近一次结果即可；玩家重新登录
     * 时由 {@link #forget} 清掉。旧编号不再执行，避免迟到包把已经完成的步骤再做一次。
     */
    private static boolean replayCraftRequest(EntityPlayerMP player, PacketStorageAction packet) {
        if (!isCraftRequest(packet)) return false;

        CraftReceipt previous = LAST_CRAFT_REQUESTS.get(player.getUniqueID());
        if (previous == null) return false;

        if (previous.requestId == packet.getRequestId()) {
            PacketCraftResult.send(player, previous.requestId, previous.crafted, previous.status);
            return true;
        }

        if (packet.getRequestId() < previous.requestId) {
            PacketCraftResult.send(player, packet.getRequestId(), 0, PacketCraftResult.FAILED);
            return true;
        }
        return false;
    }

    /** 在所有存储/容器同步包发出之后发送 NEI 步骤回执。 */
    private static void completeCraftRequest(EntityPlayerMP player, PacketStorageAction packet, int crafted) {
        if (!isCraftRequest(packet)) return;

        int actual = Math.max(0, crafted);
        long requested = packet.getAmount();
        byte status;
        if (actual <= 0) {
            status = PacketCraftResult.FAILED;
        } else if (requested > 0L && actual < requested) {
            status = PacketCraftResult.PARTIAL;
        } else {
            status = PacketCraftResult.COMPLETED;
        }

        CraftReceipt receipt = new CraftReceipt(packet.getRequestId(), actual, status);
        LAST_CRAFT_REQUESTS.put(player.getUniqueID(), receipt);
        PacketCraftResult.send(player, receipt.requestId, receipt.crafted, receipt.status);
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
            UUID id = player.getUniqueID();
            RATE_LIMIT.remove(id);
            LAST_CRAFT_REQUESTS.remove(id);
        }
    }

    private static final class CraftReceipt {

        private final long requestId;
        private final int crafted;
        private final byte status;

        private CraftReceipt(long requestId, int crafted, byte status) {
            this.requestId = requestId;
            this.crafted = crafted;
            this.status = status;
        }
    }

    /** 供配置变更时说明用：当前是否禁止远程（按键）打开。 */
    public static boolean remoteAccessAllowed() {
        return Config.allowRemoteAccess;
    }

    /**
     * 「这个容器是挂着共享存储的匠魂合成站吗」。
     *
     * <p>
     * 单独包一层是因为 {@code station} 包里的类型全是 tconstruct 的：先探测匠魂在不在，
     * 再让 JVM 去解析那些符号引用（不在时这个方法体根本不会执行到）。
     */
    private static boolean futa$isSharedChestStation(Container open) {
        if (open == null) return false;
        if (!com.futa_gtnh.tinkers.TinkersAutoFill.isAvailable()) return false;
        try {
            return com.futa_gtnh.station.StationViews.canCraftFromSharedStorage(open);
        } catch (Throwable t) {
            return false;
        }
    }
}
