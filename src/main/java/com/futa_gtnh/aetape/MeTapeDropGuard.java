package com.futa_gtnh.aetape;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 封装替换/移除设备期间的「物品实体掉落抑制器」（与
 * {@code com.futa_gtnh.cardboard.CardboardBoxDropGuard} 同一套机制）。
 *
 * <p>
 * <b>为什么必须有：</b>{@code world.setBlockToAir} / 总线清空会触发 AE2 的
 * {@code AEBaseTileBlock.breakBlock}，它会把方块实体库存（驱动器里的元件、
 * 接口里的样板）原样掉落 —— 而这些数据已经完整进了封装物品的 NBT，
 * 掉出来就是复制。窗口内新加入世界的物品实体直接取消。
 *
 * <p>
 * 窗口是「当前线程、顺序执行」的：setBlock 同步执行，异步任务插不进来。
 * 挂 Forge 总线（实例注册），由 {@code CommonProxy#registerMePackingTape} 注册。
 */
public class MeTapeDropGuard {

    /** 窗口打开时为 true。只有服务端封装路径会碰它。 */
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
