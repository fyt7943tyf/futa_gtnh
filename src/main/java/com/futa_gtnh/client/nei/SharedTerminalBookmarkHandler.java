package com.futa_gtnh.client.nei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.client.ClientStorageCache;
import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.exchange.BookmarkWithdrawal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;

import codechicken.nei.api.IBookmarkContainerHandler;
import codechicken.nei.recipe.StackInfo;

/** NEI 书签取物清单来自整个共享仓库，执行走服务端批量取物，快捷键由 NEI 配置。 */
public final class SharedTerminalBookmarkHandler implements IBookmarkContainerHandler {

    @Override
    public List<ItemStack> getStorageStacks(GuiContainer gui) {
        List<ItemStack> result = new ArrayList<>();
        if (!(gui instanceof GuiSharedTerminal) || !ClientStorageCache.isReady()) return result;
        for (ClientStorageCache.ItemAmount entry : ClientStorageCache.itemAmounts()) {
            result.add(
                entry.getKey()
                    .prototype(entry.getAmount()));
        }
        return result;
    }

    @Override
    public void pullBookmarkItemsFromContainer(GuiContainer gui, ArrayList<ItemStack> realItems) {
        if (!(gui instanceof GuiSharedTerminal) || realItems == null) return;
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack stack : realItems) {
            // 流体显示栈是虚拟流体数量，不能把它当作真实物品从物品仓库提取。
            if (stack != null && !StackInfo.isFluidDisplayItem(stack)) items.add(stack);
        }
        List<PacketStorageAction> packets;
        try {
            packets = BookmarkWithdrawal.requests(items, gui.inventorySlots.windowId);
        } catch (IllegalArgumentException error) {
            gui.mc.thePlayer.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.bookmark.too_large"));
            return;
        }
        for (PacketStorageAction packet : packets) NetworkHandler.INSTANCE.sendToServer(packet);
    }
}
