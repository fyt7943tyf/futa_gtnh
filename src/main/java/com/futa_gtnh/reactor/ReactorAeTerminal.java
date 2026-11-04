package com.futa_gtnh.reactor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.shared.ItemKey;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.features.IWirelessTermRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.Loader;

/**
 * 反应堆布局工具的 AE2 触碰层 —— 本包里所有对 AE2 的触碰都从这走（定位仿
 * {@link Ic2ReactorCompat}，那是对 IC2 做的同一件事）。
 *
 * <p>
 * <b>「随身 ME 终端」访问链</b>（照抄 GT 多方块投影仪背后的 StructureCompat
 * 实现，{@code WirelessTerminalStackExtractor.fromItem}）：终端物品的加密密钥
 * 就是它绑定访问点的序列号 ——
 * {@code registries().wireless()} 识别终端 → {@code getEncryptionKey()} 拿序列号
 * → {@code registries().locatable().getLocatableBy(serial)} 找到访问点方块
 * （{@code ILocatable}）→ {@code IGridHost.getGridNode()} 拿到整个 {@link IGrid}。
 * <b>全程不需要放置任何方块</b>，第三方终端（WCT、AE2FC 等）注册在同一个
 * registry，天然兼容。
 *
 * <p>
 * 闸门对齐 AE2 自家无线流程：范围内有<b>激活的</b>访问点（注册表的
 * {@link IWirelessTermRegistry#checkRange}，无限距离卡在 handler 里豁免）、
 * 终端有基础电量（打开门槛 0.5 AE，同 {@code WirelessRegistry.performCheck}）、
 * 合成下单另查 {@code SecurityPermissions.CRAFT}。实际取货走
 * {@code poweredExtraction}（原子扣终端电池 + 取物），读库存是纯只读不耗电。
 *
 * <p>
 * 取货/统计一律用<b>宽松口径</b>（同物品+耐久的所有 NBT 变体合并，全新件优先），
 * 与部署器对共享背包的语义一致（见 {@code ReactorDeployer#takeFromStorageLoose}）。
 */
public final class ReactorAeTerminal {

    private ReactorAeTerminal() {}

    /** AE2 自家打开终端 GUI 的最低电量门槛（{@code WirelessRegistry.performCheck}）。 */
    private static final double MIN_OPEN_POWER = 0.5D;

    /**
     * 已解析的随身终端：网络 + handler + 物品栈。
     *
     * <p>
     * 注意网络对象在断线重连后会被 AE 换掉，所以 {@code handle} 是<b>一次性</b>的：
     * 每次部署/下单/预览都重新 {@link #find}，不跨 tick 缓存。
     */
    public static final class TerminalHandle {

        public final IGrid grid;
        public final IWirelessTermHandler handler;
        public final ItemStack stack;

        TerminalHandle(IGrid grid, IWirelessTermHandler handler, ItemStack stack) {
            this.grid = grid;
            this.handler = handler;
            this.stack = stack;
        }

        /** @return 该网络是否允许这位玩家下合成订单（对齐 AE2 合成终端的 CRAFT 权限） */
        public boolean canCraft(EntityPlayerMP player) {
            ISecurityGrid security = grid.getCache(ISecurityGrid.class);
            return security != null && security.hasPermission(player, SecurityPermissions.CRAFT);
        }

        /** @return 该网络的合成缓存（下单用）；理论上 AE 网络必有，判空只是防御 */
        public ICraftingGrid crafting() {
            return grid.getCache(ICraftingGrid.class);
        }
    }

    /** @return AE2 是否在场（reactor 包内守卫；本功能的注册不依赖 AE2 存在） */
    public static boolean isAvailable() {
        return Loader.isModLoaded("appliedenergistics2");
    }

    // ==================================================================
    // 终端查找
    // ==================================================================

    /**
     * 在玩家身上找第一个可用的 ME 终端：主背包 → Baubles 饰品栏。
     * 每个候选都过完整校验链（识别 → 密钥 → 定位 → 范围 → 电量）。
     *
     * @return null = 没带终端 / 没绑访问点 / 超范围 / 没电
     */
    public static TerminalHandle find(EntityPlayerMP player) {
        if (!isAvailable() || player == null) return null;

        for (ItemStack stack : player.inventory.mainInventory) {
            TerminalHandle handle = resolve(stack, player);
            if (handle != null) return handle;
        }
        // Baubles 守卫与访问写在同一行：ReactorAeBaubles import 了 baubles 类型，
        // 没装 Baubles 时这行不执行就不会加载它（仓库既有惯例）
        if (Loader.isModLoaded("Baubles|Expanded") || Loader.isModLoaded("Baubles")) {
            for (ItemStack stack : ReactorAeBaubles.scan(player)) {
                TerminalHandle handle = resolve(stack, player);
                if (handle != null) return handle;
            }
        }
        return null;
    }

    /** 单个物品栈的完整解析链；任何一步失败都安静返回 null（调用方继续扫下一个）。 */
    private static TerminalHandle resolve(ItemStack stack, EntityPlayerMP player) {
        if (stack == null || stack.getItem() == null) return null;

        IWirelessTermRegistry wireless = AEApi.instance()
            .registries()
            .wireless();
        if (!wireless.isWirelessTerminal(stack)) return null;
        IWirelessTermHandler handler = wireless.getWirelessTerminalHandler(stack);
        if (handler == null) return null;

        String key = handler.getEncryptionKey(stack);
        if (key == null || key.isEmpty()) return null;
        long serial;
        try {
            serial = Long.parseLong(key);
        } catch (NumberFormatException unbound) {
            return null; // 密钥不是序列号 = 还没绑定过访问点
        }
        ILocatable locatable = AEApi.instance()
            .registries()
            .locatable()
            .getLocatableBy(serial);
        if (!(locatable instanceof IGridHost)) return null;
        IGridNode node = ((IGridHost) locatable).getGridNode(ForgeDirection.UNKNOWN);
        if (node == null) return null;
        IGrid grid = node.getGrid();
        if (grid == null) return null;

        if (!wireless.checkRange(stack, player)) return null;
        if (!handler.hasInfinityPower(stack) && !handler.hasPower(player, MIN_OPEN_POWER, stack)) return null;

        return new TerminalHandle(grid, handler, stack);
    }

    // ==================================================================
    // 读库存（只读，不耗电）
    // ==================================================================

    /**
     * @return 该 (物品, 耐久) 在终端网络里的宽松总量（所有 NBT 变体合计）
     */
    public static long itemCountLoose(TerminalHandle handle, Item item, int meta) {
        IMEMonitor<IAEItemStack> inventory = itemInventory(handle);
        if (inventory == null) return 0L;
        long total = 0L;
        for (IAEItemStack stack : inventory.getStorageList()) {
            if (stack.getItem() != item || stack.getItemDamage() != meta) continue;
            total += stack.getStackSize();
        }
        return total;
    }

    /** @return 该网络里有没有能合成这个（全新变体）的在线样板 */
    public static boolean hasPattern(TerminalHandle handle, Item item, int meta) {
        ICraftingGrid crafting = handle.crafting();
        if (crafting == null) return false;
        // 与 PublicMeCraftingView 同款判定：全新变体的 IAEStack 精确匹配样板表
        return crafting.getCraftingMultiPatterns()
            .containsKey(AEItemStack.create(new ItemStack(item, 1, meta)));
    }

    // ==================================================================
    // 取货（扣终端电池）
    // ==================================================================

    /**
     * 宽松取货：同物品+耐久，全新件优先、其余按损耗升序（与部署器对共享背包
     * 的 freshness 口径一致），走 {@code poweredExtraction} 原子扣电。
     *
     * @return 实际取到的 变体键 → 数量；调用方负责登记进取料池
     */
    public static Map<ItemKey, Integer> extractLoose(TerminalHandle handle, EntityPlayerMP player, Item item, int meta,
        int amount) {
        Map<ItemKey, Integer> taken = new LinkedHashMap<>();
        IMEMonitor<IAEItemStack> inventory = itemInventory(handle);
        if (inventory == null || amount <= 0) return taken;

        List<IAEItemStack> candidates = new ArrayList<>();
        for (IAEItemStack stack : inventory.getStorageList()) {
            if (stack.getItem() != item || stack.getItemDamage() != meta) continue;
            if (stack.getStackSize() <= 0) continue;
            candidates.add(stack);
        }
        candidates.sort(Comparator.comparingInt(ReactorAeTerminal::freshness));

        IEnergySource power = new TerminalPower(handle.handler, player, handle.stack);
        PlayerSource source = new PlayerSource(player, null);
        int remaining = amount;
        for (IAEItemStack candidate : candidates) {
            if (remaining <= 0) break;
            IAEItemStack request = candidate.copy();
            request.setStackSize(Math.min(candidate.getStackSize(), remaining));
            IAEItemStack extracted = AEApi.instance()
                .storage()
                .poweredExtraction(power, inventory, request, source);
            if (extracted == null || extracted.getStackSize() <= 0) continue;
            ItemKey key = ItemKey.of(extracted.getItemStack());
            if (key == null) continue;
            int got = (int) extracted.getStackSize();
            taken.merge(key, got, Integer::sum);
            remaining -= got;
        }
        return taken;
    }

    /**
     * 精确取货（按 {@code ItemKey} 原样，含 NBT）。
     *
     * @return 实际取到的数量
     */
    public static int extractExact(TerminalHandle handle, EntityPlayerMP player, ItemKey key, int amount) {
        if (amount <= 0) return 0;
        IMEMonitor<IAEItemStack> inventory = itemInventory(handle);
        if (inventory == null) return 0;

        IAEItemStack request = AEItemStack.create(key.prototype(amount));
        if (request == null) return 0;
        IAEItemStack extracted = AEApi.instance()
            .storage()
            .poweredExtraction(
                new TerminalPower(handle.handler, player, handle.stack),
                inventory,
                request,
                new PlayerSource(player, null));
        return extracted == null ? 0 : (int) extracted.getStackSize();
    }

    // ==================================================================
    // 电量
    // ==================================================================

    /** 下单前固定扣一笔终端电量；失败（电量不足）返回 false。 */
    public static boolean drainPower(TerminalHandle handle, EntityPlayerMP player, double amount) {
        if (amount <= 0) return true;
        if (handle.handler.hasInfinityPower(handle.stack)) return true;
        return handle.handler.usePower(player, amount, handle.stack);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static IMEMonitor<IAEItemStack> itemInventory(TerminalHandle handle) {
        IStorageGrid storage = handle.grid.getCache(IStorageGrid.class);
        return storage == null ? null : storage.getItemInventory();
    }

    /** 「新鲜度」排序值：越小越新（无 NBT = 0；{@code advDmg} 按损耗排；其它 NBT 兜底）。 */
    private static int freshness(IAEItemStack stack) {
        ItemKey key = ItemKey.of(stack.getItemStack());
        return key == null ? Integer.MAX_VALUE : ReactorDeployer.freshnessRank(key);
    }

    /**
     * 终端电池当 AE 电源用（仿 StructureCompat 的 {@code WirelessTerminalEnergySource}）：
     * 模拟查询 {@code hasPower}，实际扣减 {@code usePower}，无限电卡直接放行。
     */
    private static final class TerminalPower implements IEnergySource {

        private final IWirelessTermHandler handler;
        private final EntityPlayer player;
        private final ItemStack stack;

        TerminalPower(IWirelessTermHandler handler, EntityPlayer player, ItemStack stack) {
            this.handler = handler;
            this.player = player;
            this.stack = stack;
        }

        @Override
        public double extractAEPower(double amt, Actionable mode, PowerMultiplier multiplier) {
            if (handler.hasInfinityPower(stack)) return amt;
            if (mode == Actionable.SIMULATE) return handler.hasPower(player, amt, stack) ? amt : 0;
            return handler.usePower(player, amt, stack) ? amt : 0;
        }
    }
}
