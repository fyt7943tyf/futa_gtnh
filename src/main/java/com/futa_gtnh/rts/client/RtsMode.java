package com.futa_gtnh.rts.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * 俯瞰模式的操作模式。
 *
 * <p>
 * 三种模式<b>真实路由</b>鼠标行为（1.3.0 里模式只是 HUD 文字，不改变点击
 * 行为——上机测试反馈「切换有 BUG」的主因之一）：
 * <ul>
 * <li>INTERACT 互动：右键 = 互动/使用/开 GUI，左键 = 破坏；</li>
 * <li>BUILD 建造：右键 = 放置手中方块，左键 = 破坏，回车 = 形状建造；</li>
 * <li>DESTROY 破坏：左/右键 = 破坏，回车 = 区域破坏。</li>
 * </ul>
 * 形状选点（Ctrl+左键）在所有模式下都可用；回车提交的含义由模式决定。
 */
public enum RtsMode {

    INTERACT,
    BUILD,
    DESTROY;

    /** 语言键：模式显示名。 */
    public String langKey() {
        return "futa_gtnh.rts.mode." + name().toLowerCase();
    }

    /** 该模式下「右键」的动词（HUD 状态行提示，对齐 RTSBuilding 的 RMB: place）。 */
    public String rmbLangKey() {
        switch (this) {
            case BUILD:
                return "futa_gtnh.rts.rmb.place";
            case DESTROY:
                return "futa_gtnh.rts.rmb.break";
            case INTERACT:
            default:
                return "futa_gtnh.rts.rmb.interact";
        }
    }

    /**
     * 该模式下玩家手里的物品（顶栏「物品」栏显示用）。INTERACT/DESTROY 模式
     * 不强调手持物品，返回 null 表示不显示。
     */
    public static ItemStack showHeldItem(RtsMode mode, EntityPlayer player) {
        if (mode != BUILD || player == null) return null;
        return player.getCurrentEquippedItem();
    }
}
