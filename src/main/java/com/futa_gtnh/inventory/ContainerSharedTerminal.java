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
import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.exchange.FluidContainerHelper;
import com.futa_gtnh.exchange.InventoryExchange;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

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
 *   85 .. 93  合成栏 3×3
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
    /** 合成栏是 3×3。 */
    public static final int CRAFT_SIZE = 3;
    public static final int CRAFT_SLOTS = CRAFT_SIZE * CRAFT_SIZE;
    public static final int CRAFT_END = CRAFT_START + CRAFT_SLOTS;
    public static final int RESULT_SLOT = CRAFT_END;

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
    // y=132..186 合成栏 3×3
    // y=188..196 箭头（画在贴图里）
    // y=204..222 产物格
    //
    // 3×3 的横向：侧栏内沿是 x=178..230（x=176 是分隔线、177 是高光、231 是右边框），
    // 只有 53px，装不下 3×18=54 的格子，所以合成栏从 x=177 起 —— 正好覆盖掉那条
    // 1px 高光，格子的浅色边框顶上去，看上去就是贴着侧栏的一整块（贴图已按这个位置重画）。
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
    /**
     * 「合成」小标题的 y。
     *
     * <p>
     * 原来在 118，现在上移到 110 —— 118 那一行腾给「返还原料」按钮（见
     * {@link #CRAFT_DUMP_Y}）。护甲最后一格画到 y=110，110 正好是它下一个像素，
     * 标题（8px 高）到 118 结束，按钮紧接着从 118 开始，一行都没浪费。
     */
    public static final int CRAFT_LABEL_Y = 110;
    /** 侧栏「返还原料」按钮的 y（合成栏正上方，14px 高，下沿正好贴住合成栏）。 */
    public static final int CRAFT_DUMP_Y = 118;
    public static final int CRAFT_X = 177;
    public static final int CRAFT_Y = 132;
    public static final int RESULT_X = 191;
    public static final int RESULT_Y = 204;

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
     * 3×3 合成栏。<b>它属于容器，不属于玩家</b> —— 这一点和很多人的直觉相反。
     * 原版 {@code ContainerPlayer} 也是这么做的，所以关掉原版背包界面时
     * 里面的东西会被丢到地上。
     */
    private final InventoryCrafting craftMatrix = new InventoryCrafting(this, CRAFT_SIZE, CRAFT_SIZE);
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
        // 3×3 <b>必须先加</b>：槽位下标就是加入顺序，而常量表写的是
        // {@code CRAFT_START..CRAFT_END}（85..93）= 合成栏、{@code RESULT_SLOT}（94）= 产物格。
        //
        // 以前这里把产物格放在最前面，于是它的真实下标是 85、合成栏占了 86..94。
        // 全套按常量做的判断就都错开一格：
        // <ul>
        // <li>{@code isStorableSlot} 认为 85 是合成栏 → <b>Shift 点产物格被当成
        // 「把合成栏第 0 格存进共享存储」</b>：材料被搬进仓库、产物什么都不发生
        // ——玩家报的「Shift 点产物，材料直接消失」就是这么来的；</li>
        // <li>{@code getSlot(RESULT_SLOT)} 拿到的是合成栏右下角那一格 → 取产物永远
        // 「产物格是空的」，NEI 的自动合成一次也做不成；</li>
        // <li>合成栏存入的 {@code DEPOSIT_CRAFT_SLOT} 下标也跟着偏 1（86 → 1）。</li>
        // </ul>
        // 所以顺序不能凭手感写：它必须和常量表一致，产物格放在最后。
        for (int row = 0; row < CRAFT_SIZE; row++) {
            for (int col = 0; col < CRAFT_SIZE; col++) {
                addSlotToContainer(
                    new Slot(craftMatrix, col + row * CRAFT_SIZE, CRAFT_X + 1 + col * 18, CRAFT_Y + 1 + row * 18));
            }
        }

        // 产物格必须用 SlotCrafting：取出产物时消耗合成材料的逻辑全在它里面
        addSlotToContainer(
            new SlotCrafting(playerInventory.player, craftMatrix, craftResult, 0, RESULT_X + 1, RESULT_Y + 1));
    }

    public TileEntitySharedTerminal getTerminal() {
        return terminal;
    }

    public GhostInventory getGhostInventory() {
        return ghost;
    }

    /**
     * 把当前 3×3 的内容打包成 NEI 那套布局 NBT（{@code {slots:[{idx,count,cands:[ItemKey]}]}}）。
     *
     * <p>
     * Shift+点击产物要「批量合成」时用它：那一瞬间格子里的东西就是配方本身。
     * 必须<b>在结算之前</b>取 —— 原版结算会把格子清空，之后就拿不到了。
     *
     * <p>
     * 每格只放一个候选（就是格子里那件物品），{@code count} 取该格的堆叠数。
     * {@code count} 在这里只是「这一格该填多少」的倍率底数，不是消耗量
     * （消耗量由配方本身决定，原版 {@code SlotCrafting} 每合成一次每格扣 1），
     * 所以取堆叠数正好等于「填满这一格，能做多少次就做多少次」。
     *
     * <p>
     * 多带一个 {@code keep = true}：告诉服务端<b>别动合成栏里现有的摆法</b>。
     * NEI 那条「照配方填栏」要先把格子倒空再重填，而这个布局本来就是从格子里
     * 读出来的，倒空它等于自己把自己抹掉（详见 {@code CraftFiller} 里那段注释）。
     */
    public net.minecraft.nbt.NBTTagCompound layoutFromCraftMatrix() {
        net.minecraft.nbt.NBTTagList slots = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < CRAFT_SLOTS; i++) {
            ItemStack stack = craftMatrix.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0) continue;

            com.futa_gtnh.shared.ItemKey key = com.futa_gtnh.shared.ItemKey.of(stack);
            if (key == null) continue;

            net.minecraft.nbt.NBTTagCompound slot = new net.minecraft.nbt.NBTTagCompound();
            slot.setInteger("idx", i);
            slot.setInteger("count", Math.max(1, Math.min(stack.stackSize, 64)));
            net.minecraft.nbt.NBTTagList cands = new net.minecraft.nbt.NBTTagList();
            cands.appendTag(key.writeToNbt());
            slot.setTag("cands", cands);
            slots.appendTag(slot);
        }
        if (slots.tagCount() == 0) return null;

        net.minecraft.nbt.NBTTagCompound root = new net.minecraft.nbt.NBTTagCompound();
        root.setTag("slots", slots);
        root.setBoolean("keep", true);
        return root;
    }

    public IInventory getCraftMatrix() {
        return craftMatrix;
    }

    /**
     * Shift 点产物格：把「现在这个摆法」发给服务端，让它一直合成到
     * <b>合成栏里现有的原料用完为止</b>，做完再把格子补回原样。
     *
     * <p>
     * <b>一次点击最多消耗掉你摆进格子的那些</b>（不中途补料、也不动你的摆法），
     * 做完整批之后如果原料够，再按原样把料补回合成栏 —— 下一次 Shift 点就能接着做。
     * 补的是「你刚才那个样子」而不是塞满一整叠，想一次多做点就自己多放些。
     *
     * <p>
     * <b>为什么不让原版那条 Shift 环路做。</b>原版 {@code slotClick} 的 Shift 分支
     * 只会在产物格还有同类产物时调 {@code retrySlotClick} 再点一次 —— 一次点击到底能做
     * 多少次、产物往哪收，全在客户端那一侧逐次跑；而我们要的是「一次点击、服务端跑完
     * 整批」，少掉几十次来回的窗口点击，也不会出现「客户端做了一半、服务端没跟上」。
     *
     * <p>
     * 所以 Shift 点产物格由界面层接管（见 {@code GuiSharedTerminal#handleCraftResultClick}），
     * 包一发出去，合成循环整个在服务端跑（{@code CraftFiller}），客户端连点都不用预测。
     *
     * @return 是否已经把请求发出去；合成栏是空的（没有配方可谈）时返回 false，
     *         那种情况下调用方应该把这次点击交回原版
     */
    public boolean requestCraftFromGrid() {
        net.minecraft.nbt.NBTTagCompound layout = layoutFromCraftMatrix();
        if (layout == null) return false;

        // 数量 0 = 「有多少做多少」；次数上限在服务端（CraftFiller.MAX_AUTOCRAFT），
        // 和别处一样：客户端只说意图，做多少由服务端按合成栏里实际的料封顶
        NetworkHandler.INSTANCE.sendToServer(PacketStorageAction.craft(PacketStorageAction.AUTOCRAFT, layout, 0L));
        return true;
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
            ghost.setDisplay(i, displayOnly(page.get(i)));
            if (itemKeys != null && i < itemKeys.size()) pageItemKeys[i] = itemKeys.get(i);
            if (fluidKeys != null && i < fluidKeys.size()) pageFluidKeys[i] = fluidKeys.get(i);
        }
    }

    /**
     * 把「交给渲染用」的那一份显示栈的数量压成 1。
     *
     * <p>
     * 物品条目本来就是 1（{@code ItemKey.prototype()} 造的就是 1 个），<b>流体条目不是</b>：
     * GT 的流体显示物品把「多少」也塞进了 {@code stackSize}（托盘里那一叠就是多少份）。
     * 而所有通用渲染器都会把大于 1 的数量当堆叠数画出来：
     * <ul>
     * <li>原版 {@code RenderItem.renderItemOverlayIntoGUI} 画一行；</li>
     * <li><b>NEI 更狠</b>：它把原版那行换成自己的大号数字
     * （{@code GuiContainerManager} 里对 {@code stackSize > 1} 的堆叠调
     * {@code ReadableNumberConverter.toWideReadableForm} + {@code drawBigStackSize}）。</li>
     * </ul>
     * 两种都会和我们自己画的真实数量叠在同一格右下角，看起来就是「两个数字重叠」。
     *
     * <p>
     * 数量信息一个都没丢：格子上的数字来自 {@code StorageViewEntry.getAmount()}，
     * 点击取料读的是 {@code pageItemKeys} / {@code pageFluidKeys}，
     * tooltip 也走 {@code StorageViewEntry} —— 没有任何一处看这个 {@code stackSize}。
     */
    private static ItemStack displayOnly(ItemStack stack) {
        if (stack == null || stack.stackSize <= 1) return stack;

        ItemStack single = stack.copy();
        single.stackSize = 1;
        return single;
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
            if (mode == 6) {
                if (isClient(player)) {
                    sendCollectToCursor();
                }
                // 原版 mode=6 会直接改写光标和槽位；共享存储没有可供两端同步的真实槽位，
                // 所以改成一个服务端权威的收集请求，避免双击时生成幽灵物品。
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
            //
            // 这里<b>必须返回 null</b>：transferCraftResult 自己的职责就是「把合成结果挪进玩家背包」
            // （见它的 javadoc，里面按原版 ContainerPlayer 那一支做了 mergeItemStack + onPickupFromSlot），
            // 东西已经搬完了。以前把产物当返回值再交出去一次，原版 Shift 分支会拿它当
            // 「没搬完的部分」继续处理 —— 结果就是产物被处理两遍，玩家看到的就是「Shift 点击后产物消失」。
            //
            // 真正没搬完的（背包满）留在产物格里，并且打一条日志：这类丢东西的问题不能静默。
            // 返回值语义（照 transferCraftResult 的实现）：成功合成时它返回<b>产物的一份副本</b>，
            // 原版 slotClick 的 Shift 分支靠这个非空返回值继续重试，
            // 从而实现「Shift 点击一次，一直合成到材料用完」—— 这是原版行为，不能用 null 替代。
            // （我一度改成返回 null 并把副本人为塞回产物格，那是错的：产物已经进背包了，
            // 再塞一份回产物格只会让它在 onCraftMatrixChanged 重算时被抹掉，看着就是「消失」。）
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
     * 把产物格里的那一份「真的」产物取出来；是幽灵（和合成栏对不上）就顺手修掉并返回 null。
     *
     * <p>
     * <b>为什么必须验。</b>原版 {@code InventoryCrafting.markDirty()} 是空实现，任何
     * 「直接改 stackSize 再 markDirty」的路径都不会重算产物，于是合成栏已经空了、
     * 产物格却还挂着旧的产物。那个幽灵点下去要么凭空造出东西（材料一格都没少），要么
     * 两侧点击结果对不上被服务端整包回滚（玩家看到的就是「Shift 点产物什么都没发生」）。
     * 所以取产物之前一律以合成栏为准重算一次。
     */
    private ItemStack liveCraftResult(EntityPlayer player) {
        Slot slot = getSlot(RESULT_SLOT);
        if (slot == null || !slot.getHasStack()) {
            // 这是批量合成的正常收尾出口（材料做完了，每轮循环都会走到一次），
            // 所以不能无脑打日志。只有「合成栏里明明还有料、产物格却是空的」才是异常。
            if (hasCraftMaterial()) warnCraftFailure(player, "产物格是空的（这个摆法合不出东西）");
            return null;
        }

        ItemStack live = slot.getStack();
        ItemStack expected = CraftingManager.getInstance()
            .findMatchingRecipe(craftMatrix, player.worldObj);
        if (!ItemStack.areItemStacksEqual(expected, live)) {
            slot.putStack(expected);
            warnCraftFailure(player, "产物格和合成栏对不上（幽灵产物 " + live + " → " + expected + "），已按合成栏重算");
            return null;
        }
        return live;
    }

    /**
     * 取一次产物，<b>放进玩家背包</b>（原版语义）。
     *
     * <p>
     * 照抄原版 {@code ContainerPlayer} 产物格那一支的写法，因为这里的时序很讲究：
     * {@code mergeItemStack} 会把传进去的栈（也就是产物格里那个对象本身）的
     * {@code stackSize} 减到 0，之后必须据此清空槽位，再调 {@code onPickupFromSlot} ——
     * 而 {@code SlotCrafting} 正是在那一步消耗合成材料。顺序反了就会出现
     * 「材料扣了但产物没拿到」或者「产物拿到但材料没扣」。
     *
     * @return 被挪走的产物；一点都没挪动时返回 null
     */
    public ItemStack transferCraftResult(EntityPlayer player) {
        Slot slot = getSlot(RESULT_SLOT);
        ItemStack live = liveCraftResult(player);
        if (live == null) return null;

        ItemStack before = live.copy();

        // 原版在这里有个丢东西的口子：只要背包还能塞下产物的<b>一部分</b>，
        // mergeItemStack 就会返回 true，紧接着 onPickupFromSlot 会把整份材料扣掉，
        // 而没塞进去的那部分产物会在下一次 onCraftMatrixChanged 里被重算成
        // 「合成栏已空 → 没有产物」而蒸发。
        // 这里先确认整份产物都放得下，放不下就干脆不合成 ——
        // 玩家看到的应该是「背包满了」，而不是莫名其妙少了几个东西。
        if (!canAcceptAll(live)) {
            warnCraftFailure(player, "背包放不下这 " + live.stackSize + " 个产物");
            return null;
        }

        if (!mergeItemStack(live, MAIN_START, MAIN_END, true)) {
            warnCraftFailure(player, "产物塞不进背包的任何一格");
            return null;
        }

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
     * 取一次产物，<b>直接塞进共享存储</b>（Shift 批量合成走这条）。
     *
     * <p>
     * 「从仓库里拿料做的东西，成品回仓库」是这个界面的自然闭环：一次 Shift 点下去，
     * 料从仓库扣、成品回仓库堆，玩家背包一个格子都不占。原版那条
     * （{@link #transferCraftResult}）仍然保留 —— 不带 Shift 的普通点击、以及别的
     * 模组调 {@code transferStackInSlot} 时走的还是它。
     *
     * <p>
     * 收产物必须<b>整份</b>收得下才动手：{@code insertItem} 到上限会只收一部分，
     * 那部分要是留在产物格里，下一次 {@code onCraftMatrixChanged} 就把它抹掉了 ——
     * 宁可这一次不合成，也不能出现「材料扣了、产物蒸发」。正常存量下（上限约 9.2e18）
     * 这条路永远走不到。
     *
     * @return 被搬进仓库的产物；没搬动时返回 null（自动合成循环靠这个决定停不停）
     */
    public ItemStack transferCraftResultToStorage(EntityPlayer player, SharedStorage storage, DeltaRecorder recorder) {
        Slot slot = getSlot(RESULT_SLOT);
        ItemStack live = liveCraftResult(player);
        if (live == null) return null;

        ItemKey key = ItemKey.of(live);
        if (key == null) {
            warnCraftFailure(player, "产物认不出物品键，无法入库");
            return null;
        }

        ItemStack before = live.copy();
        long stored = storage.insertItem(key, live.stackSize);
        if (stored < live.stackSize) {
            // 存储到单条目上限了：把刚塞进去的退回来，这一次当没发生
            if (stored > 0L) storage.extractItem(key, stored);
            warnCraftFailure(player, "共享存储收不下这份产物（" + live.stackSize + " 个）");
            return null;
        }

        recorder.item(key);
        slot.putStack(null);
        // 这一步才扣合成栏里的材料 —— 顺序不能反（同 transferCraftResult）
        slot.onPickupFromSlot(player, live);
        return before;
    }

    /**
     * 客户端：把合成栏里的原料一次性退回共享存储（侧栏那个「返还原料」按钮）。
     *
     * <p>
     * 合成栏有 9 格，一格一个包太吵，所以走一个动作让服务端自己扫一遍 ——
     * 和别处一样，客户端只说意图，退多少由服务端按实际内容定。
     */
    public void requestDumpCraftGrid() {
        if (!hasCraftMaterial()) return;
        NetworkHandler.INSTANCE.sendToServer(new PacketStorageAction(PacketStorageAction.DUMP_CRAFT_GRID));
    }

    /** 合成栏里还有没有料（用来区分「正常做完了」和「异常地做不出来」）。 */
    private boolean hasCraftMaterial() {
        for (int i = 0; i < CRAFT_SLOTS; i++) {
            ItemStack stack = craftMatrix.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0) return true;
        }
        return false;
    }

    /**
     * 放弃一次合成时留一行日志。
     *
     * <p>
     * 「点了没反应」是这个界面最难查的故障 —— 服务端知道原因，玩家只看到一个没动静的
     * 产物格。所以每条放弃路径都要说话，并且带上<b>是哪一侧</b>：客户端也会跑一遍
     * {@code slotClick}，两边的失败原因经常不一样。
     */
    private void warnCraftFailure(EntityPlayer player, String reason) {
        boolean remote = player.worldObj != null && player.worldObj.isRemote;
        com.futa_gtnh.FutaGtnhMod.LOG
            .warn("共享存储：放弃一次合成（{}，玩家 {}）—— {}", remote ? "客户端" : "服务端", player.getCommandSenderName(), reason);
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

    /** 数字键快捷交换。流体条目没有可放入快捷栏的物品键，仍保留流体页原有语义。 */
    private void handleHotbarSwap(int viewIndex, int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= InventoryExchange.HOTBAR_SIZE) return;
        if (player.inventory.getItemStack() != null) return;

        ItemStack display = ghost.getDisplay(viewIndex);
        if (display == null) return;

        FluidKey fluidKey = viewIndex < pageFluidKeys.length ? pageFluidKeys[viewIndex] : null;
        FluidStack shown = GTUtility.getFluidFromDisplayStack(display);
        if (fluidKey != null || (shown != null && shown.getFluid() != null && shown.amount > 0)) return;

        ItemKey key = viewIndex < pageItemKeys.length ? pageItemKeys[viewIndex] : null;
        if (key == null) key = ItemKey.of(display);
        if (key == null) return;

        NetworkHandler.INSTANCE.sendToServer(
            PacketStorageAction
                .itemSlot(PacketStorageAction.HOTBAR_SWAP, key, hotbarSlot, Math.max(1, display.getMaxStackSize())));
    }

    /** Q / Ctrl+Q：共享虚拟槽位没有真实栈可供原版丢弃，所以改走服务端动作。 */
    private void handleDrop(int viewIndex, int mouseButton) {
        if (player.inventory.getItemStack() != null) return;

        ItemStack display = ghost.getDisplay(viewIndex);
        if (display == null) return;

        FluidKey fluidKey = viewIndex < pageFluidKeys.length ? pageFluidKeys[viewIndex] : null;
        FluidStack shown = GTUtility.getFluidFromDisplayStack(display);
        if (fluidKey != null || (shown != null && shown.getFluid() != null && shown.amount > 0)) return;

        ItemKey key = viewIndex < pageItemKeys.length ? pageItemKeys[viewIndex] : null;
        if (key == null) key = ItemKey.of(display);
        if (key == null) return;

        long amount = mouseButton == 1 ? Math.max(1, display.getMaxStackSize()) : 1L;
        NetworkHandler.INSTANCE.sendToServer(PacketStorageAction.item(PacketStorageAction.DROP_ITEM, key, amount));
    }

    /** 双击收集只对物品条目生效；流体页签仍由自己的容器/毫巴操作处理。 */
    private void handleCollect(int viewIndex) {
        ItemStack cursor = player.inventory.getItemStack();
        ItemStack display = ghost.getDisplay(viewIndex);
        if (cursor == null || display == null) return;

        FluidKey fluidKey = viewIndex < pageFluidKeys.length ? pageFluidKeys[viewIndex] : null;
        FluidStack shown = GTUtility.getFluidFromDisplayStack(display);
        if (fluidKey != null || (shown != null && shown.getFluid() != null && shown.amount > 0)) return;

        ItemKey displayKey = viewIndex < pageItemKeys.length ? pageItemKeys[viewIndex] : null;
        if (displayKey == null) displayKey = ItemKey.of(display);
        ItemKey cursorKey = ItemKey.of(cursor);
        if (displayKey == null || !displayKey.equals(cursorKey)) return;

        sendCollectToCursor();
    }

    /** 普通左/右键从共享存储取到光标。 */
    private void sendWithdrawToCursor(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return;
        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_TO_CURSOR, key, amount));
    }

    /** 发送 Bogo Sorter 的空槽单物品转移（Ctrl+右键）。 */
    public void requestWithdrawFromDisplayToEmpty(int viewIndex) {
        ItemStack display = ghost.getDisplay(viewIndex);
        if (display == null) return;

        ItemKey key = viewIndex < pageItemKeys.length ? pageItemKeys[viewIndex] : null;
        if (key == null) key = ItemKey.of(display);
        if (key == null) return;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_ITEM_EMPTY, key, 1L));
    }

    /** 发送 Bogo Sorter 的整库转移（空格+左键）。 */
    public void sendWithdrawAllItems() {
        NetworkHandler.INSTANCE.sendToServer(new PacketStorageAction(PacketStorageAction.WITHDRAW_ALL));
    }

    /** 发送 Bogo Sorter 的共享存储整库丢弃（空格+Q）。 */
    public void sendDropAllItems() {
        NetworkHandler.INSTANCE.sendToServer(new PacketStorageAction(PacketStorageAction.DROP_ALL_ITEMS));
    }

    /** 发送 Bogo Sorter 的共享存储同类丢弃（Alt+Q）。 */
    public void sendDropMatching(ItemKey key) {
        if (key == null) return;
        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.DROP_MATCHING_ITEMS, key, 0L));
    }

    /** 发送一次服务端权威的双击收集请求，数量只取光标还剩的空间。 */
    private void sendCollectToCursor() {
        ItemStack cursor = player.inventory.getItemStack();
        if (cursor == null) return;

        ItemKey key = ItemKey.of(cursor);
        if (key == null) return;

        int limit = cursor.getMaxStackSize();
        if (limit <= 0) limit = 64;
        long space = limit - cursor.stackSize;
        if (space <= 0L) return;

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.COLLECT_TO_CURSOR, key, space));
    }

    /**
     * @param mouseButton 0=左键, 1=右键, 2=中键
     * @param mode        0=普通, 1=Shift, 2=数字键, 3=中键, 4=丢弃, 5=拖拽经过, 6=双击
     */
    private void handleSharedSlotClick(int viewIndex, int mouseButton, int mode) {
        // 只认真正的「鼠标点击」。slotClick 的 mode 参数是复用的：
        // 2 = 数字键与快捷栏交换（mouseButton 是快捷栏下标 0..8，不是鼠标键）
        // 4 = Q 键丢弃（mouseButton 是 0/1）
        // 6 = 双击收集
        if (mode == 2) {
            handleHotbarSwap(viewIndex, mouseButton);
            return;
        }
        if (mode == 4) {
            handleDrop(viewIndex, mouseButton);
            return;
        }
        if (mode == 6) {
            handleCollect(viewIndex);
            return;
        }
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

        if (mouseButton == 2) {
            // 方块终端中键选择物品管道的输出；远程打开的共享存储仍保留原本的整组取出语义。
            if (terminal != null) {
                NetworkHandler.INSTANCE
                    .sendToServer(PacketStorageAction.item(PacketStorageAction.SET_TERMINAL_ITEM, itemKey, 0L));
                return;
            }
        }

        long amount;
        if (dragging) {
            amount = 1L;
        } else if (mouseButton == 2) {
            amount = Math.max(1, display.getMaxStackSize());
        } else if (shift) {
            amount = mouseButton == 1 ? -1L
                : Math.max(1L, Math.min((long) Config.shiftClickWithdrawAmount, display.getMaxStackSize()));
        } else if (mouseButton == 1) {
            // 普通右键和原版容器一样：把半叠（奇数时向上取整）放到光标。
            sendWithdrawToCursor(itemKey, Math.max(1, (display.getMaxStackSize() + 1) / 2));
            return;
        } else {
            // 普通左键和原版容器一样：把一整叠放到光标，而不是直接塞进背包。
            sendWithdrawToCursor(itemKey, Math.max(1, display.getMaxStackSize()));
            return;
        }

        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_ITEM, itemKey, amount));
    }

    /** 供 {@link GhostInventory} / {@link SlotSharedStorage} 在别的模组直接操作槽位时调用。 */
    public void requestWithdrawFromDisplay(int viewIndex, int amount) {
        requestWithdrawFromDisplay(viewIndex, (long) amount);
    }

    /** 供滚轮等快捷操作使用的长数量版本；流体页签的数量单位是毫巴。 */
    public void requestWithdrawFromDisplay(int viewIndex, long amount) {
        if (isClient(player)) {
            withdrawFromDisplay(viewIndex, amount);
        }
    }

    public void requestDepositFromExternal(ItemStack stack) {
        requestDepositMatching(stack, stack == null ? 0L : stack.stackSize);
    }

    /** 从玩家背包里找出指定物品存入；数量由服务端再次按实际背包内容封顶。 */
    public void requestDepositMatching(ItemStack stack, long amount) {
        if (stack == null) return;
        requestDepositMatching(ItemKey.of(stack), amount);
    }

    /**
     * 按<b>条目键</b>从玩家背包里凑出来存入（{@code amount <= 0} = 有多少存多少）。
     *
     * <p>
     * 和上面那个「拿一个物品栈来描述要存什么」的版本是同一件事，区别只在于怎么描述。
     * <b>Shift + 双击那条路必须用这个版本</b>：双击的第二下发生在第一下已经把那一格
     * 存走之后，那时候槽位里已经没有东西可以拿来描述「要存的是哪一种」了
     * （见 {@code GuiSharedTerminal.handleShiftDoubleClick}）。
     *
     * <p>
     * 语义仍然是「<b>先从背包里扣，再存进共享存储</b>」，数量由服务端按背包实际内容封顶，
     * 所以客户端传 0 或者传一个天文数字都不可能凭空造出物品。
     */
    public void requestDepositMatching(ItemKey key, long amount) {
        if (!isClient(player) || key == null) return;
        NetworkHandler.INSTANCE
            .sendToServer(PacketStorageAction.item(PacketStorageAction.DEPOSIT_MATCHING, key, amount));
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
    public void sendDepositFromSlot(int containerSlot, long amount) {
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
