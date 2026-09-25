package com.futa_gtnh.client;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
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
 * 光束只在手持或佩戴魔杖时画；不然换下魔杖后光线还会留在天上。
 */
public final class LocatorState {

    private LocatorState() {}

    /** 比服务端的状态再多一个「什么都没选」。 */
    public static final byte STATE_NONE = -1;

    /** 方块模式下的原始目标；矿脉模式下为 null。 */
    private static ItemStack targetStack;
    /** 矿脉模式下的内部名；方块模式下为 null。 */
    private static String veinKey;
    /** 当前目标是箱子库存中的物品，而不是世界方块。 */
    private static boolean inventoryTarget;

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

    /** Baubles 是可选依赖；用反射探测，避免未安装时客户端加载寻物状态就崩溃。 */
    private static Method getBaublesInventory;
    private static boolean baublesApiResolved;
    private static EntityPlayer accessoryCheckPlayer;
    private static int accessoryCheckTick = Integer.MIN_VALUE;
    private static boolean accessoryHasWand;

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
        if (inventoryTarget || targetStack == null || stack == null) return false;
        return targetStack.getItem() == stack.getItem() && targetStack.getItemDamage() == stack.getItemDamage();
    }

    /** @return 当前选的是不是这个库存物品 */
    public static boolean isItemSelected(ItemStack stack) {
        if (!inventoryTarget || targetStack == null || stack == null) return false;
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
        inventoryTarget = false;
        icon = stack;
        title = stack == null ? "" : stack.getDisplayName();
        subtitle = "";
        state = PacketLocatorResult.STATE_RUNNING;
        progress = 0.0F;
        distance = -1.0D;
    }

    /** 玩家选择一种要在箱子库存里搜索的物品。 */
    public static void setItemTarget(ItemStack stack) {
        targetStack = stack;
        veinKey = null;
        inventoryTarget = true;
        icon = stack;
        title = stack == null ? "" : stack.getDisplayName();
        subtitle = net.minecraft.util.StatCollector.translateToLocal("futa_gtnh.gui.locator.items.target");
        state = PacketLocatorResult.STATE_RUNNING;
        progress = 0.0F;
        distance = -1.0D;
    }

    /** 玩家选了一条矿脉，等结果中。 */
    public static void setVeinTarget(String key, String veinTitle, String materials, ItemStack veinIcon) {
        targetStack = null;
        veinKey = key;
        inventoryTarget = false;
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
        } else if (newState == PacketLocatorResult.STATE_ARRIVED) {
            // 传送成功，坐标和距离<b>刻意不动</b>：这个包只是「按钮作废、追踪保留」的通知，
            // 目标还是刚才那个目标。服务端也没存距离，照抄才是对的。
        } else if (newState == PacketLocatorResult.STATE_CANCELLED) {
            clear();
        }
    }

    public static void clear() {
        targetStack = null;
        veinKey = null;
        inventoryTarget = false;
        icon = null;
        title = "";
        subtitle = "";
        state = STATE_NONE;
        progress = 0.0F;
        distance = -1.0D;
    }

    /** @return 有没有值得画光束的目标坐标 */
    public static boolean hasBeam() {
        // 「已传送」也要画：玩家落地之后正需要那道光告诉他往哪挖 ——
        // 这正是以前把结果一传送就清掉时最要命的地方
        return (state == PacketLocatorResult.STATE_FOUND || state == PacketLocatorResult.STATE_ARRIVED)
            && distance >= 0.0D;
    }

    /**
     * @return 这次结果是不是已经用掉传送了
     *
     *         <p>
     *         界面据此把传送按钮换成「已传送」并禁用。注意<b>结果本身还在</b>：
     *         坐标、距离、光束都照旧，玩家可以自己走过去或者接着挖，
     *         想再传送就重新选一次目标（重新搜索会清掉这个标记）。
     */
    public static boolean isTeleported() {
        return state == PacketLocatorResult.STATE_ARRIVED;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** @return 玩家是否手持或佩戴寻物魔杖。 */
    public static boolean isHoldingWand(EntityPlayer player) {
        if (player == null) return false;
        ItemStack held = player.getCurrentEquippedItem();
        if (held != null && held.getItem() instanceof ItemLocatorWand) return true;

        // 渲染器每帧都会问一次；饰品栏每个玩家 tick 查一次就够了。
        if (player != accessoryCheckPlayer || player.ticksExisted != accessoryCheckTick) {
            accessoryCheckPlayer = player;
            accessoryCheckTick = player.ticksExisted;
            accessoryHasWand = hasWandInBaubles(player);
        }
        return accessoryHasWand;
    }

    private static boolean hasWandInBaubles(EntityPlayer player) {
        Method getter = getBaublesInventoryMethod();
        if (getter == null) return false;

        try {
            Object result = getter.invoke(null, player);
            if (!(result instanceof IInventory)) return false;

            IInventory baubles = (IInventory) result;
            for (int slot = 0; slot < baubles.getSizeInventory(); slot++) {
                ItemStack stack = baubles.getStackInSlot(slot);
                if (stack != null && stack.getItem() instanceof ItemLocatorWand) return true;
            }
        } catch (Throwable ignored) {
            // 饰品 API 在不同 Baubles 分支中的异常不应影响渲染或游戏运行。
        }
        return false;
    }

    private static Method getBaublesInventoryMethod() {
        if (baublesApiResolved) return getBaublesInventory;
        baublesApiResolved = true;

        try {
            Class<?> api = Class.forName("baubles.api.BaublesApi");
            getBaublesInventory = api.getMethod("getBaubles", EntityPlayer.class);
        } catch (Throwable ignored) {
            // Baubles 是可选依赖；没有它时只有手持模式。
        }
        return getBaublesInventory;
    }
}
