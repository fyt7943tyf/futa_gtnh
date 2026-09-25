package com.futa_gtnh.rts.client;

import net.minecraft.entity.Entity;

/**
 * 一次光标拾取的结果（纯客户端的值对象）。
 *
 * <p>
 * 除了命中目标本身，还带生成它的<b>射线原点与方向</b>：发互动包时服务端
 * 需要它们反推虚拟眼位（见 {@code TemporaryContextSwitcher}），这样「点哪、
 * 从哪个角度看」在两端是一致的。
 */
public final class RtsPickResult {

    public enum Type {

        BLOCK,
        ENTITY,
        MISS
    }

    public final Type type;

    // 方块命中（BLOCK）
    public final int blockX, blockY, blockZ;
    /** 命中的面（0-5）。 */
    public final int side;
    /** 命中点的世界坐标（BLOCK）。 */
    public final double worldHitX, worldHitY, worldHitZ;

    // 实体命中（ENTITY）
    public final Entity entity;

    /** 距相机起点的距离（沿射线），用来和方块/实体命中比远近。 */
    public final double distance;

    /** 生成这次拾取的射线原点/方向（发送互动包时原样带给服务端）。 */
    public final double rayX, rayY, rayZ;
    public final double dirX, dirY, dirZ;

    private RtsPickResult(Type type, int blockX, int blockY, int blockZ, int side, double worldHitX, double worldHitY,
        double worldHitZ, Entity entity, double distance, double rayX, double rayY, double rayZ, double dirX,
        double dirY, double dirZ) {
        this.type = type;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.side = side;
        this.worldHitX = worldHitX;
        this.worldHitY = worldHitY;
        this.worldHitZ = worldHitZ;
        this.entity = entity;
        this.distance = distance;
        this.rayX = rayX;
        this.rayY = rayY;
        this.rayZ = rayZ;
        this.dirX = dirX;
        this.dirY = dirY;
        this.dirZ = dirZ;
    }

    public static RtsPickResult block(int x, int y, int z, int side, double hitX, double hitY, double hitZ,
        double distance, double rayX, double rayY, double rayZ, double dirX, double dirY, double dirZ) {
        return new RtsPickResult(
            Type.BLOCK,
            x,
            y,
            z,
            side,
            hitX,
            hitY,
            hitZ,
            null,
            distance,
            rayX,
            rayY,
            rayZ,
            dirX,
            dirY,
            dirZ);
    }

    public static RtsPickResult entity(Entity entity, double hitX, double hitY, double hitZ, double distance,
        double rayX, double rayY, double rayZ, double dirX, double dirY, double dirZ) {
        return new RtsPickResult(
            Type.ENTITY,
            0,
            0,
            0,
            0,
            hitX,
            hitY,
            hitZ,
            entity,
            distance,
            rayX,
            rayY,
            rayZ,
            dirX,
            dirY,
            dirZ);
    }

    public static RtsPickResult miss(double rayX, double rayY, double rayZ, double dirX, double dirY, double dirZ) {
        return new RtsPickResult(
            Type.MISS,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            null,
            Double.MAX_VALUE,
            rayX,
            rayY,
            rayZ,
            dirX,
            dirY,
            dirZ);
    }
}
