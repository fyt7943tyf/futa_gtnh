package com.futa_gtnh.shared;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 共享存储中「一种物品」的规范化标识，用作 {@link SharedStorage} 的键。
 *
 * <p>
 * 相等的判定标准是 <b>物品 + 元数据 + NBT</b> 三者完全相同（忽略堆叠数量），
 * 也就是把「堆叠数量」从身份里剥离出去 —— 这正是实现无限堆叠的前提。
 *
 * <p>
 * 两个容易踩的坑，这里都处理掉了：
 * <ol>
 * <li>NBT 必须<b>深拷贝</b>后再存。直接引用调用方的 {@link NBTTagCompound}，
 * 一旦对方改了它（GT 机器改电池电量就是这么干的），键的 {@code hashCode} 就会变，
 * 存进 {@link java.util.HashMap} 之后再也查不出来。</li>
 * <li>空标签要归一化成 {@code null}。{@code new NBTTagCompound()} 和
 * 「没有标签」在语义上是一回事，不归一化就会变成两个不同的条目。</li>
 * </ol>
 *
 * <p>
 * 1.7.10 里所有 NBT 标签类型（含 {@code NBTTagList}、{@code NBTTagByteArray}、
 * {@code NBTTagIntArray}）都按内容实现了 {@code equals}/{@code hashCode}，
 * 所以这里直接复用原版 NBT 的比较语义是安全的：附魔书、GT 工具、电池
 * 这类「靠列表型 NBT 区分」的物品不会互相串条目。
 */
public final class ItemKey {

    private final Item item;
    private final int meta;
    /** 已深拷贝并规范化；可能为 null */
    private final NBTTagCompound nbt;
    private final int hash;

    private ItemKey(Item item, int meta, NBTTagCompound nbt) {
        this.item = item;
        this.meta = meta;
        this.nbt = nbt;

        int h = item.hashCode();
        h = h * 31 + meta;
        h = h * 31 + (nbt == null ? 0 : nbt.hashCode());
        this.hash = h;
    }

    /** @return 规范化后的键；物品为 null（空气/空栈）时返回 null */
    public static ItemKey of(ItemStack stack) {
        if (stack == null) return null;
        Item item = stack.getItem();
        if (item == null) return null;
        return new ItemKey(item, stack.getItemDamage(), normalize(stack.getTagCompound()));
    }

    public static ItemKey of(Item item, int meta, NBTTagCompound nbt) {
        if (item == null) return null;
        return new ItemKey(item, meta, normalize(nbt));
    }

    private static NBTTagCompound normalize(NBTTagCompound tag) {
        if (tag == null || tag.hasNoTags()) return null;
        return (NBTTagCompound) tag.copy();
    }

    public Item getItem() {
        return item;
    }

    public int getMeta() {
        return meta;
    }

    public boolean hasNbt() {
        return nbt != null;
    }

    /** @return NBT 的独立副本，可能为 null */
    public NBTTagCompound copyNbt() {
        return nbt == null ? null : (NBTTagCompound) nbt.copy();
    }

    /**
     * 造一个 {@code stackSize == 1} 的原型栈。每次调用都返回新对象，
     * 调用方可以随意修改（改数量、改 NBT）而不会污染这个键。
     */
    public ItemStack prototype() {
        ItemStack stack = new ItemStack(item, 1, meta);
        if (nbt != null) {
            stack.setTagCompound((NBTTagCompound) nbt.copy());
        }
        return stack;
    }

    /**
     * 造一个指定数量的原型栈。
     *
     * <p>
     * 注意 {@link ItemStack#stackSize} 是 {@code int}，所以超过
     * {@link Integer#MAX_VALUE} 的数量会被夹到 {@code Integer.MAX_VALUE}。
     * 需要完整数量时请另外读 {@link SharedStorage#getItemAmount(ItemKey)}。
     */
    public ItemStack prototype(long amount) {
        ItemStack stack = prototype();
        stack.stackSize = (int) Math.min(Math.max(amount, 1L), Integer.MAX_VALUE);
        return stack;
    }

    // ------------------------------------------------------------------
    // 序列化：刻意不用 ItemStack.writeToNBT
    //
    // 原版 writeToNBT 把物品 id 写成 (short)，Damage 也写成 (short)：
    // p_77955_1_.setShort("id", (short) Item.getIdFromItem(item));
    // p_77955_1_.setShort("Damage", (short) this.itemDamage);
    // 虽然 1.7.10 的物品 id 空间本身限制在 short 范围内、实际不会溢出，
    // 但依赖这个隐含前提没有意义 —— 这里直接写 int，永远不会有截断问题，
    // 也不受将来 id 空间变化的影响。
    // ------------------------------------------------------------------

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("item", Item.getIdFromItem(item));
        tag.setInteger("meta", meta);
        if (nbt != null) {
            tag.setTag("nbt", nbt.copy());
        }
        return tag;
    }

    /** @return 反序列化结果；物品 id 无效（对应模组被移除）时返回 null */
    public static ItemKey readFromNbt(NBTTagCompound tag) {
        if (tag == null) return null;
        if (!tag.hasKey("item")) return null;
        Item item = Item.getItemById(tag.getInteger("item"));
        if (item == null) return null;
        NBTTagCompound nbt = tag.hasKey("nbt") ? (NBTTagCompound) tag.getTag("nbt") : null;
        return new ItemKey(item, tag.getInteger("meta"), normalize(nbt));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ItemKey)) return false;
        ItemKey o = (ItemKey) other;
        if (this.hash != o.hash) return false;
        if (this.meta != o.meta) return false;
        if (this.item != o.item) return false;
        if (this.nbt == null) return o.nbt == null;
        return this.nbt.equals(o.nbt);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "ItemKey[" + Item.itemRegistry.getNameForObject(item) + "@" + meta + (nbt == null ? "" : "+nbt") + "]";
    }
}
