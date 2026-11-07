package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.me.GtswnQuantumCompat;
import com.futa_gtnh.me.PublicMeNetwork;

/**
 * Use GTSWN's own permission, quantization, registry and terminal-binding implementation.
 *
 * <p>
 * A click on a public access point / core port is replayed through GTSWN's own {@code onItemUseFirst} at the
 * public controller's position, so whatever GTSWN checks before touching a controller (terminal sync, monitoring
 * profile, per-network access rules in 2.x) still runs; only the click target is redirected.
 */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal", remap = false)
public abstract class MixinGtswnQuantumTerminal {

    @Shadow
    public abstract boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z,
        int side, float hitX, float hitY, float hitZ);

    @Shadow
    private static void clearAnchor(ItemStack stack) {
        throw new AssertionError();
    }

    @Shadow
    public static int[] getAnchor(ItemStack stack) {
        throw new AssertionError();
    }

    /** True while the replayed click is inside GTSWN's own handling, so it is not redirected a second time. */
    @Unique
    private boolean futa$replaying;

    @Inject(method = "onItemUseFirst", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void futa$bindPublicNetwork(ItemStack stack, EntityPlayer player, World world, int x, int y, int z,
        int side, float hitX, float hitY, float hitZ, CallbackInfoReturnable<Boolean> cir) {
        if (futa$replaying) return;
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
        World controllerWorld = controller.getWorldObj();
        futa$replaying = true;
        try {
            cir.setReturnValue(
                onItemUseFirst(
                    stack,
                    player,
                    controllerWorld,
                    controller.xCoord,
                    controller.yCoord,
                    controller.zCoord,
                    side,
                    hitX,
                    hitY,
                    hitZ));
        } finally {
            futa$replaying = false;
        }
    }
}
