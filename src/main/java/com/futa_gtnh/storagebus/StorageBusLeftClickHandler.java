package com.futa_gtnh.storagebus;

import net.minecraft.block.Block;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 存储总线左键逐面拆除。
 *
 * <p>
 * 光靠 {@code Block#onBlockClicked} 不够：创造模式会直接走 {@code tryHarvestBlock}，
 * 而且生存模式继续按住左键仍可能把整块挖掉。这里在服务端左键事件里先把命中的那一面
 * 拆掉并取消事件，同时 {@link BlockStorageBus#getBlockHardness} 返回 -1，整块无法被挖掉。
 */
public final class StorageBusLeftClickHandler {

    @SubscribeEvent
    public void onLeftClick(PlayerInteractEvent event) {
        if (event == null || event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;
        if (event.world == null || event.world.isRemote || event.entityPlayer == null) return;

        Block block = event.world.getBlock(event.x, event.y, event.z);
        if (!(block instanceof BlockStorageBus)) return;

        // 先拆一面；即使没命中（比如点到板边缘的空隙）也取消整块破坏。
        BlockStorageBus.removeFaceAt(event.world, event.x, event.y, event.z, event.entityPlayer);
        event.setCanceled(true);
    }
}
