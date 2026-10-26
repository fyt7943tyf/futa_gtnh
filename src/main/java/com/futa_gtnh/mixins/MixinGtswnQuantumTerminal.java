package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.me.GtswnQuantumCompat;
import com.futa_gtnh.me.PublicMeNetwork;

import appeng.tile.networking.TileController;

/** Use GTSWN 1.8.8's own permission, quantization, registry and terminal-binding implementation. */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal", remap = false)
public abstract class MixinGtswnQuantumTerminal {

    @Shadow
    private boolean handleControllerClick(ItemStack stack, EntityPlayer player, World world, TileController controller,
        int x, int y, int z) {
        throw new AssertionError();
    }

    @Shadow
    private static void clearAnchor(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow
    public static int[] getAnchor(ItemStack stack) {
        throw new AssertionError();
    }

    @Inject(method = "onItemUseFirst", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void futa$bindPublicNetwork(ItemStack stack, EntityPlayer player, World world, int x, int y, int z,
        int side, float hitX, float hitY, float hitZ, CallbackInfoReturnable<Boolean> cir) {
        TileEntity target = world.getTileEntity(x, y, z);
        if (!GtswnQuantumCompat.isPublicTarget(target)) return;
        // Client true would suppress C08. Let the original server-authoritative interaction packet through.
        if (world.isRemote) {
            cir.setReturnValue(false);
            return;
        }
        if (player.isSneaking()) {
            int[] anchor = getAnchor(stack);
            if (anchor != null && PublicMeNetwork.isControllerLocation(anchor[0], anchor[1], anchor[2], anchor[3])) {
                clearAnchor(stack);
                player.addChatMessage(new ChatComponentTranslation("futa_gtnh.quantum.unbound"));
            } else player.addChatMessage(new ChatComponentTranslation("futa_gtnh.quantum.managed"));
            cir.setReturnValue(true);
            return;
        }
        TileEntity controller = PublicMeNetwork.controller();
        if (controller == null || !GtswnQuantumCompat.canBind(target)) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.quantum.offline"));
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(
            handleControllerClick(
                stack,
                player,
                controller.getWorldObj(),
                (TileController) controller,
                controller.xCoord,
                controller.yCoord,
                controller.zCoord));
    }
}
