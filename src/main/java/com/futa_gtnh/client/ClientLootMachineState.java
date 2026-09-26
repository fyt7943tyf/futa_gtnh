package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.network.PacketLootMachineResult;

/**
 * 抽奖机界面的客户端镜像：只存「怎么画」，不存任何判定逻辑。
 * 服务端的 {@code PacketLootMachineResult} 是唯一写入口；
 * 「当前看哪一轮」是纯视图状态，重开界面默认落在最近一轮。
 */
public final class ClientLootMachineState {

    private ClientLootMachineState() {}

    private static final List<PacketLootMachineResult.RoundView> rounds = new ArrayList<>();
    private static volatile int rollsMax = 3;
    private static volatile int selectedRound;
    private static volatile int tokenBalance;
    private static volatile boolean tokenAvailable;

    public static void apply(int rollsMax, List<PacketLootMachineResult.RoundView> newRounds, int tokenBalance,
        boolean tokenAvailable) {
        rounds.clear();
        if (newRounds != null) rounds.addAll(newRounds);
        ClientLootMachineState.rollsMax = Math.max(1, rollsMax);
        // 默认看最近一轮；同步包不会动玩家手动切换的视图（除非轮次变少了）
        if (selectedRound >= rounds.size()) {
            selectedRound = Math.max(0, rounds.size() - 1);
        }
        ClientLootMachineState.tokenBalance = tokenBalance;
        ClientLootMachineState.tokenAvailable = tokenAvailable;
    }

    public static int getRollsUsed() {
        return rounds.size();
    }

    public static int getRollsMax() {
        return rollsMax;
    }

    public static boolean hasResult() {
        return !rounds.isEmpty();
    }

    public static int getSelectedRound() {
        return selectedRound;
    }

    public static void setSelectedRound(int index) {
        if (index >= 0 && index < rounds.size()) {
            selectedRound = index;
        }
    }

    /** @return 当前选中轮的出货堆；无历史或下标越界时返回空数组 */
    public static ItemStack[] getSelectedStacks() {
        if (selectedRound < 0 || selectedRound >= rounds.size()) return new ItemStack[0];
        ItemStack[] stacks = rounds.get(selectedRound).stacks;
        return stacks == null ? new ItemStack[0] : stacks;
    }

    /** @return 当前选中轮的掉率数组（与 {@link #getSelectedStacks()} 一一对应） */
    public static double[] getSelectedChances() {
        if (selectedRound < 0 || selectedRound >= rounds.size()) return new double[0];
        double[] chances = rounds.get(selectedRound).chances;
        return chances == null ? new double[0] : chances;
    }

    public static int getTokenBalance() {
        return tokenBalance;
    }

    public static boolean isTokenAvailable() {
        return tokenAvailable;
    }
}
