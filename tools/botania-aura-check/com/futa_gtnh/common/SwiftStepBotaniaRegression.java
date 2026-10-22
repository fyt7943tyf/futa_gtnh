package com.futa_gtnh.common;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;

import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.network.PacketSetSwiftStep;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import vazkii.botania.api.BotaniaAPI;
import vazkii.botania.api.recipe.RecipePureDaisy;
import vazkii.botania.api.recipe.RecipeManaInfusion;
import vazkii.botania.common.Botania;
import vazkii.botania.common.block.subtile.SubTilePureDaisy;
import vazkii.botania.common.block.mana.BlockPool;
import vazkii.botania.common.block.tile.TileSpecialFlower;
import vazkii.botania.common.block.tile.mana.TilePool;
import vazkii.botania.common.core.proxy.CommonProxy;
import vazkii.botania.common.entity.EntitySpark;
import vazkii.botania.common.item.ItemManaTablet;

/** Real Botania work methods run in an in-memory world; no game, saves or network. */
public final class SwiftStepBotaniaRegression {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(net.minecraft.launchwrapper.Launch.classLoader);
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        Field mods = Loader.class.getDeclaredField("namedMods");
        mods.setAccessible(true);
        mods.set(Loader.instance(), new HashMap<>());
        Bootstrap.func_151354_b();
        cpw.mods.fml.common.LoadController controller = new cpw.mods.fml.common.LoadController(Loader.instance());
        cpw.mods.fml.common.ModMetadata metadata = new cpw.mods.fml.common.ModMetadata();
        metadata.modId = "Botania";
        Field active = cpw.mods.fml.common.LoadController.class.getDeclaredField("activeContainer");
        active.setAccessible(true);
        active.set(controller, new cpw.mods.fml.common.DummyModContainer(metadata));
        Field control = Loader.class.getDeclaredField("modController");
        control.setAccessible(true);
        control.set(Loader.instance(), controller);
        Botania.proxy = new CommonProxy();
        File minecraftHome = new File("build/botania-regression");
        minecraftHome.mkdirs();
        Field home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, minecraftHome);
        net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Field proxyField = gregtech.GTMod.class.getField("proxy");
        proxyField.set(null, unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, proxyField.getType()));
        com.futa_gtnh.Config.swiftStepAuraBudgetMillis = 0;
        scheduleAndSettings();
        daisyConversion();
        poolCharging();
        poolInfusion();
        sparkTransfer();
        boundsAndBudgets();
        SwiftStepGrowthRegression.run();
        System.out.println("Swift Step Botania regression: " + checks + " checks passed");
    }

    private static void scheduleAndSettings() {
        for (int speed : new int[] { 1, 7, 99, 100, 101, 999, 1000 }) {
            int sum = 0;
            for (int tick = 0; tick < 100; tick++) sum += SwiftStepBotaniaAura.extraTicks(tick, speed);
            check(sum == speed, "even scheduling preserves requested extra ticks");
        }
        check(SwiftStepBotaniaAura.extraTicks(Long.MAX_VALUE, Integer.MAX_VALUE) == 10, "clamp speed without time overflow");
        Item item = new ItemSwiftStep();
        Item.itemRegistry.addObject(31010, "botania-aura-check:charm", item);
        ItemStack charm = new ItemStack(item);
        check(!ItemSwiftStep.isBotaniaAuraEnabled(charm), "legacy/new charm defaults to disabled");
        ItemSwiftStep.setGrowthAuraEnabled(charm, true);
        ItemSwiftStep.setBotaniaAuraEnabled(charm, true);
        ItemSwiftStep.setGrowthAuraRadius(charm, 1000);
        ItemSwiftStep.setGrowthAuraSpeed(charm, Integer.MAX_VALUE);
        NBTTagCompound tag = new NBTTagCompound();
        charm.writeToNBT(tag);
        ItemStack loaded = ItemStack.loadItemStackFromNBT(tag);
        check(ItemSwiftStep.isBotaniaAuraEnabled(loaded) && ItemSwiftStep.isGrowthAuraEnabled(loaded), "independent toggles persist");
        check(ItemSwiftStep.getGrowthAuraRadius(loaded) == 16 && ItemSwiftStep.getGrowthAuraSpeed(loaded) == 1000,
            "shared range and strength are clamped");
        ItemSwiftStep.setBotaniaAuraEnabled(loaded, false);
        check(ItemSwiftStep.isGrowthAuraEnabled(loaded), "Botania toggle leaves crop setting alone");
        ByteBuf wire = Unpooled.buffer();
        ByteBuf copied = Unpooled.buffer();
        try {
            PacketSetSwiftStep.botaniaAura(true).toBytes(wire);
            PacketSetSwiftStep decoded = new PacketSetSwiftStep();
            decoded.fromBytes(wire.duplicate());
            decoded.toBytes(copied);
            check(copied.getInt(40) == 1, "Botania flag round trip");
            copied.clear();
            decoded.fromBytes(wire.slice(0, 40));
            decoded.toBytes(copied);
            check(copied.getInt(40) == -1, "old settings packet leaves Botania unchanged");
            check(copied.getFloat(0) == PacketSetSwiftStep.UNCHANGED && copied.getInt(36) == -1,
                "Botania-only packet never resets movement or recovery");
        } finally {
            wire.release();
            copied.release();
        }
    }

    private static TileSpecialFlower daisy(TestWorld world) {
        TileSpecialFlower flower = new TileSpecialFlower();
        flower.setSubTile(new SubTilePureDaisy());
        world.put(0, 64, 0, Blocks.yellow_flower, flower);
        world.blocks.put(key(-1, 64, -1), Blocks.stone);
        return flower;
    }

    private static void daisyConversion() {
        BotaniaAPI.pureDaisyRecipes.clear();
        BotaniaAPI.pureDaisyRecipes.add(new RecipePureDaisy(Blocks.stone, Blocks.cobblestone, 0));
        TestWorld world = new TestWorld();
        TileSpecialFlower flower = daisy(world);
        for (int tick = 0; tick < 110; tick++) {
            world.time = tick;
            flower.updateEntity();
            SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
            check(world.time == tick, "aura does not advance global time");
            if (tick == 99) check(world.getBlock(-1, 64, -1) == Blocks.stone, "real conversion timer is not skipped");
        }
        check(world.getBlock(-1, 64, -1) == Blocks.cobblestone, "real pure daisy converts in about 110 world ticks");
        TestWorld normal = new TestWorld();
        TileSpecialFlower normalFlower = daisy(normal);
        for (int tick = 0; tick < 110; tick++) normalFlower.updateEntity();
        check(normal.getBlock(-1, 64, -1) == Blocks.stone, "unaccelerated pure daisy still needs normal wait");
    }

    private static TilePool pool(TestWorld world, int x, int mana, boolean outputting) {
        TilePool pool = new TilePool();
        world.put(x, 64, 0, Blocks.stone, pool);
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("mana", mana);
        data.setInteger("manaCap", 1000000);
        data.setBoolean("outputting", outputting);
        pool.readCustomNBT(data);
        return pool;
    }

    private static void poolCharging() {
        TestWorld world = new TestWorld();
        TilePool pool = pool(world, 0, 3500, true);
        ItemManaTablet tablet = new ItemManaTablet();
        ItemStack stack = new ItemStack(tablet);
        EntityItem dropped = new EntityItem(world, 0.5, 64.1, 0.5, stack);
        world.loadedEntityList.add(dropped);
        pool.updateEntity();
        check(tablet.getMana(stack) == 1000 && pool.getCurrentMana() == 2500, "normal pool transfer rate is 1000");
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
        check(tablet.getMana(stack) == 3500 && pool.getCurrentMana() == 0, "extra ticks charge faster without creating mana");
        check(dropped.age == 0 && !dropped.isDead, "charging does not age dropped items");
        TestWorld fullWorld = new TestWorld();
        TilePool fullPool = pool(fullWorld, 0, 3500, true);
        ItemStack full = new ItemStack(tablet);
        ItemManaTablet.setMana(full, 498500);
        fullWorld.loadedEntityList.add(new EntityItem(fullWorld, 0.5, 64.1, 0.5, full));
        SwiftStepBotaniaAura.accelerate(fullWorld, 0.5, 64.5, 0.5, 8, 1000);
        check(tablet.getMana(full) == 500000 && fullPool.getCurrentMana() == 2000, "capacity and mana costs remain exact");
    }

    private static void sparkTransfer() {
        TestWorld world = new TestWorld();
        TilePool source = pool(world, 0, 50000, false);
        TilePool target = pool(world, 3, 0, false);
        EntitySpark sender = new EntitySpark(world);
        sender.setPosition(0.5, 65.5, 0.5);
        sender.setUpgrade(3);
        EntitySpark receiver = new EntitySpark(world);
        receiver.setPosition(3.5, 65.5, 0.5);
        receiver.onUpdate();
        receiver.onUpdate();
        world.loadedEntityList.add(sender);
        world.loadedEntityList.add(receiver);
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
        check(target.getCurrentMana() > 1000, "actual spark entity transfers receive extra updates");
        check(source.getCurrentMana() + target.getCurrentMana() == 50000, "spark transfer conserves mana");
    }

    private static void poolInfusion() {
        BotaniaAPI.manaInfusionRecipes.clear();
        BotaniaAPI.manaInfusionRecipes.add(new RecipeManaInfusion(new ItemStack(Items.gold_ingot), new ItemStack(Items.iron_ingot), 100));
        BlockPool block = new BlockPool() {
            @Override public Block setBlockName(String name) { return this; }
        };
        TestWorld world = new TestWorld();
        TilePool pool = pool(world, 0, 350, false);
        world.blocks.put(key(0, 64, 0), block);
        EntityItem input = new EntityItem(world, 0.5, 64.1, 0.5, new ItemStack(Items.iron_ingot, 5));
        world.loadedEntityList.add(input);
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
        int outputs = 0;
        for (Object value : world.loadedEntityList) {
            EntityItem item = (EntityItem) value;
            if (item.getEntityItem().getItem() == Items.gold_ingot) outputs += item.getEntityItem().stackSize;
        }
        check(outputs == 3 && input.getEntityItem().stackSize == 2 && pool.getCurrentMana() == 50,
            "real pool collision conversion preserves input counts, output counts and mana costs");
        TestWorld protectedWorld = new TestWorld();
        TilePool protectedPool = pool(protectedWorld, 0, 350, false);
        protectedWorld.blocks.put(key(0, 64, 0), block);
        EntityItem protectedItem = new EntityItem(protectedWorld, 0.5, 64.1, 0.5, new ItemStack(Items.iron_ingot, 2));
        protectedItem.age = 105;
        protectedWorld.loadedEntityList.add(protectedItem);
        SwiftStepBotaniaAura.accelerate(protectedWorld, 0.5, 64.5, 0.5, 8, 1000);
        check(protectedItem.age == 105 && protectedItem.getEntityItem().stackSize == 2 && protectedPool.getCurrentMana() == 350,
            "new product collision protection is not bypassed by aging items");
    }

    private static void boundsAndBudgets() {
        TestWorld world = new TestWorld();
        CountingFlower near = new CountingFlower();
        CountingFlower far = new CountingFlower();
        world.put(0, 64, 0, Blocks.yellow_flower, near);
        world.put(30, 64, 0, Blocks.yellow_flower, far);
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
        check(near.calls == 10 && far.calls == 0, "spherical radius filters loaded tiles");
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
        check(near.calls == 10, "overlapping wearers do not multiply extra ticks");
        world.time++;
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 100);
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
        check(near.calls == 20, "stronger overlapping aura supplies only missing ticks");
        world.isRemote = true;
        world.time++;
        SwiftStepBotaniaAura.accelerate(world, 0.5, 64.5, 0.5, 8, 1000);
        check(near.calls == 20, "client never applies acceleration");
        TestWorld replacing = new TestWorld();
        CountingFlower replace = new CountingFlower() {
            @Override public void updateEntity() { super.updateEntity(); getWorldObj().removeTileEntity(xCoord, yCoord, zCoord); }
        };
        replacing.put(0, 64, 0, Blocks.yellow_flower, replace);
        SwiftStepBotaniaAura.accelerate(replacing, 0.5, 64.5, 0.5, 8, 1000);
        check(replace.calls == 1, "removed tile stops immediately without concurrent modification");
        TestWorld unloaded = new TestWorld();
        CountingFlower stale = new CountingFlower();
        unloaded.put(0, 64, 0, Blocks.yellow_flower, stale);
        unloaded.loaded = false;
        SwiftStepBotaniaAura.accelerate(unloaded, 0.5, 64.5, 0.5, 8, 1000);
        check(stale.calls == 0, "unloaded blocks are never forced to tick");
        TestWorld revivedWorld = new TestWorld();
        CountingFlower revived = new CountingFlower();
        revivedWorld.put(0,64,0,Blocks.yellow_flower,revived);
        SwiftStepBotaniaAura.accelerate(revivedWorld,0.5,64.5,0.5,8,100);
        revived.invalidate();
        revivedWorld.time++;
        SwiftStepBotaniaAura.accelerate(revivedWorld,0.5,64.5,0.5,8,100);
        check(revived.calls == 1,"invalid tile is pruned without further work");
        revived.validate();
        revivedWorld.time++;
        SwiftStepBotaniaAura.changed(revivedWorld,0,64,0);
        SwiftStepBotaniaAura.accelerate(revivedWorld,0.5,64.5,0.5,8,100);
        check(revived.calls == 2,"reactivated same tile can be rediscovered after pruning");
        TestWorld large = new TestWorld();
        List<CountingFlower> flowers = new ArrayList<>();
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) for (int dy = 0; dy < 2; dy++) {
            CountingFlower tile = new CountingFlower();
            large.put(x, 64 + dy, z, Blocks.yellow_flower, tile);
            flowers.add(tile);
        }
        SwiftStepBotaniaAura.accelerate(large, 0.5, 64.5, 0.5, 16, 1000);
        check(flowers.stream().mapToInt(tile -> tile.calls).sum() == SwiftStepBotaniaAura.MAX_EXTRA_UPDATES_PER_WORLD_TICK,
            "world budget bounds large farms");
        large.time++;
        SwiftStepBotaniaAura.accelerate(large, 0.5, 64.5, 0.5, 16, 1000);
        check(flowers.get(flowers.size() - 1).calls > 0, "budget cursor rotates instead of starving final tiles");
    }

    private static void check(boolean ok, String description) {
        checks++;
        if (!ok) throw new AssertionError(description);
    }

    static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }

    private static class CountingFlower extends TileSpecialFlower {
        int calls;
        @Override public void updateEntity() { calls++; }
    }

    static class TestWorld extends World {
        final Map<String, Block> blocks = new HashMap<>();
        final Map<String, TileEntity> tiles = new HashMap<>();
        long time;
        boolean loaded = true;

        TestWorld() {
            super(new SaveHandlerMP(), "botania-aura-check",
                new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                new WorldProviderSurface(), new Profiler());
        }
        void put(int x, int y, int z, Block block, TileEntity tile) {
            blocks.put(key(x, y, z), block);
            tiles.put(key(x, y, z), tile);
            tile.xCoord = x; tile.yCoord = y; tile.zCoord = z; tile.setWorldObj(this);
            loadedTileEntityList.add(tile);
        }
        @Override protected IChunkProvider createChunkProvider() { return null; }
        @Override protected int func_152379_p() { return 0; }
        @Override public Entity getEntityByID(int id) { return null; }
        @Override public long getTotalWorldTime() { return time; }
        @Override public boolean blockExists(int x, int y, int z) { return loaded; }
        @Override public boolean handleMaterialAcceleration(AxisAlignedBB box, Material material, Entity entity) { return false; }
        @Override public boolean isMaterialInBB(AxisAlignedBB box, Material material) { return false; }
        @Override public Block getBlock(int x, int y, int z) { return blocks.getOrDefault(key(x, y, z), Blocks.air); }
        @Override public int getBlockMetadata(int x, int y, int z) { return 0; }
        @Override public TileEntity getTileEntity(int x, int y, int z) { return tiles.get(key(x, y, z)); }
        @Override public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            blocks.put(key(x, y, z), block); return true;
        }
        @Override public void removeTileEntity(int x, int y, int z) {
            TileEntity tile = tiles.remove(key(x, y, z)); loadedTileEntityList.remove(tile);
        }
        @Override public List getEntitiesWithinAABB(Class type, AxisAlignedBB box) {
            List<Entity> found = new ArrayList<>();
            for (Object value : loadedEntityList) {
                Entity entity = (Entity) value;
                if (type.isInstance(entity) && entity.boundingBox.intersectsWith(box)) found.add(entity);
            }
            return found;
        }
        @Override public void markTileEntityChunkModified(int x,int y,int z,TileEntity tile) {}
        @Override public void func_147453_f(int x, int y, int z, Block block) {}
        @Override public void playSoundEffect(double x, double y, double z, String sound, float volume, float pitch) {}
        @Override public void playAuxSFX(int effect, int x, int y, int z, int data) {}
        @Override public boolean spawnEntityInWorld(Entity entity) { loadedEntityList.add(entity); return true; }
    }
}
