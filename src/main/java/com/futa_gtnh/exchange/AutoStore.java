package com.futa_gtnh.exchange;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 「拾取的东西是否自动进共享背包」这个开关的每玩家状态。
 *
 * <p>
 * 存在玩家自己的 {@code ForgeData} 里，更准确地说存在
 * {@link EntityPlayer#PERSISTED_NBT_TAG} 这个子标签下。
 *
 * <p>
 * 为什么是它：
 * <ul>
 * <li>Forge 的 {@code Entity.getEntityData()} 会被写进实体的存档数据
 * （{@code Entity.writeToNBT} 里的 {@code "ForgeData"} 标签），跟着玩家
 * {@code .dat} 一起存盘；</li>
 * <li>而 {@code PlayerPersisted} 这个子标签是官方留给「跨死亡也要保留」的玩家数据的位置 ——
 * 原版 {@code EntityPlayer.clonePlayer} 在重生时会专门把它从旧玩家复制到新玩家。
 * 直接往 {@code getEntityData()} 根上写的话，死一次就没了。</li>
 * </ul>
 *
 * <p>
 * 另一条佐证：{@code ForgeData} 和 {@code extendedProperties}（Forge 官方的
 * {@code IExtendedEntityProperties} 每玩家数据 API）是在<b>同一个方法</b>里写入的。
 * 如果那个方法对玩家不被调用，官方那套 API 就是坏的 —— 所以它一定被调用。
 *
 * <p>
 * 状态是<b>每玩家</b>的：这是个人偏好，不该由别人替你决定。
 */
public final class AutoStore {

    private AutoStore() {}

    /** 存进 {@code PlayerPersisted} 里的键名。带模组前缀避免和别的模组撞。 */
    private static final String KEY = "futa_gtnh.auto_store";

    public static boolean isEnabled(EntityPlayer player) {
        if (player == null) return false;
        return persistedTag(player).getBoolean(KEY);
    }

    public static void setEnabled(EntityPlayer player, boolean enabled) {
        if (player == null) return;
        persistedTag(player).setBoolean(KEY, enabled);
    }

    /**
     * @return 该玩家「跨死亡保留」的那份 NBT，必要时先建出来。
     *         {@code getCompoundTag} 返回的是<b>引用</b>而不是副本，所以直接改它就等于改存档。
     */
    private static NBTTagCompound persistedTag(EntityPlayer player) {
        NBTTagCompound data = player.getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
            data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        }
        return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }
}
