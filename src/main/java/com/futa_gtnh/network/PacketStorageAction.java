package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.exchange.StorageActionHandler;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -> 服务端的操作请求（取东西、存东西、选终端输出流体）。
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
     * 1.7.10 的 2×2 合成栏是 {@code ContainerPlayer} 私有的 {@code InventoryCrafting}，
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

    // ---- 终端 ----
    /** 设置方块终端往相邻管道/流体罐输出的流体。 */
    public static final byte SET_TERMINAL_FLUID = 20;

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
