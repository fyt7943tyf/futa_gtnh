package com.futa_gtnh.mixins;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.futa_gtnh.rts.client.RtsClientState;

/**
 * 俯瞰模式下隐藏第一人称手臂的<b>双保险</b>（事件路径见
 * {@code com.futa_gtnh.rts.client.RtsHandHider}）。
 *
 * <p>
 * GTNH Forge 的 {@code RenderHandEvent} 取消在字节码层面确实能跳过
 * {@code renderHand}（全工程唯一调用点），但 1.3.1 真机测试手臂仍然显示 ——
 * 静态排查（事件类、总线、注册时序、其它 mod 的 mixin 冲突面）都找不到原因，
 * 怀疑是整合包里某个 mod 干扰了事件链。这里在 {@code renderHand} 的 HEAD
 * 再加一道确定性闸门：俯瞰会话激活时直接 cancel，不依赖任何事件分发。
 *
 * <p>
 * 已知共存性（1.3.1 排查结论）：Hodgepodge 的 F1ShowHand 在方法<b>内部</b>做
 * FIELD 注入、Angelica 的 shaders 在方法内部做 FIELD Redirect —— 都不在 HEAD，
 * 与本注入天然兼容。原版目标，注解保持默认 remap（refmap 翻译 renderHand）。
 * 只注册在 mixins.json 的 client 段（EntityRenderer 是客户端类）。
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void futa$rtsHideHand(float partialTicks, int renderPass, CallbackInfo ci) {
        if (RtsClientState.isActive()) {
            ci.cancel();
        }
    }
}
