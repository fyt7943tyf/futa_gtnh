package com.futa_gtnh.station;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 「挂在匠魂合成站旁边的那个箱子」—— 但它没有实体，背后就是全服共享存储。
 *
 * <p>
 * 做法是冒充匠魂原生的「相邻容器」：{@code CraftingStationLogic.chest} 指向本类之后，
 * 合成站界面会用它自己的箱子布局（{@code ChestLayout}）、自己创建 27 个
 * {@code ChestSlot} 真槽位，于是拖动、Shift 转移、NEI 的材料指引、悬停提示
 * 全部走原版/匠魂的既有代码，我们一行界面代码都不用写。
 *
 * <p>
 * <b>为什么是 27 格（6 列 × 5 行）</b>：匠魂在槽位数 ≤ 60 时固定按 6 列排版
 * （见 {@code CraftingStationGui.selectChestLayout}），27 格正好是「一个原版单箱子」
 * 的大小，界面右下角那 3 个空格子也是真箱子会有的样子。
 *
 * <p>
 * <b>两边的角色完全不同</b>：
 * <ul>
 * <li><b>服务端</b>（{@code remote = false}）：槽位里的东西是「这一页的第 i 个物品键」，
 * 数量实时问共享存储要。取出 = {@code extractItem}，放入 = {@code insertItem}，
 * 都是守恒操作，任何情况下都不可能凭空多出东西。</li>
 * <li><b>客户端</b>（{@code remote = true}）：只是显示用的镜像，内容是「这一页有哪些物品」
 * 的本地渲染 —— 服务端的槽位同步写进来（{@link #setInventorySlotContents}），
 * 搜索面板每帧也会照着客户端缓存铺一遍（{@link #setDisplay}）。
 * 客户端自己不去算「第 i 格背后是什么物品键」（那是服务端的事，键只由服务端持有），
 * 所以显示和取出的东西一定是同一页。</li>
 * </ul>
 *
 * <p>
 * <b>为什么槽位里最多只放 64 个</b>：共享存储里一种物品可能有几十万个，但
 * {@code ItemStack.stackSize} 一旦超过堆叠上限，原版容器的「整叠拿到光标」会把
 * 整个数量搬到光标上，落到普通槽位里就是凭空刷物品。所以这里恒等于
 * {@code min(真实数量, 64)}，真实数量在终端界面里看。
 */
public class SharedStorageInventory implements IInventory {

    /** 匠魂固定用 6 列排箱子（槽位数 ≤ 60 时），这里跟着它走。 */
    public static final int COLUMNS = 6;
    /** 5 行 × 6 列 = 30 个格子，只填 27 个，和一个原版单箱子一模一样。 */
    public static final int ROWS = 5;
    /** 真实格数。 */
    public static final int SIZE = 27;
    /** 单格显示上限，见类注释。 */
    public static final int MAX_DISPLAY = 64;

    /** true = 客户端镜像，false = 服务端权威。 */
    private final boolean remote;

    /** 服务端：客户端推过来的「这一页有哪些物品」。 */
    private final ItemKey[] keys = new ItemKey[SIZE];

    /** 客户端：服务端同步过来的槽位内容。 */
    private final ItemStack[] mirror = new ItemStack[SIZE];

    /**
     * 「正在由服务端的槽位同步写入客户端镜像」。
     *
     * <p>
     * 客户端那边的镜像<b>只能</b>由服务端的槽位同步来写（{@code Container.putStackInSlot}
     * → {@code Slot.putStack} → {@code IInventory#setInventorySlotContents}）。
     * 别的模组（MouseTweaks 的拖动、NEI 的模拟点击）在客户端也会直接写槽位，
     * 那一写就会把显示清掉 —— 而服务端那一格的值<b>并没有变</b>（数量从 5000 变 4936，
     * 显示值仍然是 64），所以永远不会有回包把它修回来，物品就在界面上「消失」了。
     *
     * <p>
     * 所以这里只认服务端同步那一条路：{@code mixins/MixinContainer} 挂在
     * {@code Container.putStackInSlot} / {@code putStacksInSlots} 上
     * （这两个方法<b>只有网络层会调</b>），在调用前后把这个标记打开/关掉。
     */
    private static boolean serverSync;

    public static void beginServerSync() {
        serverSync = true;
    }

    public static void endServerSync() {
        serverSync = false;
    }

    public SharedStorageInventory(boolean remote) {
        this.remote = remote;
    }

    public boolean isRemote() {
        return remote;
    }

    // ==================================================================
    // 服务端专用：视图与守恒读写
    // ==================================================================

    /**
     * 记下客户端推来的这一页。<b>只信物品键，不信数量</b> —— 数量永远现查存储，
     * 所以客户端撒谎也拿不到不存在的东西。
     */
    public void setView(List<ItemKey> list) {
        for (int i = 0; i < SIZE; i++) {
            keys[i] = list != null && i < list.size() ? list.get(i) : null;
        }
    }

    /** @return 第 i 格背后的物品键（服务端用；客户端一律为 null） */
    public ItemKey keyAt(int index) {
        return index >= 0 && index < SIZE ? keys[index] : null;
    }

    /**
     * 存东西进共享存储。
     *
     * @return 实际存进去的数量（因为饱和上限没存进去的部分由调用方保留）
     */
    public long deposit(ItemStack stack) {
        if (remote || stack == null || stack.stackSize <= 0) return 0L;

        SharedStorage storage = SharedStorageManager.getStorage();
        long accepted = storage.insertItem(stack, stack.stackSize);
        if (accepted <= 0L) return 0L;

        broadcast(ItemKey.of(stack));
        return accepted;
    }

    /**
     * 从共享存储取东西。
     *
     * @return 取出来的物品栈；这一页没有这一格 / 已经取空时返回 null
     */
    public ItemStack withdraw(int index, int amount) {
        if (remote || amount <= 0) return null;
        ItemKey key = keyAt(index);
        if (key == null) return null;

        SharedStorage storage = SharedStorageManager.getStorage();
        long taken = storage.extractItem(key, amount);
        if (taken <= 0L) return null;

        broadcast(key);
        return key.prototype(taken);
    }

    /** 把一个条目的新数量广播给所有正看着共享存储的人（终端 + 挂着本箱子的合成站）。 */
    private static void broadcast(ItemKey key) {
        if (key == null) return;
        SharedStorage storage = SharedStorageManager.getStorage();
        PacketStorageDelta delta = new PacketStorageDelta();
        new DeltaRecorder(storage, delta).item(key);
        SharedStorageManager.broadcastDelta(delta);
    }

    // ==================================================================
    // IInventory
    // ==================================================================

    @Override
    public int getSizeInventory() {
        return SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        if (index < 0 || index >= SIZE) return null;

        if (remote) return mirror[index];

        ItemKey key = keys[index];
        if (key == null) return null;

        long amount = SharedStorageManager.getStorage()
            .getItemAmount(key);
        if (amount <= 0L) return null;
        return key.prototype(Math.min(amount, MAX_DISPLAY));
    }

    @Override
    public ItemStack decrStackSize(int index, int amount) {
        if (index < 0 || index >= SIZE) return null;

        if (remote) {
            // 客户端<b>不做任何本地改动</b>：显示只认服务端的槽位同步（见 serverSync 的说明）。
            // 照样返回一份「取出来」的东西，免得在客户端模拟点击的模组以为这一格是空的
            ItemStack current = mirror[index];
            if (current == null) return null;
            ItemStack result = current.copy();
            result.stackSize = Math.min(amount, current.stackSize);
            return result;
        }

        return withdraw(index, amount);
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int index) {
        return null;
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        if (index < 0 || index >= SIZE) return;

        if (remote) {
            // 只接受服务端的槽位同步，理由见 serverSync
            if (!serverSync) return;
            mirror[index] = stack == null ? null : stack.copy();
            serverWrites++;
            return;
        }

        // 服务端：往虚拟格子里放东西 = 存进共享存储。
        // 位置由「物品是什么」决定，不由「放在第几格」决定 —— 这一页是排序视图，
        // 存进去的东西会自己出现在它该在的位置上。
        deposit(stack);
    }

    @Override
    public String getInventoryName() {
        // 界面标题（匠魂会把这块区域的名字画在存储区左上角）。
        // 客户端顺带把「第几页」拼进去 —— 不按 P 打开搜索栏时，这是唯一的页码提示；
        // 服务端返回的是翻译键原样，因为那边根本不画界面。
        if (!remote) return "futa_gtnh.station.chest";
        return net.minecraft.util.StatCollector.translateToLocal("futa_gtnh.station.chest") + clientSuffix;
    }

    /**
     * 客户端在区域标题后面追加的文字，由搜索面板维护（例如「· 3/12」）。
     *
     * <p>
     * 只影响显示：标题是每帧现算的，改了下一帧就变。
     */
    public void setClientSuffix(String suffix) {
        if (!remote) return;
        this.clientSuffix = suffix == null ? "" : suffix;
    }

    private String clientSuffix = "";

    /**
     * 客户端专用：把「这一格应该显示什么」直接铺进镜像（{@code client/StoragePanel} 每帧调用）。
     *
     * <p>
     * <b>为什么不能只靠服务端的槽位同步。</b>原版那条路是
     * {@code Container.detectAndSendChanges} → {@code S2FPacketSetSlot} → 客户端
     * {@code putStackInSlot} → {@link #setInventorySlotContents}，中间任何一环不成立，
     * 结果都是<b>「东西明明在、点得动，格子却是空的」</b>：格子里画什么只取决于客户端镜像，
     * 而点击是发槽位号给服务端裁决的，两边走的根本不是同一条路。
     *
     * <p>
     * 终端界面从一开始就没依赖那条路（{@code GhostInventory} 由客户端自己铺），
     * 这里补上同样的做法。数量取 {@code min(真实数量, MAX_DISPLAY)}：真数量由
     * {@code StationAmounts} 另外画，塞进 {@code ItemStack.stackSize} 会被原版
     * 「整叠拿到光标」当成真的一叠。
     *
     * @return 有没有真的改动（内容一致时不重建对象）
     */
    public boolean setDisplay(int index, ItemStack expected) {
        if (!remote || index < 0 || index >= SIZE) return false;

        ItemStack current = mirror[index];
        // 比 ItemStack.areItemStacksEqual：物品、元数据、数量和 NBT 全都要一致，
        // 否则 GT 那些把状态写在 NBT 里的东西会一直显示旧状态
        if (ItemStack.areItemStacksEqual(current, expected)) return false;

        mirror[index] = expected == null ? null : expected.copy();
        return true;
    }

    /**
     * 客户端：服务端的槽位同步真正写进镜像的次数。
     *
     * <p>
     * 纯诊断。多人服务器上它一直是 0 的话，说明「服务端推槽位」那条路没生效 ——
     * 显示已经不吃这条路了（见 {@link #setDisplay}），但日志里有这个数字，
     * 排查时就不用再猜。
     */
    public int getServerWriteCount() {
        return serverWrites;
    }

    private int serverWrites;

    @Override
    public boolean hasCustomInventoryName() {
        return true;
    }

    @Override
    public int getInventoryStackLimit() {
        return MAX_DISPLAY;
    }

    /**
     * 一定要返回 true：容器创建时会拿这个判定「玩家能不能用这个界面」，
     * 返回 false 的话合成站界面会开一下就自己关掉。
     */
    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return true;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return true;
    }

    @Override
    public void markDirty() {
        // 落盘由 SharedStorageManager 的自动保存负责，这里没有需要标脏的东西
    }
}
