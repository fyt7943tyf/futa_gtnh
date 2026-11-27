package com.futa_gtnh.spacecenter;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 星球种子存档：各维度的世界 seed 在维度首次加载时捕获，写进主世界的
 * {@link WorldSavedData}（{@code mapStorage}，随存档走、跨重启有效）。
 *
 * <p>
 * GT 的矿脉/油田生成完全由 {@code worldSeed + 维度 id + 区块坐标} 决定，
 * 所以拿到 seed 就能离线复现该星球任意区块的矿脉（{@link VirtualVeinSim}）。
 * 星球维度只有在有人去过至少一次后才会加载 —— 「先去开拓、再远程开采」，
 * 与指挥中心「塞入当地可达等级的火箭」的玩法一致。
 */
public final class PlanetSeeds extends WorldSavedData {

    private static final String DATA_NAME = "futa_gtnh.planet_seeds";

    /** 维度 id -> 世界 seed。 */
    private final Map<Integer, Long> seeds = new HashMap<>();

    /** 内存镜像（两个世界切换间快速可用）。 */
    private static volatile Map<Integer, Long> cache = new HashMap<>();

    public PlanetSeeds(String name) {
        super(name);
    }

    /** 只在服务端注册事件；客户端从不需要种子。 */
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new EventHandler());
    }

    public static final class EventHandler {

        @SubscribeEvent
        public void onWorldLoad(WorldEvent.Load event) {
            World world = event.world;
            if (world == null || world.isRemote) return;
            capture(world);
        }
    }

    /** 捕获一个已加载维度的种子（含主世界）。 */
    public static void capture(World world) {
        int dimId = world.provider.dimensionId;
        long seed = world.getSeed();
        if (cache.containsKey(dimId) && cache.get(dimId) == seed) return;
        // 主世界上的 mapStorage 才是随存档保存的那份；其他维度挂着同一个 mapStorage（单机/常规服务器皆然）
        World storageWorld = world.provider.dimensionId == 0 ? world : null;
        if (storageWorld == null) {
            net.minecraft.world.World overworld = net.minecraftforge.common.DimensionManager.getWorld(0);
            if (overworld == null) {
                // 服务器关机竞态：只更新内存
                cache.put(dimId, seed);
                return;
            }
            storageWorld = overworld;
        }
        PlanetSeeds data = (PlanetSeeds) storageWorld.mapStorage.loadData(PlanetSeeds.class, DATA_NAME);
        if (data == null) {
            data = new PlanetSeeds(DATA_NAME);
            storageWorld.mapStorage.setData(DATA_NAME, data);
        }
        data.seeds.put(dimId, seed);
        data.markDirty();
        cache = new HashMap<>(data.seeds);
    }

    /** @return 该维度的世界 seed；尚未勘查（维度从未加载）时 null。 */
    public static Long seedOf(int dimensionId) {
        Long seed = cache.get(dimensionId);
        if (seed != null) return seed;
        // 兜底：维度此刻恰好加载着（例如刚有人去过）
        World world = net.minecraftforge.common.DimensionManager.getWorld(dimensionId);
        if (world != null && !world.isRemote) {
            capture(world);
            return cache.get(dimensionId);
        }
        return null;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        seeds.clear();
        net.minecraft.nbt.NBTTagList list = nbt.getTagList("seeds", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            seeds.put(tag.getInteger("dim"), tag.getLong("seed"));
        }
        cache = new HashMap<>(seeds);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
        for (Map.Entry<Integer, Long> entry : seeds.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("dim", entry.getKey());
            tag.setLong("seed", entry.getValue());
            list.appendTag(tag);
        }
        nbt.setTag("seeds", list);
    }
}
