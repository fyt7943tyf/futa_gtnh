package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.item.ItemLocatorWand;
import com.futa_gtnh.network.PacketLocatorResult;

/**
 * 客户端记住的寻物状态：选了什么、搜到哪了、目标在哪。
 *
 * <p>
 * 只用来说话和画画，不参与任何判定 —— 目标坐标是服务端算出来发过来的。
 * 光束渲染和界面都从这里读。
 *
 * <p>
 * 光束<b>只在手持魔杖时才画</b>：不这么做的话，换到别的物品之后那道光还在天上飘，
 * 既碍眼又没意义。判断放在渲染器里做。
 */
public final class LocatorState {

    private LocatorState() {}

    /** 比服务端的四个状态再多一个「什么都没选」。 */
    public static final byte STATE_NONE = -1;

    private static ItemStack target;
    private static byte state = STATE_NONE;
    private static float progress;
    private static int posX;
    private static int posY;
    private static int posZ;
    private static double distance = -1.0D;
    /**
     * 结果是在哪个维度算出来的。
     *
     * <p>
     * 搜索只在玩家当前维度做，但玩家完全可以在出结果之后走传送门 —— 那时候坐标还在，
     * 指的却是另一个世界的同一个数字。渲染器和传送按钮都靠这个字段判断结果还能不能用。
     */
    private static int resultDimension;

    // ==================================================================
    // 读写
    // ==================================================================

    public static ItemStack getTarget() {
        return target;
    }

    public static byte getState() {
        return state;
    }

    public static float getProgress() {
        return progress;
    }

    public static int getPosX() {
        return posX;
    }

    public static int getPosY() {
        return posY;
    }

    public static int getPosZ() {
        return posZ;
    }

    public static double getDistance() {
        return distance;
    }

    /** 结果所属维度。和当前维度不一致时，坐标和光束都不该再被信任。 */
    public static int getResultDimension() {
        return resultDimension;
    }

    /** 玩家选了新目标，等结果中。 */
    public static void setTarget(ItemStack stack) {
        target = stack;
        state = PacketLocatorResult.STATE_RUNNING;
        progress = 0.0F;
        distance = -1.0D;
    }

    /** 服务端回包。 */
    public static void applyResult(byte newState, float newProgress, int x, int y, int z, double newDistance) {
        // 界面已经清掉目标了（比如玩家关了界面又收到迟到的回包），忽略
        if (newState != PacketLocatorResult.STATE_CANCELLED && target == null) {
            return;
        }

        state = newState;
        progress = newProgress;
        if (newState == PacketLocatorResult.STATE_FOUND) {
            posX = x;
            posY = y;
            posZ = z;
            distance = newDistance;

            // 记下维度。包处理在主线程上跑，这里读 thePlayer 是安全的。
            Minecraft mc = Minecraft.getMinecraft();
            resultDimension = mc.thePlayer == null ? 0 : mc.thePlayer.dimension;
        } else if (newState == PacketLocatorResult.STATE_CANCELLED) {
            clear();
        }
    }

    public static void clear() {
        target = null;
        state = STATE_NONE;
        progress = 0.0F;
        distance = -1.0D;
    }

    /** @return 有没有值得画光束的目标坐标 */
    public static boolean hasBeam() {
        return state == PacketLocatorResult.STATE_FOUND && distance >= 0.0D;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * @return 玩家是不是正拿着寻物魔杖
     *
     *         <p>
     *         <b>只看主手。</b>副手（GTNH 的 Backhand 模组）也拿着魔杖时该不该画，
     *         属于模棱两可的事，索性不算 —— 免得玩家把魔杖放副手之后那道光
     *         莫名其妙一直在。
     */
    public static boolean isHoldingWand(EntityPlayer player) {
        if (player == null) return false;
        ItemStack held = player.getCurrentEquippedItem();
        return held != null && held.getItem() instanceof ItemLocatorWand;
    }
}
