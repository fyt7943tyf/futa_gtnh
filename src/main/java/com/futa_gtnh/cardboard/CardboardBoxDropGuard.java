package com.futa_gtnh.cardboard;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 打包替换方块期间的「物品实体掉落抑制器」。
 *
 * <p>
 * 打包是 {@code world.setBlock(纸箱)} 直接顶掉原方块 —— 不走破坏流程，绝大多数
 * 方块不会掉东西。但个别模组的方块实体在 {@code invalidate} 时会主动往世界里吐
 * 内容物（Mekanism 的原版纸箱就遇到过，所以它也挂了同款监听）。这里用一个静态
 * 窗口标志：{@link CardboardBoxOps} 在 setBlock 前后开关，窗口内新加入世界的
 * 物品实体直接取消 —— 机器内容已经完整存在快照 NBT 里，重复掉落只会凭空刷物品。
 *
 * <p>
 * 窗口是「当前线程、顺序执行」的：1.7.10 的 setBlock 同步执行，不会有异步任务
 * 插进窗口里。挂在 Forge 总线上（实例注册，和
 * {@code StorageBusLeftClickHandler} 同款），由 {@code CommonProxy#registerCardboardBox} 注册。
 */
public class CardboardBoxDropGuard {

    /** 窗口打开时为 true。只有服务端打包路径会碰它。 */
    private static boolean monitoring;

    private static World activeWorld;

    /** 打开抑制窗口。 */
    static void begin(World world) {
        monitoring = true;
        activeWorld = world;
    }

    /** 关闭抑制窗口。必须和 {@link #begin} 成对出现（finally 里调）。 */
    static void end() {
        monitoring = false;
        activeWorld = null;
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!monitoring || event.world != activeWorld) return;
        if (event.entity instanceof EntityItem) event.setCanceled(true);
    }
}
