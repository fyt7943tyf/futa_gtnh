package com.futa_gtnh.reactor;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * 一份反应堆摆放配置（布局档案里的一条）。
 *
 * <p>
 * 记录的是反应堆内部网格的<b>逐格快照</b>：每格存完整的「物品 + 耐久 + NBT」。
 * NBT 必须原样保留 —— IC2 的燃料棒/散热组件把损耗记在栈 NBT 里
 * （{@code ItemGradualInt} 的 {@code advDmg} 整型），布局档案的价值就在于能
 * <b>精确复刻</b>；「拿档案里的 NBT 去共享背包里精确等值请求会请求不到」这个问题，
 * 是部署侧（{@link ReactorDeployer} 的三级匹配）去解决的，记录侧不做任何削剪。
 *
 * <p>
 * <b>序列化刻意不用 {@code ItemStack.writeToNBT}：</b>它把物品 id 和 Damage 都写成
 * short（1.7.10 源码如此），这是共享存储 {@code ItemKey} 注释里踩过的同一个坑。
 * 这里全部写 int，数量也单独存 int，永远没有截断问题。空 NBT 与「没有 NBT」
 * 归一化为同一种写法（不写 {@code nbt} 子标签）。
 *
 * <p>
 * 网格坐标约定：{@code slots[row * columns + column]}（行主序，与界面逐行绘制一致）；
 * IC2 侧 {@code getItemAt/setItemAt} 的第一参是列、第二参是行。
 */
public final class ReactorLayout {

    /** 网格行数：IC2 反应堆固定 6 行。 */
    public static final int ROWS = 6;
    /** 列数范围：核心自身 3 列，每个相邻反应堆仓 +1 列，满配 9 列。 */
    public static final int MIN_COLUMNS = 3;
    public static final int MAX_COLUMNS = 9;

    public long id;
    /** 玩家可改的名字（默认按记录时间自动起）。 */
    public String name = "";
    /** 记录来源是不是流体冷却反应堆（{@code IReactor.isFluidCooled()}）。 */
    public boolean fluidCooled;
    public int columns;
    public long createdAt;

    /** 长度 = columns × ROWS；空格为 null。物品栈一律持有独立副本。 */
    public final ItemStack[] slots;

    public ReactorLayout(int columns) {
        this.columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, columns));
        this.slots = new ItemStack[this.columns * ROWS];
    }

    public static ReactorLayout readFrom(NBTTagCompound tag) {
        ReactorLayout layout = new ReactorLayout(tag.getInteger("columns"));
        layout.id = tag.getLong("id");
        layout.name = tag.getString("name");
        layout.fluidCooled = tag.getBoolean("fluid");
        layout.createdAt = tag.getLong("created");

        NBTTagList list = tag.getTagList("slots", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            int slot = entry.getInteger("slot");
            if (slot < 0 || slot >= layout.slots.length) continue;
            Item item = Item.getItemById(entry.getInteger("item"));
            if (item == null) continue; // 物品没了（mod 移除）：跳过这一格，档案其余部分照常
            int size = Math.max(1, Math.min(64, entry.getInteger("size")));
            ItemStack stack = new ItemStack(item, size, entry.getInteger("meta"));
            if (entry.hasKey("nbt")) {
                stack.setTagCompound(entry.getCompoundTag("nbt"));
            }
            layout.slots[slot] = stack;
        }
        return layout;
    }

    public void writeTo(NBTTagCompound tag) {
        tag.setLong("id", id);
        tag.setString("name", name);
        tag.setBoolean("fluid", fluidCooled);
        tag.setInteger("columns", columns);
        tag.setLong("created", createdAt);

        NBTTagList list = new NBTTagList();
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.getItem() == null) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("slot", i);
            entry.setInteger("item", Item.getIdFromItem(stack.getItem()));
            entry.setInteger("meta", stack.getItemDamage());
            entry.setInteger("size", stack.stackSize);
            NBTTagCompound nbt = stack.getTagCompound();
            if (nbt != null && !nbt.hasNoTags()) {
                entry.setTag("nbt", (NBTTagCompound) nbt.copy());
            }
            list.appendTag(entry);
        }
        tag.setTag("slots", list);
    }

    /** @return 布局里的非空格数（「这一摆法用了多少件原件」） */
    public int occupiedSlots() {
        int count = 0;
        for (ItemStack slot : slots) {
            if (slot != null && slot.getItem() != null) count++;
        }
        return count;
    }

    /** 深拷贝一份（部署/预览都要在副本上动，不能共享数组）。 */
    public ReactorLayout copy() {
        NBTTagCompound tag = new NBTTagCompound();
        writeTo(tag);
        return readFrom(tag);
    }
}
