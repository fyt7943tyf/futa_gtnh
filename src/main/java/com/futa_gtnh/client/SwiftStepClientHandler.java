package com.futa_gtnh.client;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.ItemStack;

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

    public static void register() {
        // FML 自己的 TickEvent 走 FMLCommonHandler 那条总线，不是 Forge 总线
        FMLCommonHandler.instance()
            .bus()
            .register(new Listener());
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

        @SubscribeEvent
        public void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.side != Side.CLIENT || event.phase != TickEvent.Phase.END) return;

            EntityPlayer player = event.player;
            if (!(player instanceof EntityPlayerSP)) return;

            if (player != watchedPlayer) {
                // 换了玩家对象：上一次那份「原值」已经不属于这个对象了，丢掉
                watchedPlayer = player;
                originalFlySpeed = null;
            }

            ItemStack charm = ItemSwiftStep.findEquipped(player);
            float multiplier = charm == null ? ItemSwiftStep.DEFAULT_MULTIPLIER
                : ItemSwiftStep.getFlightMultiplier(charm);

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
            }
        }
    }
}
