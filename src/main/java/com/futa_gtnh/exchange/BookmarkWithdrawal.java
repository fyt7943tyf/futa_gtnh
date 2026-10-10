package com.futa_gtnh.exchange;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

/** 书签只表达取物意图；实际扣库存和背包容量检查复用普通取物路径。此类不依赖 NEI。 */
public final class BookmarkWithdrawal {

    public static final int MAX_ENTRIES = 256;
    public static final int MAX_BYTES = 24000;
    private static final long MAX_AMOUNT = 1_000_000_000_000L;

    private BookmarkWithdrawal() {}

    /** 普通组只发一个包；超长组按条数和 NBT 字节预算分批，保留清单顺序。 */
    public static List<PacketStorageAction> requests(List<ItemStack> items, int windowId) {
        Map<ItemKey, Long> amounts = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.stackSize <= 0) continue;
            ItemKey key = ItemKey.of(stack);
            if (key == null) continue;
            long previous = amounts.containsKey(key) ? amounts.get(key) : 0L;
            amounts.put(key, Math.min(MAX_AMOUNT, previous + stack.stackSize));
        }
        List<PacketStorageAction> packets = new ArrayList<>();
        NBTTagList entries = new NBTTagList();
        int bytes = 64;
        for (Map.Entry<ItemKey, Long> entry : amounts.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setTag(
                "key",
                entry.getKey()
                    .writeToNbt());
            tag.setLong("amount", entry.getValue());
            int cost = size(tag) + 8;
            if (cost + 64 > MAX_BYTES) throw new IllegalArgumentException("Bookmark item NBT exceeds packet budget");
            if (entries.tagCount() >= MAX_ENTRIES || bytes + cost > MAX_BYTES) {
                packets.add(packet(entries, windowId));
                entries = new NBTTagList();
                bytes = 64;
            }
            entries.appendTag(tag);
            bytes += cost;
        }
        if (entries.tagCount() > 0) packets.add(packet(entries, windowId));
        return packets;
    }

    private static PacketStorageAction packet(NBTTagList entries, int windowId) {
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("items", entries);
        return PacketStorageAction.nbt(PacketStorageAction.WITHDRAW_BOOKMARK_ITEMS, root)
            .withInvSlot(windowId);
    }

    /** 先校验整个批次，再移动；零/负数不能落入普通取物的「尽量塞满」语义。 */
    public static long withdraw(EntityPlayer player, NBTTagCompound root, SharedStorage storage,
        DeltaRecorder recorder) {
        if (root == null || !root.hasKey("items", 9) || size(root) > MAX_BYTES) return 0L;
        NBTTagList entries = root.getTagList("items", 10);
        if (entries.tagCount() == 0 || entries.tagCount() > MAX_ENTRIES) return 0L;
        Map<ItemKey, Long> amounts = new LinkedHashMap<>();
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            if (!entry.hasKey("key", 10) || !entry.hasKey("amount", 4)) return 0L;
            NBTTagCompound keyTag = entry.getCompoundTag("key");
            if (!keyTag.hasKey("item", 3) || !keyTag.hasKey("meta", 3)
                || keyTag.hasKey("nbt") && !keyTag.hasKey("nbt", 10)) return 0L;
            ItemKey key = ItemKey.readFromNbt(keyTag);
            long amount = entry.getLong("amount");
            if (key == null || amount <= 0L || amount > MAX_AMOUNT) return 0L;
            long previous = amounts.containsKey(key) ? amounts.get(key) : 0L;
            amounts.put(key, Math.min(MAX_AMOUNT, previous + amount));
        }
        long moved = 0L;
        for (Map.Entry<ItemKey, Long> entry : amounts.entrySet()) {
            moved += InventoryExchange.withdrawItem(player, entry.getKey(), entry.getValue(), storage, recorder);
        }
        return moved;
    }

    private static int size(NBTTagCompound tag) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            CompressedStreamTools.write(tag, new DataOutputStream(buffer));
            return buffer.size();
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot serialize bookmark request", error);
        }
    }
}
