package com.futa_gtnh.client;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.PlayerCapabilities;

import com.futa_gtnh.item.ItemFlightCharm;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * 把「戴着飞行护符」这件事变成实际的速度。
 *
 * <p>
 * <b>整件事都跑在客户端，这是被 API 逼出来的，不是偷懒。</b>
 * 1.7.10 的 {@link PlayerCapabilities#setFlySpeed(float)} 标着
 * {@code @SideOnly(Side.CLIENT)}：服务端那份类里根本没有这个方法，调了会
 * {@code NoSuchMethodError}。而飞行速度本来就是个纯客户端参数 ——
 * 它在 {@code EntityPlayer.moveEntityWithHeading} 里被读出来赋给
 * {@code jumpMovementFactor}，那段代码跑在客户端；服务端也无从验证玩家飞多快。
 *
 * <p>
 * 倍率本身仍然存在物品 NBT 里、由服务端写入，客户端只是读它并应用。
 *
 * <p>
 * <b>不覆盖别人设的速度。</b>第一次接管时先把当前值备份下来，护符摘掉之后
 * 还原成备份值，而不是无脑恢复成原版的 0.05。否则万一还有别的模组也在动
 * {@code flySpeed}，我们就会在摘护符的瞬间把它的设置抹掉。
 */
public class FlightCharmHandler {

    private FlightCharmHandler() {}

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

            float multiplier = ItemFlightCharm.findEquippedMultiplier(player);
            PlayerCapabilities capabilities = player.capabilities;

            if (multiplier > 0.0F) {
                if (originalFlySpeed == null) {
                    originalFlySpeed = Float.valueOf(capabilities.getFlySpeed());
                }
                float target = multiplier * ItemFlightCharm.VANILLA_FLY_SPEED;
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
