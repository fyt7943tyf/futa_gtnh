package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.rts.server.RtsRemoteGuiRegistry;

/**
 * 放宽俯瞰远程 GUI 的每 tick 距离校验（EntityPlayerMP.onUpdate 一侧）。
 *
 * <p>
 * 原版 1.7.10 在 {@code EntityPlayerMP.onUpdate} 里有
 * {@code openContainer.canInteractWith(this)} 检查：玩家离容器太远就关 GUI
 * （EntityPlayerMP.java:201）。远程打开的箱子/GT 机器界面会被它立刻关掉，
 * 所以对 {@link RtsRemoteGuiRegistry} 登记过的容器放行 —— 没登记的
 * （玩家自己走到旁边开的）行为不变。
 *
 * <p>
 * 目标是原版类，注解保持默认 remap（refmap 负责把 onUpdate/canInteractWith
 * 翻回 SRG 名）；{@code MixinEntityPlayer} 是同一检查在父类 onUpdate 里的
 * 另一处调用点，两边都要盖。
 */
@Mixin(EntityPlayerMP.class)
public class MixinEntityPlayerMP {

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
