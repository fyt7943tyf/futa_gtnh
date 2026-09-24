package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.rts.server.RtsRemoteGuiRegistry;

/**
 * {@link MixinEntityPlayerMP} 的另一半：同一个每 tick 容器距离校验在
 * {@code EntityPlayer.onUpdate} 里还有一处（EntityPlayer.java:226，
 * EntityPlayerMP.onUpdate 会 super 到这里再跑一遍）。只盖子类的话，
 * 父类这处照样会把远程 GUI 关掉。
 */
@Mixin(EntityPlayer.class)
public class MixinEntityPlayer {

    @Redirect(
        method = "onUpdate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/inventory/Container;canInteractWith(Lnet/minecraft/entity/player/EntityPlayer;)Z"))
    private boolean futa$rtsRelaxRemoteContainerCheck(Container container, EntityPlayer player) {
        if (player instanceof EntityPlayerMP && RtsRemoteGuiRegistry.shouldRelax((EntityPlayerMP) player, container)) {
            return true;
        }
        return container.canInteractWith(player);
    }
}
