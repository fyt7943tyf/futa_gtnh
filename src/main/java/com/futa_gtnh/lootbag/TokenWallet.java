package com.futa_gtnh.lootbag;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;

import com.cubefury.vendingmachine.blocks.gui.WalletMode;
import com.cubefury.vendingmachine.trade.CurrencyType;
import com.cubefury.vendingmachine.trade.TradeManager;
import com.cubefury.vendingmachine.util.Wallet;
import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.Loader;

/**
 * VendingMachine 联动的「技术员代币」钱包桥接 —— 本工程里唯一直接引用
 * {@code com.cubefury.vendingmachine.*} 的类。
 *
 * <p>
 * 代币余额存在 gtnhlib 的团队数据里（{@code VMTeamData}，团队/个人两种钱包），
 * 由 {@code TradeManager.getWallet(uuid, mode)} 取出。这里完全复刻贩卖机本体的
 * 扣款路径：先查余额、够数才 {@code addCount(-cost)}，改完调
 * {@code saveTeamData} 让团队数据落盘。
 *
 * <p>
 * 缺席/旧版兜底：{@link #isAvailable()} 只看 modid；真正碰钱包的方法再套一层
 * try/catch —— VendingMachine 0.4.45 之前是另一套没有 {@code Wallet} 的 API，
 * 旧版在场时这里会把异常吞掉、按「不可用」处理并记一次日志，抽奖机只是不能
 * 花代币重置次数，其它功能不受影响。
 */
public final class TokenWallet {

    private TokenWallet() {}

    private static boolean warnedBroken;

    /** @return VendingMachine 是否在场（不触碰它的类，加载安全） */
    public static boolean isAvailable() {
        return Loader.isModLoaded("vendingmachine");
    }

    /**
     * @return 玩家当前钱包里的技术员代币余额；拿不到钱包（没有团队等罕见情况）按 0 计
     */
    public static int getBalance(EntityPlayer player) {
        if (player == null) return 0;
        try {
            Wallet wallet = wallet(player);
            return wallet == null ? 0 : wallet.getCount(CurrencyType.TECHNICIAN);
        } catch (Throwable t) {
            logBroken(t);
            return 0;
        }
    }

    /**
     * 扣款。
     *
     * @return true = 已扣掉 amount 并落盘；false = 余额不足 / 钱包不可用
     */
    public static boolean tryCharge(EntityPlayer player, int amount) {
        if (player == null || amount <= 0) return amount <= 0;
        try {
            Wallet wallet = wallet(player);
            if (wallet == null) return false;
            if (wallet.getCount(CurrencyType.TECHNICIAN) < amount) return false;

            wallet.addCount(CurrencyType.TECHNICIAN, -amount);

            UUID uuid = player.getUniqueID();
            TradeManager.INSTANCE.saveTeamData(uuid);
            return true;
        } catch (Throwable t) {
            logBroken(t);
            return false;
        }
    }

    private static Wallet wallet(EntityPlayer player) {
        WalletMode mode = Config.lootMachineTeamWallet ? WalletMode.TEAM : WalletMode.PERSONAL;
        return TradeManager.INSTANCE.getWallet(player.getUniqueID(), mode);
    }

    private static synchronized void logBroken(Throwable t) {
        if (warnedBroken) return;
        warnedBroken = true;
        FutaGtnhMod.LOG.error("技术员代币钱包访问失败（VendingMachine 版本过旧或缺依赖？），抽奖机的代币功能将不可用", t);
    }
}
