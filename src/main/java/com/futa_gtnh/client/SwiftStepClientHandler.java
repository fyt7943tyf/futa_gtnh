package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemSwiftStep;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * 把「戴着迅步」这件事变成实际的飞行速度。
 *
 * <p>
 * <b>只有飞行速度在这里做，移动速度不在这里。</b>这两项在 1.7.10 里走的是
 * 完全不同的机制：
 *
 * <ul>
 * <li>移动速度是「移动速度属性」，服务端每 tick 写基值，用公开的
 * {@link net.minecraft.entity.ai.attributes.AttributeModifier} 就能改，
 * 而且是服务端权威 —— 见 {@code ItemSwiftStep.applyWalkSpeedModifier}。</li>
 * <li>飞行速度没有属性可用，就是 {@link PlayerCapabilities} 里的私有字段，
 * 而它的 setter 标着 {@code @SideOnly(Side.CLIENT)}：服务端那份类里根本没有这个方法，
 * 调了会 {@code NoSuchMethodError}。它本来就是纯客户端参数 ——
 * 在 {@code EntityPlayer.moveEntityWithHeading} 里被读出来赋给
 * {@code jumpMovementFactor}，那段代码跑在客户端。</li>
 * </ul>
 *
 * <p>
 * 顺带说一句为什么<b>不</b>用反射去写服务端那个私有字段：那样得把字段名
 * {@code "flySpeed"} 写死在代码里，而生产环境的 jar 是混淆过的，字段名会变成
 * SRG 名（{@code field_XXXXXX}），运行时直接抛 {@code NoSuchFieldException}。
 *
 * <p>
 * <b>不覆盖别人设的速度。</b>第一次接管时先把当前值备份下来，护符摘掉之后
 * 还原成备份值，而不是无脑恢复成原版的 0.05。否则万一还有别的模组也在动
 * {@code flySpeed}，我们就会在摘护符的瞬间把它的设置抹掉。
 */
public class SwiftStepClientHandler {

    private SwiftStepClientHandler() {}

    /** 接管前的飞行速度。null 表示当前没有接管。 */
    private static Float originalFlySpeed;

    /** 记着上次是给哪个玩家对象接管的，换人（重进世界 / 重生）就作废重来。 */
    private static EntityPlayer watchedPlayer;

    /**
     * 当前生效的飞行倍率是不是被「服务器安全上限」压过。
     *
     * <p>
     * 给界面用：玩家调了 20 倍却只跑出 18 倍的效果，得让他知道为什么，
     * 否则只会觉得「这东西坏了」。
     */
    private static boolean clampedForServer;

    public static void register() {
        // FML 自己的 TickEvent 走 FMLCommonHandler 那条总线，不是 Forge 总线
        FMLCommonHandler.instance()
            .bus()
            .register(new Listener());
    }

    /** @return 当前飞行倍率是不是被服务器安全上限压住了（界面据此提示玩家） */
    public static boolean isClampedForServer() {
        return clampedForServer;
    }

    /**
     * 判断浮动值是否「够不一样」。
     *
     * <p>
     * 不能直接 {@code !=}：float 每次乘除都会有末位误差，拿它当条件会导致
     * 每 tick 都判定成「变了」然后反复 set，白白制造无谓的写入。
     */
    private static boolean differs(float a, float b) {
        return Math.abs(a - b) > 1.0E-4F;
    }

    public static final class Listener {

        /** 上次报告过的状态串，用来避免每 tick 刷日志。 */
        private String lastReport;
        /** 「处理器活着」这条只打一次。 */
        private boolean aliveReported;

        /**
         * 两个阶段都要处理，<b>而且 START 才是关键的那个</b>。
         *
         * <p>
         * 客户端一 tick 的顺序是这样的（全部来自原版源码）：
         *
         * <pre>
         * 1. gameMode 阶段  Minecraft.runTick → PlayerControllerMP.updateController
         *                   → NetworkManager.processReceivedPackets()
         *                   ← S39 包在这里到达，把 flySpeed 覆盖成服务端那份（默认 0.05）
         * 2. level 阶段     theWorld.tick() → thePlayer.onUpdate()
         *    a. EntityPlayer:259  onPlayerPreTick   → PlayerTickEvent(START)
         *    b. EntityPlayer:327  super.onUpdate()  → onLivingUpdate() ★ 这里读 flySpeed 算移动 ★
         *    c. EntityPlayer:392  onPlayerPostTick  → PlayerTickEvent(END)
         * </pre>
         *
         * <p>
         * <b>原来只在 END 阶段设置，也就是在移动算完之后才写</b> —— 在只有本模组改
         * {@code flySpeed} 的环境里这没问题（上一 tick 写的值能活到下一 tick 的移动）。
         * 但整合包里 Draconic Evolution 的 {@code CustomArmorHandler.onPlayerTick}
         * 会在客户端把 {@code flySpeed} 重置回 0.05（没穿它的飞行护甲时也照做），
         * 还会让服务端回一个 S39 包；那个包正好在上面的第 1 步到达。
         * 于是每 tick 的移动读到的都是 0.05，<b>我们的倍率永远轮不到被使用</b>。
         *
         * <p>
         * 所以 START 阶段也要设一次：它在移动之前，设完本 tick 立刻生效。
         * END 那次留着是为了兜住「有模组在我们之后、下次移动之前又改掉」的情况。
         */
        @SubscribeEvent
        public void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.side != Side.CLIENT) return;

            EntityPlayer player = event.player;
            if (!(player instanceof EntityPlayerSP)) return;

            // 这条是排查「飞行速度没反应」的第一个岔路口：
            // 日志里没有它，说明这个处理器压根没跑起来，问题在网络事件/代理注册那一段，
            // 根本不用去查速度本身。
            if (!aliveReported) {
                aliveReported = true;
                FutaGtnhMod.LOG.info(
                    "迅步：客户端飞行速度处理器已启动（玩家类 {}）",
                    player.getClass()
                        .getSimpleName());
            }

            if (player != watchedPlayer) {
                // 换了玩家对象：上一次那份「原值」已经不属于这个对象了，丢掉
                watchedPlayer = player;
                originalFlySpeed = null;
                lastReport = null;
            }

            ItemStack charm = ItemSwiftStep.findEquipped(player);
            float multiplier = charm == null ? ItemSwiftStep.DEFAULT_MULTIPLIER
                : ItemSwiftStep.getFlightMultiplier(charm);

            // 多人服务器上把倍率压到服务端不会拉回的值。
            //
            // 这不是「保守起见」，而是超过那个值之后<b>一点加速都拿不到</b>：
            // NetHandlerPlayServer 每 tick 检查位移的平方和，超过 100（即 10 格/tick）
            // 就把你 setPlayerLocation 回原位。而那个检查带一个
            // 「isSinglePlayer 且你是房主」的豁免条件，所以单人存档完全不受影响。
            //
            // 压到安全值总比维持一个「每 tick 被拉回」的倍率强 ——
            // 18 倍能跑，20 倍等于没装。
            float safe = ItemSwiftStep.serverSafeFlyMultiplier();
            clampedForServer = false;
            if (multiplier > safe && !Minecraft.getMinecraft()
                .isSingleplayer()) {
                multiplier = safe;
                clampedForServer = true;
            }

            PlayerCapabilities capabilities = player.capabilities;

            if (multiplier > ItemSwiftStep.DEFAULT_MULTIPLIER) {
                if (originalFlySpeed == null) {
                    originalFlySpeed = Float.valueOf(capabilities.getFlySpeed());
                }
                float target = multiplier * ItemSwiftStep.VANILLA_FLY_SPEED;
                if (differs(capabilities.getFlySpeed(), target)) {
                    capabilities.setFlySpeed(target);
                }
            } else if (originalFlySpeed != null) {
                capabilities.setFlySpeed(originalFlySpeed.floatValue());
                originalFlySpeed = null;
                clampedForServer = false;
            }

            report(player, charm, capabilities);
        }

        /**
         * 状态变了才打一行日志。
         *
         * <p>
         * 「飞行速度没反应」有两条完全不同的岔路，光看游戏画面分不出来：
         *
         * <ul>
         * <li><b>没装备 / 倍率还是 1.0</b> —— 物品没戴上，或者界面里的值没写进 NBT；</li>
         * <li><b>装备了、倍率也对，但 flySpeed 没跟着变</b> —— 那才是速度本身的问题。</li>
         * </ul>
         *
         * 这一行把「饰品找没找到、NBT 里是什么、capabilities 现在是多少」一次摊开，
         * 省掉来回猜。只在变化时打，一次会话最多几行。
         *
         * <p>
         * 顺带也把「戴着迅步却一点速度都没加」这种状态暴露出来 —— 那正是
         * 玩家会报「无效」的情形。
         */
        private void report(EntityPlayer player, ItemStack charm, PlayerCapabilities capabilities) {
            String line;
            if (charm == null) {
                line = "未装备（capabilities.flySpeed=" + capabilities.getFlySpeed() + "）";
            } else {
                line = "已装备：飞行 x" + ItemSwiftStep.getFlightMultiplier(charm)
                    + " / 移动 x"
                    + ItemSwiftStep.getWalkMultiplier(charm)
                    + "，NBT="
                    + (charm.getTagCompound() == null ? "无" : "有")
                    + "，capabilities.flySpeed="
                    + capabilities.getFlySpeed()
                    + "，isFlying="
                    + capabilities.isFlying
                    + (clampedForServer ? "，[已按服务器上限压低]" : "");
            }

            if (!line.equals(lastReport)) {
                lastReport = line;
                FutaGtnhMod.LOG.info("迅步：{}", line);
            }
        }
    }
}
