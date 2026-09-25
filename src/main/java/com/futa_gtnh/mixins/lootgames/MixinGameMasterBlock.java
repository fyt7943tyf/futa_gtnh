package com.futa_gtnh.mixins.lootgames;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ru.timeconqueror.lootgames.api.block.GameMasterBlock;
import ru.timeconqueror.lootgames.api.block.tile.GameMasterTile;
import ru.timeconqueror.lootgames.api.minigame.LootGame;

/**
 * 潜行右击小游戏主方块 = 自动完成当前进度（见 {@link LootgameAutoComplete}）。
 *
 * <p>
 * {@code GameMasterBlock} 是三种游戏（扫雷/光之游戏/数独）棋盘西北角那块
 * 「主方块」的公共基类，游戏进行中右击它本来只会把坐标换算成棋盘外的空位、
 * 什么都不发生 —— 正好把这个「空闲入口」征用为跳过关卡的快捷键，完全不挤占
 * 原版操作。三个子类方块（{@code MSGameMasterBlock} 等）都没有覆写
 * {@code onBlockActivated}，一个注入点全覆盖。
 *
 * <p>
 * 只在<b>服务端 + 潜行</b>时拦截：客户端不潜行、或服务端没装本 mod 时，
 * 原逻辑原样执行。{@code PuzzleMasterBlock}（未开局的地牢核心）不继承这个类，
 * 不受影响。
 */
@Mixin(value = GameMasterBlock.class)
public abstract class MixinGameMasterBlock {

    @Inject(method = "onBlockActivated", at = @At("HEAD"), cancellable = true, remap = false)
    private void futa$sneakCompleteCurrentLevel(World world, int x, int y, int z, EntityPlayer player, int side,
        float subX, float subY, float subZ, CallbackInfoReturnable<Boolean> cir) {
        if (world.isRemote || !player.isSneaking()) return;
        if (!(player instanceof EntityPlayerMP)) return;

        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof GameMasterTile)) return;

        LootGame<?, ?> game = ((GameMasterTile<?>) te).getGame();
        if (game == null) return;

        if (LootgameAutoComplete.tryComplete(game, (EntityPlayerMP) player)) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
