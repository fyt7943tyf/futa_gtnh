/**
 * Mixin 包占位文件 —— 目前这里没有任何 Mixin 类，Mixin 基础配置见
 * {@code src/main/resources/mixins.futa_gtnh.json}。
 *
 * 要开始写真正的 Mixin 时：
 * 1. 在本包下新建你的 Mixin 类，例如：
 * {@code @Mixin(EntityPlayer.class)}
 * {@code public abstract class MixinEntityPlayer { ... }}
 * 2. 把类名加进 {@code src/main/resources/mixins.futa_gtnh.json} 的 "mixins" 数组，
 * 客户端专用的加进 "client"。
 *
 * GTNH 更推荐用 GTNHLib 的 IMixins API 来注册（写法更简洁统一），
 * 参考 https://github.com/GTNewHorizons/ExampleMod1.7.10#mixins
 *
 * 注意：一个 Mixin 类不能没有目标，所以这里放的是 package-info 而不是空的 Mixin 类。
 */
package com.futa_gtnh.mixins;
