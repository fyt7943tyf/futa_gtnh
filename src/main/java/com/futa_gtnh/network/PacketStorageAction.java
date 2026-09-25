package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.exchange.StorageActionHandler;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -> 服务端的操作请求（取东西、存东西、设置终端管道输出）。
 *
 * <p>
 * 设计上<b>只发「意图」，不发「结果」</b>：客户端说的是「我要取 1000 个铁锭」，
 * 而不是「把铁锭数量改成 X」。所有数量校验、上限夹取、背包空间检查都在服务端做，
 * 客户端算出来的任何数字都不被信任 —— 否则改个客户端就能凭空刷物品。
 *
 * <p>
 * 条目用 {@link #keyTag}（物品/流体的规范键）标识，不用客户端列表下标：
 * 下标依赖客户端当时的搜索/排序/分页状态，和服务器对不上，
 * 而且在「别人刚好把这条取空了」的时候会指到错误的东西上。
 */
public class PacketStorageAction implements IMessage {

    // ---- 取出：共享 -> 个人 ----
    /** 取出物品到背包。amount &lt;= 0 表示「尽量塞满背包」。 */
    public static final byte WITHDRAW_ITEM = 0;
    /** 灌装背包里的容器（桶 / GT 单元 / 任何注册过的容器）。amount 为毫巴数。 */
    public static final byte FILL_CONTAINER = 1;
    /** 取出 GT 流体显示物品（可以再存回来，也可以放进 NEI 之类的地方）。 */
    public static final byte TAKE_FLUID_DISPLAY = 2;

    // ---- 存入：个人 -> 共享 ----
    /** 存入玩家背包的某一格。amount &lt;= 0 表示整叠存入。 */
    public static final byte DEPOSIT_INV_SLOT = 10;
    /** 批量存入背包。{@link #invSlot} 用作范围：0=全部, 1=快捷栏, 2=主背包。 */
    public static final byte DEPOSIT_ALL = 11;
    /** 存入鼠标光标上拿着的那一叠。amount &lt;= 0 表示全部。 */
    public static final byte DEPOSIT_CURSOR = 12;
    /** 把背包里所有「装着流体」的容器都倒进共享存储（容器本身留在背包里）。 */
    public static final byte DRAIN_CONTAINERS = 13;
    /**
     * 从玩家背包里找出指定条目并存入。数量由服务端按背包实际内容封顶。
     *
     * <p>
     * 这个动作是给「外部模组直接往虚拟槽位里塞物品」那条路准备的
     * （{@code Slot.putStack} / {@code IInventory.setInventorySlotContents}）。
     * 关键是它的语义为「<b>先把这些东西从背包里扣掉</b>，再存进共享存储」，
     * 而不是「凭空存这么多」—— 否则 NEI 之类的模组把玩家背包里的东西
     * 搬进虚拟槽位时，物品会既留在背包里又进了存储，那就是刷物品。
     */
    public static final byte DEPOSIT_MATCHING = 14;
    /**
     * 存入合成栏的某一格（{@link #invSlot} 是 0..3）。
     *
     * <p>
     * 需要单独一个动作，是因为合成栏<b>不在玩家背包里</b>：
     * 1.7.10 原版背包的 2×2 合成栏是 {@code ContainerPlayer} 私有的 {@code InventoryCrafting}，
     * 我们自己的容器同理。所以它没法用 {@code DEPOSIT_INV_SLOT} 那套
     * 「玩家背包索引 0..39」的编号表达，服务端得去操作自己容器里的那个合成栏。
     */
    public static final byte DEPOSIT_CRAFT_SLOT = 15;
    /**
     * 把<b>鼠标光标上</b>那个容器里的流体倒进共享存储，倒空后的容器留在光标上。
     *
     * <p>
     * 和 {@link #DRAIN_CONTAINERS} 的区别是作用对象：那个扫的是玩家背包里所有容器，
     * 这个只处理光标上那一个 —— 光标上的东西不属于 {@code mainInventory}，
     * 背包遍历是碰不到它的。
     *
     * <p>
     * 用在流体页签里：手里举着一桶牛奶点一下，应该是「把牛奶倒进去」，
     * 而不是「把整个桶当成物品收走」。
     */
    public static final byte DRAIN_CURSOR = 16;

    /**
     * 把共享存储里的条目<b>取到光标上</b>（共享存储面板里左键点一下的效果）。
     *
     * <p>
     * 和 {@link #WITHDRAW_ITEM} 的区别只在「落到哪里」：那个是直接写进玩家背包，
     * 这个放在光标上 —— 面板要的就是「像从箱子里拿出来」的手感。
     */
    public static final byte WITHDRAW_TO_CURSOR = 17;

    // ---- 终端 ----
    /** 设置方块终端往相邻管道/流体罐输出的流体。 */
    public static final byte SET_TERMINAL_FLUID = 20;
    /** 设置方块终端往物品管道输出的物品。 */
    public static final byte SET_TERMINAL_ITEM = 21;

    // ---- NEI 合成联动 ----
    /**
     * 按客户端发来的布局填充终端界面的 3×3 合成栏：材料优先从玩家背包取，不够的从共享存储取。
     * 布局（候选 + 每格数量）放在 {@link #keyTag} 里，见 {@link #fillCraft}。
     * {@link #amount} 是倍率：{@code <= 0} 表示「尽量填满」（每格填到堆叠上限）。
     */
    public static final byte FILL_CRAFT_MATRIX = 30;
    /**
     * 自动合成：先按布局填满合成栏，然后反复「取产物进背包 → 补材料」，
     * 直到合成 {@link #amount} 次（{@code <= 0} 表示材料用尽或背包放不下为止）。
     * 全程服务端权威，不走任何模拟点击。
     */
    public static final byte AUTOCRAFT = 31;

    public static final byte KIND_ITEM = 0;
    public static final byte KIND_FLUID = 1;

    private byte action;
    private byte kind;
    private int invSlot;
    private long amount;
    private NBTTagCompound keyTag;

    public PacketStorageAction() {}

    public PacketStorageAction(byte action) {
        this.action = action;
    }

    public static PacketStorageAction item(byte action, com.futa_gtnh.shared.ItemKey key, long amount) {
        PacketStorageAction packet = new PacketStorageAction(action);
        packet.kind = KIND_ITEM;
        packet.amount = amount;
        packet.keyTag = key == null ? null : key.writeToNbt();
        return packet;
    }

    public static PacketStorageAction fluid(byte action, com.futa_gtnh.shared.FluidKey key, long amount) {
        PacketStorageAction packet = new PacketStorageAction(action);
        packet.kind = KIND_FLUID;
        packet.amount = amount;
        packet.keyTag = key == null ? null : key.writeToNbt();
        return packet;
    }

    public static PacketStorageAction slot(byte action, int invSlot) {
        PacketStorageAction packet = new PacketStorageAction(action);
        packet.invSlot = invSlot;
        return packet;
    }

    public static PacketStorageAction scope(byte action, int scope) {
        PacketStorageAction packet = new PacketStorageAction(action);
        packet.invSlot = scope;
        return packet;
    }

    /** 链式设置数量，方便在构造之后补充参数。 */
    public PacketStorageAction withAmount(long amount) {
        this.amount = amount;
        return this;
    }

    /**
     * NEI 合成联动用的构造：布局 NBT 直接放进 {@link #keyTag}，倍率放 {@link #amount}。
     *
     * <p>
     * 布局格式（由 {@code SharedTerminalOverlayHandler} 生成、
     * {@code CraftFiller} 解析）：
     * 
     * <pre>
     * root: {
     *   "slots": [ { "idx": 0..8, "count": 每次合成需要几个,
     *                "cands": [ ItemKey 的 NBT, ... 按优先级排序的候选 ],
     *                "toolOres": [ "craftingToolSaw", ... 可选的工具矿辞 ] }, ... ]
     * }
     * </pre>
     */
    public static PacketStorageAction craft(byte action, NBTTagCompound layout, long multiplier) {
        PacketStorageAction packet = new PacketStorageAction(action);
        packet.keyTag = layout;
        packet.amount = multiplier;
        return packet;
    }

    public byte getAction() {
        return action;
    }

    public byte getKind() {
        return kind;
    }

    public int getInvSlot() {
        return invSlot;
    }

    public long getAmount() {
        return amount;
    }

    /** 合成联动动作携带的布局 NBT（见 {@link #craft}）；别的动作返回 null。 */
    public NBTTagCompound getLayoutTag() {
        return keyTag;
    }

    public com.futa_gtnh.shared.ItemKey getItemKey() {
        return kind == KIND_ITEM && keyTag != null ? com.futa_gtnh.shared.ItemKey.readFromNbt(keyTag) : null;
    }

    public com.futa_gtnh.shared.FluidKey getFluidKey() {
        return kind == KIND_FLUID && keyTag != null ? com.futa_gtnh.shared.FluidKey.readFromNbt(keyTag) : null;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        kind = buf.readByte();
        invSlot = buf.readInt();
        amount = buf.readLong();
        keyTag = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeByte(kind);
        buf.writeInt(invSlot);
        buf.writeLong(amount);
        buf.writeBoolean(keyTag != null);
        if (keyTag != null) {
            ByteBufUtils.writeTag(buf, keyTag);
        }
    }

    public static class Handler implements IMessageHandler<PacketStorageAction, IMessage> {

        @Override
        public IMessage onMessage(PacketStorageAction message, MessageContext ctx) {
            StorageActionHandler.handle(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
