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
 * 目标有两种：一个<b>方块</b>（{@link #setBlockTarget}）或者一条<b>矿脉</b>
 * （{@link #setVeinTarget}）。两者的共同点是「界面要画一个图标、写一个标题」，
 * 所以这里统一存图标 + 标题 + 副标题，另外把原始目标留着供界面判断
 * 「这一格是不是当前选中的那个」。
 *
 * <p>
 * 光束<b>只在手持魔杖时才画</b>：不这么做的话，换到别的物品之后那道光还在天上飘，
 * 既碍眼又没意义。判断放在渲染器里做。
 */
public final class LocatorState {

    private LocatorState() {}

    /** 比服务端的四个状态再多一个「什么都没选」。 */
    public static final byte STATE_NONE = -1;

    /** 方块模式下的原始目标；矿脉模式下为 null。 */
    private static ItemStack targetStack;
    /** 矿脉模式下的内部名；方块模式下为 null。 */
    private static String veinKey;

    /** 界面显示用的图标、标题、副标题。两种模式共用。 */
    private static ItemStack icon;
    private static String title = "";
    private static String subtitle = "";

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

    public static ItemStack getIcon() {
        return icon;
    }

    public static String getTitle() {
        return title;
    }

    /** 矿脉模式下是四个材料的列表；方块模式下是空串。 */
    public static String getSubtitle() {
        return subtitle;
    }

    /** @return 当前选的是不是这条矿脉 */
    public static boolean isVeinSelected(String key) {
        return veinKey != null && veinKey.equals(key);
    }

    /** @return 当前选的是不是这个方块 */
    public static boolean isBlockSelected(ItemStack stack) {
        if (targetStack == null || stack == null) return false;
        return targetStack.getItem() == stack.getItem() && targetStack.getItemDamage() == stack.getItemDamage();
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

    /** 玩家选了一个方块，等结果中。 */
    public static void setBlockTarget(ItemStack stack) {
        targetStack = stack;
        veinKey = null;
        icon = stack;
        title = stack == null ? "" : stack.getDisplayName();
        subtitle = "";
        state = PacketLocatorResult.STATE_RUNNING;
        progress = 0.0F;
        distance = -1.0D;
    }

    /** 玩家选了一条矿脉，等结果中。 */
    public static void setVeinTarget(String key, String veinTitle, String materials, ItemStack veinIcon) {
        targetStack = null;
        veinKey = key;
        icon = veinIcon;
        title = veinTitle == null ? "" : veinTitle;
        subtitle = materials == null ? "" : materials;
        state = PacketLocatorResult.STATE_RUNNING;
        progress = 0.0F;
        distance = -1.0D;
    }

    /** 服务端回包。 */
    public static void applyResult(byte newState, float newProgress, int x, int y, int z, double newDistance) {
        // 界面已经清掉目标了（比如玩家关了界面又收到迟到的回包），忽略
        if (newState != PacketLocatorResult.STATE_CANCELLED && targetStack == null && veinKey == null) {
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
        targetStack = null;
        veinKey = null;
        icon = null;
        title = "";
        subtitle = "";
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
