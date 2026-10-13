package com.futa_gtnh.aewireless;

import java.util.UUID;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;

/**
 * 「可用节点隔离」的判定：GTNHLib 的队伍系统。
 *
 * <p>
 * GTNH 没有新版那种 FTB Teams；生态收敛点是 GTNHLib 的 {@link TeamManager}
 * （GT 掉电同步、GTSWN 的设备扫描都在用它），玩家用 /team 命令组队。
 * 判定规则：
 *
 * <ul>
 * <li>同一玩家（UUID 相等）永远互通 —— 单人自己用不需要组队；
 * <li>否则两边都必须有队伍，且是<b>同一个</b>队伍实例。
 * <li>任一侧没有所有者（老方块 / OP 手放）时不互通，宁可严一点。
 * </ul>
 */
final class AeSignalTeam {

    private AeSignalTeam() {}

    static boolean sameTeam(UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        Team teamA = TeamManager.getTeamByPlayer(a);
        Team teamB = TeamManager.getTeamByPlayer(b);
        return teamA != null && teamA == teamB;
    }
}
