package com.futa_gtnh.mixins;

import net.minecraft.block.Block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.futa_gtnh.common.ProspectorChineseNames;

import detrav.net.ProspectingPacket;

/** Replace the name before GT groups ores and writes its unchanged scanner packet. */
@Mixin(value = ProspectingPacket.class, remap = false)
public abstract class MixinProspectingPacket {

    @ModifyVariable(method = "addBlock", at = @At("STORE"), ordinal = 0, remap = false, require = 1)
    private String futa$chineseOreName(String name, int x, int y, int z, Block block, int metadata) {
        return ProspectorChineseNames.getOreName(block, metadata, name);
    }
}
