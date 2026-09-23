package com.futa_gtnh.inventory;

import java.util.Arrays;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.exchange.FluidContainerHelper;
import com.futa_gtnh.exchange.InventoryExchange;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.util.GTUtility;

/**
 * 共享存储界面的容器。
 *
 * <p>
 * 槽位分布（下标必须两端一致，靠两边跑同一个构造函数保证）：
 *
 * <pre>
 *   0 .. 44   共享存储网格（虚拟槽位）
 *   45 .. 80  玩家主背包 + 快捷栏
 *   81 .. 84  护甲（39=头盔, 38=胸甲, 37=护腿, 36=靴子）
 *   85 .. 88  合成栏 2×2
 *   89        合成产物
 * </pre>
 *
 * <p>
 * <b>核心约定：服务端的这个容器不做任何实际增删。</b>
 * 不管是 {@link #slotClick} 还是 {@link #transferStackInSlot}，在服务端都只是
 * 「什么都不做，返回 null」；客户端则只是「发一个请求包出去」。
 * 真正的改动全部集中在 {@code StorageActionHandler} 这一条路径上，
 * 由它统一校验服务端状态。这么设计的原因很实际：
 *
 * <ul>
 * <li>原版 {@code slotClick} 是<b>两端都会跑</b>的（客户端
 * {@code PlayerControllerMP.windowClick} 会先本地跑一遍再发包）。如果让它在
 * 服务端也改存储，那么「客户端本地预测 + 服务端执行」就会各改一次，
 * 变成存一次加两回。</li>
 * <li>虚拟槽位没有能在两端同步的真实状态，任何依赖原版预测的逻辑都会算出
 * 不一样的结果。只留一条路径就不存在「两边算法必须一致」这个负担。</li>
 * </ul>
 *
 * <p>
 * <b>唯一的例外是合成。</b>合成栏和产物格是<b>真实状态</b>（{@code craftMatrix}
 * 两端都有、由容器自己同步），所以它们完全交给原版处理 —— 包括产物格
 * Shift 连续合成所依赖的 {@code retrySlotClick} 循环。硬把它们塞进那条
 * 「单一改动路径」反而会把原版的合成逻辑拆坏。
 *
 * <p>
 * 代价是客户端对存入不做预测：Shift 点击后物品要等一个 tick（约 50ms）才消失。
 * 这点延迟换来的是「永远不会出现客户端有、服务端没有的幽灵物品」。
 */
public class ContainerSharedTerminal extends Container {

    // ---- 网格 ----
    public static final int COLS = 9;
    public static final int ROWS = 5;
    public static final int SHARED_SLOTS = COLS * ROWS;

    // ---- 槽位分段 ----
    public static final int MAIN_START = SHARED_SLOTS;
    public static final int MAIN_END = MAIN_START + InventoryExchange.INV_SIZE;
    public static final int ARMOR_START = MAIN_END;
    public static final int ARMOR_END = ARMOR_START + InventoryExchange.ARMOR_SIZE;
    public static final int CRAFT_START = ARMOR_END;
    public static final int CRAFT_END = CRAFT_START + 4;
    public static final int RESULT_SLOT = CRAFT_END;
    public static final int TOTAL_SLOTS = RESULT_SLOT + 1;

    // ---- GUI 布局（容器和 GUI 共用同一组常量，保证格子画在哪就点在哪） ----
    //
    // 左区（沿用原布局，一点没动）
    // y= 4..20 搜索框
    // y= 22..36 页签（物品 / 流体）
    // y= 38..128 共享存储网格（9 列 × 5 行）
    // y=130..144 翻页 / 排序
    // y=146..160 存入按钮
    // y=162..170 状态行
    // y=172..226 玩家背包主区
    // y=230..248 快捷栏
    //
    // 右区（侧栏）
    // y= 26 「装备」标题
    // y= 38..110 护甲 4 格
    // y=118 「合成」标题
    // y=132..168 合成栏 2×2
    // y=172..184 箭头（画在贴图里）
    // y=186..204 产物格
    //
    // 只加宽不加高：GUI scale 4 时竖向只有 270 像素可用，再高就有玩家看不到底部了。
    public static final int GUI_WIDTH = 232;
    public static final int GUI_HEIGHT = 252;

    public static final int GRID_X = 7;
    public static final int GRID_Y = 38;

    public static final int PLAYER_X = 7;
    public static final int PLAYER_Y = 172;
    public static final int HOTBAR_Y = 230;

    public static final int SEARCH_Y = 7;
    public static final int TAB_Y = 22;
    public static final int NAV_Y = 130;
    public static final int BUTTON_Y = 146;
    public static final int STATUS_Y = 162;

    public static final int SIDEBAR_X = 176;
    public static final int ARMOR_LABEL_Y = 26;
    public static final int ARMOR_X = 191;
    public static final int ARMOR_Y = 38;
    public static final int CRAFT_LABEL_Y = 118;
    public static final int CRAFT_X = 182;
    public static final int CRAFT_Y = 132;
    public static final int RESULT_X = 191;
    public static final int RESULT_Y = 186;

    private final EntityPlayer player;
    /** 由方块终端打开时指向那个方块；按键远程打开时为 null。 */
    private final TileEntitySharedTerminal terminal;
    private final GhostInventory ghost;

    /**
     * 当前页每一格对应的<b>原始的键</b>，和 {@link #ghost} 一一对应。
     *
     * <p>
     * <b>为什么不能从显示物品反推：</b>原来点击时用的是
     * {@code ItemKey.of(ghost.getDisplay(i))}，前提是「显示物品就是
     * {@code key.prototype()} 造出来的，反推无损」。这个前提<b>在别的模组会改写
     * 物品栈时不成立</b> —— GT 的 {@code MetaGeneratedTool.getToolStats()} 就是个
     * 有副作用的 getter：它内部调 {@code isItemStackUsable}，那个方法会
     * {@code removeTag("ench")} 并用 {@code EnchantmentHelper.setEnchantments}
     * 重写附魔表。而 {@code getToolStats} 在渲染和 tooltip 里都会被调到，
     * 于是<b>光是把界面画出来，显示栈的 NBT 就已经和存档里的不一样了</b>，
     * 反推出的键自然查不到东西，表现就是「点了没反应」。
     *
     * <p>
     * 所以键必须<b>自己带着走</b>，而不是事后从可能已经被改过的物品栈上反推。
     */
    private final ItemKey[] pageItemKeys = new ItemKey[SHARED_SLOTS];
    private final FluidKey[] pageFluidKeys = new FluidKey[SHARED_SLOTS];

    /**
     * 当前是不是在流体页签。
     *
     * <p>
     * 这个状态属于界面，但它决定了「光标上拿着装流体的容器时点一下该做什么」，
     * 而那个判断在 {@link #slotClick} 里，所以由 GUI 在切页签时告知容器。
     * 同一时刻只可能开着一个终端界面，不需要做成每个界面独立。
     */
    private boolean fluidTabActive;

    /**
     * 2×2 合成栏。<b>它属于容器，不属于玩家</b> —— 这一点和很多人的直觉相反。
     * 原版 {@code ContainerPlayer} 也是这么做的，所以关掉原版背包界面时
     * 里面的东西会被丢到地上。
     */
    private final InventoryCrafting craftMatrix = new InventoryCrafting(this, 2, 2);
    private final IInventory craftResult = new InventoryCraftResult();

    public ContainerSharedTerminal(InventoryPlayer playerInventory, TileEntitySharedTerminal terminal) {
        this.player = playerInventory.player;
        this.terminal = terminal;
        this.ghost = new GhostInventory(SHARED_SLOTS, this);

        // 共享存储网格。必须是<b>最先</b>加进去的，下标才能和 SHARED_SLOTS 对上。
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = row * COLS + col;
                addSlotToContainer(
                    new SlotSharedStorage(ghost, this, index, GRID_X + 1 + col * 18, GRID_Y + 1 + row * 18));
            }
        }

        // 玩家背包主区（mainInventory 的 9..35）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(
                    new Slot(playerInventory, 9 + row * 9 + col, PLAYER_X + 1 + col * 18, PLAYER_Y + 1 + row * 18));
            }
        }

        // 快捷栏（mainInventory 的 0..8）
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(playerInventory, col, PLAYER_X + 1 + col * 18, HOTBAR_Y + 1));
        }

        addArmorSlots(playerInventory);
        addCraftingSlots(playerInventory);

        onCraftMatrixChanged(craftMatrix);
    }

    /**
     * 护甲 4 格。
     *
     * <p>
     * 索引用 {@code getSizeInventory() - 1 - i}，也就是 39、38、37、36 ——
     * {@link InventoryPlayer#getStackInSlot(int)} 在 {@code >= 36} 时会自动落到
     * {@code armorInventory}，于是从上到下正好是头盔、胸甲、护腿、靴子。
     */
    private void addArmorSlots(InventoryPlayer playerInventory) {
        final EntityPlayer owner = playerInventory.player;
        int topIndex = playerInventory.getSizeInventory() - 1;

        for (int i = 0; i < InventoryExchange.ARMOR_SIZE; i++) {
            final int armorType = i;
            addSlotToContainer(new Slot(playerInventory, topIndex - i, ARMOR_X + 1, ARMOR_Y + 1 + i * 18) {

                /** 护甲一格只能放一件，否则玩家能把 64 顶头盔塞进头盔位。 */
                @Override
                public int getSlotStackLimit() {
                    return 1;
                }

                /** 只收对应部位的护甲。用原版判定，模组护甲也能正确识别。 */
                @Override
                public boolean isItemValid(ItemStack stack) {
                    if (stack == null || stack.getItem() == null) return false;
                    return stack.getItem()
                        .isValidArmor(stack, armorType, owner);
                }

                /**
                 * 空格子时画上对应部位的护甲剪影，和原版背包界面一致。
                 *
                 * <p>
                 * 侧栏那 4 格如果一点图标都没有，看起来和存储格一模一样，
                 * 玩家会分不清哪边是仓库、哪边是自己身上穿的。
                 *
                 * <p>
                 * {@code func_94602_b} 就是原版 {@code ContainerPlayer} 用的那个方法
                 * （MCP 没给它起名字，所以是 SRG 名）。它标了 {@code @SideOnly(CLIENT)}，
                 * 但只会被 GUI 渲染调用，服务端永远执行不到。
                 */
                @SideOnly(Side.CLIENT)
                @Override
                public net.minecraft.util.IIcon getBackgroundIconIndex() {
                    return ItemArmor.func_94602_b(armorType);
                }
            });
        }
    }

    private void addCraftingSlots(InventoryPlayer playerInventory) {
        // 产物格必须用 SlotCrafting：取出产物时消耗合成材料的逻辑全在它里面
        addSlotToContainer(
            new SlotCrafting(playerInventory.player, craftMatrix, craftResult, 0, RESULT_X + 1, RESULT_Y + 1));

        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                addSlotToContainer(
                    new Slot(craftMatrix, col + row * 2, CRAFT_X + 1 + col * 18, CRAFT_Y + 1 + row * 18));
            }
        }
    }

    public TileEntitySharedTerminal getTerminal() {
        return terminal;
    }

    public GhostInventory getGhostInventory() {
        return ghost;
    }

    public IInventory getCraftMatrix() {
        return craftMatrix;
    }

    public boolean isRemoteAccess() {
        return terminal == null;
    }

    /** 由 GUI 在切换页签时调用。见 {@link #fluidTabActive}。 */
    public void setFluidTabActive(boolean active) {
        this.fluidTabActive = active;
    }

    // ==================================================================
    // 合成
    // ==================================================================

    /** 合成栏内容一变就重算产物。两端都会跑，{@code findMatchingRecipe} 是确定性的。 */
    @Override
    public void onCraftMatrixChanged(IInventory inventory) {
        craftResult.setInventorySlotContents(
            0,
            CraftingManager.getInstance()
                .findMatchingRecipe(craftMatrix, player.worldObj));
    }

    /**
     * 关界面时把合成栏里的东西退回背包，<b>实在放不下才丢地上</b>。
     *
     * <p>
     * 原版 {@code ContainerPlayer} 是无条件丢地上的。玩家只是关个界面，
     * 东西不应该被扔出来 —— 既然这个容器是我们自己的，就顺手做得好一点。
     */
    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);

        boolean serverSide = player.worldObj != null && !player.worldObj.isRemote;
        for (int i = 0; i < craftMatrix.getSizeInventory(); i++) {
            ItemStack stack = craftMatrix.getStackInSlotOnClosing(i);
            if (stack == null) continue;

            if (serverSide && !player.inventory.addItemStackToInventory(stack)) {
                player.dropPlayerItemWithRandomChoice(stack, false);
            }
        }
        craftResult.setInventorySlotContents(0, null);
    }

    /** 产物格不参与原版的「双击收集」，和原版 {@code ContainerPlayer} 保持一致。 */
    @Override
    public boolean func_94530_a(ItemStack stack, Slot slot) {
        return slot.inventory != craftResult && super.func_94530_a(stack, slot);
    }

    // ==================================================================
    // 客户端：把当前页铺进虚拟槽位
    // ==================================================================

    /**
     * 客户端专用：设置当前页每一格显示的物品。
     *
     * <p>
     * 传进来的显示物品同时充当「这一格对应存储里哪个条目」的索引 ——
     * 因为显示物品就是用条目的 {@code ItemKey.prototype(数量)} 造出来的，
     * 反推 {@link ItemKey} / {@link FluidKey} 是无损的，不需要另维护一张下标映射表，
     * 也就不存在「表和显示不同步」这类 bug。
     */
    public void setPageDisplay(List<ItemStack> page, List<ItemKey> itemKeys, List<FluidKey> fluidKeys) {
        ghost.clearDisplay();
        Arrays.fill(pageItemKeys, null);
        Arrays.fill(pageFluidKeys, null);

        int limit = Math.min(page.size(), SHARED_SLOTS);
        for (int i = 0; i < limit; i++) {
            ghost.setDisplay(i, page.get(i));
            if (itemKeys != null && i < itemKeys.size()) pageItemKeys[i] = itemKeys.get(i);
            if (fluidKeys != null && i < fluidKeys.size()) pageFluidKeys[i] = fluidKeys.get(i);
        }
    }

    public void clearPageDisplay() {
        ghost.clearDisplay();
        Arrays.fill(pageItemKeys, null);
        Arrays.fill(pageFluidKeys, null);
    }

    // ==================================================================
    // 原版点击分发
    // ==================================================================

    @Override
    public ItemStack slotClick(int slotId, int mouseButton, int mode, EntityPlayer player) {
        // --- 共享存储网格：两端都只返回 null，真实改动等服务端处理请求包 ---
        if (slotId >= 0 && slotId < SHARED_SLOTS) {
            if (isClient(player)) {
                handleSharedSlotClick(slotId, mouseButton, mode);
            }
            return null;
        }

        // --- 玩家主背包 / 护甲 / 合成栏：Shift 点击 = 存入共享存储 ---
        if (isStorableSlot(slotId)) {
            if (mode == 1) {
                if (isClient(player)) {
                    sendDepositFromSlot(slotId, 0L);
                }
                return null;
            }
            if (mode == 6 && player.inventory.getItemStack() != null) {
                // 原版「双击收集」会遍历所有槽位（含共享槽位）并把光标堆叠数直接改大，
                // 客户端于是会多出一个服务端并不存在的幽灵物品。这里直接不处理，
                // 服务端下一个 tick 会把光标状态纠正回来。
                return null;
            }
            return super.slotClick(slotId, mouseButton, mode, player);
        }

        // --- 产物格及其它：完全交给原版（含 Shift 连续合成） ---
        return super.slotClick(slotId, mouseButton, mode, player);
    }

    /**
     * @return 这一格的东西能否被「Shift 点击存入共享存储」。
     *         不含产物格 —— 那是合成结果，该进背包而不是直接进仓库。
     */
    private boolean isStorableSlot(int slotId) {
        return slotId >= MAIN_START && slotId < CRAFT_END;
    }

    /**
     * 别的模组（NEI 等）直接调用时的入口，语义和原版一致：
     * 「把这一格的东西挪到对面去」。
     *
     * <p>
     * 基类实现是 {@code return slot.getStack()}，而 {@code slotClick} 的 Shift 分支
     * 在拿到非 null 返回值后会调 {@code retrySlotClick} 再走一遍 Shift 分支 ——
     * 基类那样写会无限递归（这正是原版注释里说「必须覆写，否则玩家 Shift 点击会崩」
     * 的原因）。
     *
     * <p>
     * 产物格是例外，见 {@link #transferCraftResult}。
     */
    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (index == RESULT_SLOT) {
            // 合成产物是真实状态，走原版逻辑、两端一致。
            // 原版 slotClick 的 Shift 分支会靠返回值反复重试，从而实现「一直合成到材料用完」。
            return transferCraftResult(player);
        }

        if (!isClient(player)) return null;

        if (index >= 0 && index < SHARED_SLOTS) {
            withdrawFromDisplay(index, 0L);
        } else if (isStorableSlot(index)) {
            sendDepositFromSlot(index, 0L);
        }
        return null;
    }

    /**
     * Shift 点击产物格：把合成结果挪进玩家背包。
     *
     * <p>
     * 照抄原版 {@code ContainerPlayer} 产物格那一支的写法，因为这里的时序很讲究：
     * {@code mergeItemStack} 会把传进去的栈（也就是产物格里那个对象本身）的
     * {@code stackSize} 减到 0，之后必须据此清空槽位，再调 {@code onPickupFromSlot} ——
     * 而 {@code SlotCrafting} 正是在那一步消耗合成材料。顺序反了就会出现
     * 「材料扣了但产物没拿到」或者「产物拿到但材料没扣」。
     *
     * <p>
     * 可见性是 public：服务端的自动合成（{@code CraftFiller}，NEI 联动）在
     * 填好合成栏之后也走这一个方法把产物收进背包 —— 和玩家 Shift 点击产物格
     * 走的是同一条路径，包括 {@link #canAcceptAll} 那个防蒸发的判断。
     *
     * @return 被挪走的产物；一点都没挪动时返回 null（自动合成循环靠这个决定停不停）
     */
    public ItemStack transferCraftResult(EntityPlayer player) {
        Slot slot = getSlot(RESULT_SLOT);
        if (slot == null || !slot.getHasStack()) return null;

        ItemStack before = slot.getStack()
            .copy();
        ItemStack live = slot.getStack();

        // 原版在这里有个丢东西的口子：只要背包还能塞下产物的<b>一部分</b>，
        // mergeItemStack 就会返回 true，紧接着 onPickupFromSlot 会把整份材料扣掉，
        // 而没塞进去的那部分产物会在下一次 onCraftMatrixChanged 里被重算成
        // 「合成栏已空 → 没有产物」而蒸发。
        // 这里先确认整份产物都放得下，放不下就干脆不合成 ——
        // 玩家看到的应该是「背包满了」，而不是莫名其妙少了几个东西。
        if (!canAcceptAll(live)) return null;

        if (!mergeItemStack(live, MAIN_START, MAIN_END, true)) return null;

        if (live.stackSize <= 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }

        if (live.stackSize == before.stackSize) return null;

        slot.onPickupFromSlot(player, live);
        return before;
    }

    /**
     * @return 玩家主背包是否装得下 {@code stack} 的全部数量。
     *
     *         <p>
     *         只做容量判断，不看顺序 —— {@code mergeItemStack} 是从后往前填的，
     *         但「总共装得下多少」和填充顺序无关。
     */
    private boolean canAcceptAll(ItemStack stack) {
        if (stack == null) return false;

        int remaining = stack.stackSize;
        for (int i = MAIN_START; i < MAIN_END && remaining > 0; i++) {
            Slot slot = getSlot(i);
            if (slot == null) continue;

            int limit = Math.min(stack.getMaxStackSize(), slot.getSlotStackLimit());
            if (limit <= 0) continue;

            ItemStack existing = slot.getStack();
            if (existing == null) {
                remaining -= limit;
                continue;
            }

            // 只有和产物完全同种（含 NBT）的堆叠才能叠上去
            if (existing.getItem() != stack.getItem() || existing.getItemDamage() != stack.getItemDamage()
                || !ItemStack.areItemStackTagsEqual(existing, stack)) {
                continue;
            }
            remaining -= Math.max(0, Math.min(remaining, limit - existing.stackSize));
        }
        return remaining <= 0;
    }

    /** 共享存储网格不允许原版拖拽逻辑直接往里塞 —— 见类注释里的单一路径原则。 */
    @Override
    public boolean canDragIntoSlot(Slot slot) {
        return !(slot instanceof SlotSharedStorage);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (terminal == null) return true;
        return terminal.isUseableByPlayer(player);
    }

    // ==================================================================
    // 客户端：把点击翻译成请求包
    // ==================================================================

    /**
     * @param mouseButton 0=左键, 1=右键, 2=中键
     * @param mode        0=普通, 1=Shift, 3=中键, 5=拖拽经过
     */
    private void handleSharedSlotClick(int viewIndex, int mouseButton, int mode) {
        // 只认真正的「鼠标点击」。slotClick 的 mode 参数是复用的：
        // 2 = 数字键与快捷栏交换（mouseButton 是快捷栏下标 0..8，不是鼠标键）
        // 4 = Q 键丢弃（mouseButton 是 0/1）
        // 6 = 双击收集
        // 不把这三个挡掉的话，对着网格按一下数字键 3 就会取出 64 个，
        // 按一下 Q 就会取出 1 个 —— 玩家根本没点这一格。
        if (mode != 0 && mode != 1 && mode != 3 && mode != 5) return;

        boolean shift = mode == 1;
        boolean dragging = mode == 5;

        ItemStack cursor = player.inventory.getItemStack();

        // 光标上拿着东西 => 存入
        if (cursor != null) {
            // 流体页签里，光标上拿着「装着流体的容器」时，这一下点击的含义是
            // 「把里面的流体倒进去」，而不是「把这个容器当成物品收走」。
            //
            // 之所以要按页签分流：同一个牛奶桶，在物品页签里它就是一个物品，
            // 该被当物品存起来；在流体页签里它承载的是流体，该被倒空。
            // 玩家的意图由他正在看哪一栏表达。
            if (fluidTabActive && FluidContainerHelper.getFluid(cursor) != null) {
                NetworkHandler.INSTANCE.sendToServer(new PacketStorageAction(PacketStorageAction.DRAIN_CURSOR));
                predictCursorDrain(cursor);
                return;
            }

            long amount;
            if (dragging) {
                // 原版不会给共享格派发 mode 5（isItemValid 和 canDragIntoSlot 两头都挡着），
                // 这里只是万一有别的模组直接发 mode 5 时的兜底语义
                amount = 1L;
            } else if (mouseButton == 0) {
                amount = 0L; // 左键：整叠
            } else {
                amount = 1L; // 右键：1 个
            }
            sendDepositCursor(amount);
            predictCursorDeposit(cursor, amount);
            return;
        }

        ItemStack display = ghost.getDisplay(viewIndex);
        if (display == null) return;

        // ---- 流体条目（GT 的流体显示物品） ----
        // 键优先用带过来的那份，反推只作为兜底 —— 见 pageItemKeys 的说明
        FluidKey fluidKey = viewIndex < pageFluidKeys.length ? pageFluidKeys[viewIndex] : null;
        FluidStack shown = GTUtility.getFluidFromDisplayStack(display);
        if (fluidKey == null && shown != null && shown.getFluid() != null && shown.amount > 0) {
            fluidKey = FluidKey.of(shown);
        }
        if (fluidKey != null) {
            long amount = shift ? -1L : Config.fluidClickAmount;

            if (mouseButton == 2) {
                // 中键：把这个流体设为方块终端的输出（远程打开的界面没有终端，忽略）
                if (terminal != null) {
                    NetworkHandler.INSTANCE
                        .sendToServer(PacketStorageAction.fluid(PacketStorageAction.SET_TERMINAL_FLUID, fluidKey, 0L));
                }
                return;
            }
            if (mouseButton == 1) {
                // 右键：取 GT 流体显示物品
                NetworkHandler.INSTANCE
                    .sendToServer(PacketStorageAction.fluid(PacketStorageAction.TAKE_FLUID_DISPLAY, fluidKey, amount));
                return;
            }
            // 左键：灌装背包里的容器
            NetworkHandler.INSTANCE
                .sendToServer(PacketStorageAction.fluid(PacketStorageAction.FILL_CONTAINER, fluidKey, amount));
            return;
        }

        // ---- 普通物品 ----
        // 同上：用带过来的键，而不是从显示栈反推。
        // 反推在「模组的 getter 会改写物品栈」时会失灵（GT 工具就是），
        // 那种情况下的症状是点了完全没反应。
        ItemKey itemKey = viewIndex < pageItemKeys.length ? pageItemKeys[viewIndex] : null;
        if (itemKey == null) itemKey = ItemKey.of(display);
        if (itemKey == null) return;

        long amount;
        if (dragging) {
            amount = 1L;
        } else if (mouseButton == 2) {
            amount = Math.max(1, display.getMaxStackSize());
        } else if (shift) {
            amount = mouseButton == 1 ? -1L : Config.shiftClickWithdrawAmount;
        } else if (mouseButton == 1) {
            amount = Math.max(1, display.getMaxStackSize() / 2);
        } else {
            amount = 1L;
        }

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_ITEM, itemKey, amount));
    }

    /** 供 {@link GhostInventory} / {@link SlotSharedStorage} 在别的模组直接操作槽位时调用。 */
    public void requestWithdrawFromDisplay(int viewIndex, int amount) {
        if (isClient(player)) {
            withdrawFromDisplay(viewIndex, amount);
        }
    }

    public void requestDepositFromExternal(ItemStack stack) {
        if (!isClient(player) || stack == null) return;
        ItemKey key = ItemKey.of(stack);
        if (key == null) return;
        // 语义是「从玩家背包里扣除这么多个再存进去」，而不是「凭空存这么多」。
        // 数量由服务端按背包实际内容封顶，所以外部模组无论传什么都不可能刷物品。
        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.DEPOSIT_MATCHING, key, stack.stackSize));
    }

    private void withdrawFromDisplay(int viewIndex, long amount) {
        ItemStack display = ghost.getDisplay(viewIndex);
        if (display == null) return;

        long requested = Math.max(amount, 0L);

        FluidKey fluidKey = viewIndex < pageFluidKeys.length ? pageFluidKeys[viewIndex] : null;
        if (fluidKey != null) {
            NetworkHandler.INSTANCE
                .sendToServer(PacketStorageAction.fluid(PacketStorageAction.FILL_CONTAINER, fluidKey, requested));
            return;
        }

        ItemKey key = viewIndex < pageItemKeys.length ? pageItemKeys[viewIndex] : null;
        if (key == null) key = ItemKey.of(display);
        if (key == null) return;
        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_ITEM, key, requested));
    }

    /**
     * 把容器槽位翻译成一个「存入请求」。三种来源分别走不同的服务端路径：
     *
     * <ul>
     * <li>玩家主背包 / 护甲 —— 索引 0..39，交给 {@code InventoryExchange.depositSlot}；</li>
     * <li>合成栏 —— <b>索引不在这套编号里</b>，因为它的内容属于容器而不是玩家，
     * 所以单独一个动作，让服务端去操作自己那个 {@code craftMatrix}。</li>
     * </ul>
     */
    private void sendDepositFromSlot(int containerSlot, long amount) {
        int playerIndex = toPlayerSlotIndex(containerSlot);
        if (playerIndex >= 0) {
            NetworkHandler.INSTANCE.sendToServer(
                PacketStorageAction.slot(PacketStorageAction.DEPOSIT_INV_SLOT, playerIndex)
                    .withAmount(amount));
            return;
        }

        if (containerSlot >= CRAFT_START && containerSlot < CRAFT_END) {
            NetworkHandler.INSTANCE.sendToServer(
                PacketStorageAction.slot(PacketStorageAction.DEPOSIT_CRAFT_SLOT, containerSlot - CRAFT_START)
                    .withAmount(amount));
        }
    }

    private void sendDepositCursor(long amount) {
        NetworkHandler.INSTANCE
            .sendToServer(new PacketStorageAction(PacketStorageAction.DEPOSIT_CURSOR).withAmount(amount));
    }

    /**
     * 把容器槽位下标换算成 {@link InventoryPlayer} 的下标。
     *
     * <p>
     * <b>这两个下标不是一回事，别直接相减。</b>容器里的顺序是
     * 「共享网格(0..44) → 背包主区(45..71，对应 mainInventory 9..35)
     * → 快捷栏(72..80，对应 mainInventory 0..8) → 护甲(81..84)」，
     * 所以容器 45 号槽对应的是 <b>mainInventory[9]</b> 而不是 [0]。
     * 靠 {@code slotId - SHARED_SLOTS} 去算会安安静静地存错格子。
     *
     * <p>
     * {@link Slot#getSlotIndex()} 返回的正是构造 {@code Slot} 时传进去的
     * inventory 下标，是唯一可靠的换算法。
     *
     * @return 0..39；不是玩家槽位（合成栏 / 产物格 / 别的模组注入的槽位）时返回 -1
     */
    private int toPlayerSlotIndex(int containerSlot) {
        if (containerSlot < 0 || containerSlot >= inventorySlots.size()) return -1;
        Slot slot = getSlot(containerSlot);
        if (slot == null) return -1;

        // 还要确认这个槽位真的挂着玩家的背包。万一有别的模组往这个容器里
        // 注入自己的槽位，光看下标范围会把它的 slotIndex 当成玩家背包下标，
        // 结果就是「点了 A 格，存了 B 格」。
        if (slot.inventory != player.inventory) return -1;

        int index = slot.getSlotIndex();
        return index >= 0 && index < InventoryExchange.PLAYER_SLOT_COUNT ? index : -1;
    }

    /**
     * 客户端本地预测「光标上这叠被存走之后」的样子。
     *
     * <p>
     * 存入是本模组唯一可以安全预测的操作：共享存储对物品来者不拒，
     * 不存在「服务端拒绝」的正常路径（只有存储已经顶到 {@code long} 上限这种理论情况）。
     * 不预测的话，服务端把光标清空了、客户端还举着那叠东西，
     * 看起来像卡了个幽灵物品 —— 要等你再点一下背包里别的格子才会被服务端纠正回来。
     *
     * <p>
     * 万一真的出现服务端拒绝（例如流体没有注册名字、无法建键），
     * 客户端这次预测就是错的，但下一个点击包会让原版走
     * {@code processClickWindow} 的不一致分支重发整个容器（含光标），自动纠正。
     */
    private void predictCursorDeposit(ItemStack cursor, long amount) {
        InventoryPlayer inventory = player.inventory;

        if (Config.displayItemBecomesFluid) {
            FluidStack shown = GTUtility.getFluidFromDisplayStack(cursor);
            if (shown != null && shown.amount > 0) {
                // 只有确认服务端也能建出键时才预测，否则宁可不预测
                if (FluidKey.of(shown) != null) {
                    inventory.setItemStack(null);
                }
                return;
            }
        }

        int take = amount <= 0L ? cursor.stackSize : (int) Math.min(amount, cursor.stackSize);
        if (take <= 0) return;

        cursor.stackSize -= take;
        if (cursor.stackSize <= 0) {
            inventory.setItemStack(null);
        }
    }

    /**
     * 客户端本地预测「光标上那个容器被倒空之后」的样子：把光标换成空容器。
     *
     * <p>
     * {@link FluidContainerHelper#drain} 内部是先复制再操作的，所以拿客户端这个
     * 真实的容器去算不会把它改坏 —— 不复制的话，这里一算就等于客户端自己先把桶倒空了，
     * 而服务端那边还没收到请求，两边立刻就不一致。
     *
     * <p>
     * 预测失败（服务端拒绝、包丢了）也无所谓：下一个点击包会让原版走
     * {@code processClickWindow} 的不一致分支把光标重发一遍，自动纠正。
     */
    private void predictCursorDrain(ItemStack cursor) {
        FluidContainerHelper.DrainResult result = FluidContainerHelper.drain(cursor);
        if (result == null || result.fluid == null || result.fluid.amount <= 0) return;

        ItemStack emptied = result.container;
        emptied.stackSize = cursor.stackSize;
        player.inventory.setItemStack(emptied);
    }

    private static boolean isClient(EntityPlayer player) {
        return player != null && player.worldObj != null && player.worldObj.isRemote;
    }

    // ==================================================================
    // 供 GUI 按钮调用的动作
    // ==================================================================

    public void sendDepositAll(int scope) {
        NetworkHandler.INSTANCE.sendToServer(PacketStorageAction.scope(PacketStorageAction.DEPOSIT_ALL, scope));
    }

    public void sendDrainContainers() {
        NetworkHandler.INSTANCE.sendToServer(new PacketStorageAction(PacketStorageAction.DRAIN_CONTAINERS));
    }
}
