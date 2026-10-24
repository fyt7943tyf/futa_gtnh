package com.futa_gtnh.common;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.network.PacketSetSwiftStep;
import com.futa_gtnh.network.PacketSwiftStepAuraStatus;
import com.gtnewhorizon.cropsnh.api.BlockWithMeta;
import com.gtnewhorizon.cropsnh.api.ICropCard;
import com.gtnewhorizon.cropsnh.api.ISeedStats;
import com.gtnewhorizon.cropsnh.crops.abstracts.NHCropCard;
import com.gtnewhorizon.cropsnh.farming.SeedData;
import com.gtnewhorizon.cropsnh.farming.SeedStats;
import com.gtnewhorizon.cropsnh.farming.mutation.CropMutation;
import com.gtnewhorizon.cropsnh.farming.requirements.SubSoilRequirement;
import com.gtnewhorizon.cropsnh.handler.ConfigurationHandler;
import com.gtnewhorizon.cropsnh.tileentity.TileEntityCropSticks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Exercises native CropsNH growth, resource use, stochastic breeding and machine/subsoil gates. */
public final class SwiftStepGrowthRegression {

    private static int checks;
    private static int nextCropId = 1;

    static void run() throws Exception {
        settingsAndWire();
        schedulesAndBudget();
        growth();
        breeding();
        scheduler();
        agricarnationMixin();
        guiGeometry();
        System.out.println("Swift Step growth regression: " + checks + " checks passed");
    }

    private static void check(boolean valid, String reason) {
        checks++;
        if (!valid) throw new AssertionError(reason);
    }

    private static void settingsAndWire() {
        ItemStack charm = new ItemStack(new ItemSwiftStep());
        ItemSwiftStep.setGrowthAuraSpeed(charm, 123);
        check(ItemSwiftStep.getBotaniaAuraSpeed(charm) == 123, "old Botania strength is inherited");
        check(!ItemSwiftStep.isBreedingAuraEnabled(charm), "new breeding capability defaults off on old charms");
        ItemSwiftStep.setIndependentAuraSpeed(charm, false, 456);
        ItemSwiftStep.setIndependentAuraSpeed(charm, true, 2000);
        ItemSwiftStep.setBreedingAuraEnabled(charm, true);
        check(ItemSwiftStep.getGrowthAuraSpeed(charm) == 123, "independent controls never change crop/animal speed");
        check(ItemSwiftStep.getBotaniaAuraSpeed(charm) == 456 && ItemSwiftStep.getBreedingAuraSpeed(charm) == 1000,
            "independent settings and clamps apply");
        NBTTagCompound copy = (NBTTagCompound) charm.getTagCompound().copy();
        ItemStack loaded = new ItemStack(charm.getItem());
        loaded.setTagCompound(copy);
        check(ItemSwiftStep.isBreedingAuraEnabled(loaded) && ItemSwiftStep.getBotaniaAuraSpeed(loaded) == 456,
            "independent settings survive save/load");
        ByteBuf wire = Unpooled.buffer();
        ByteBuf copyWire = Unpooled.buffer();
        try {
            PacketSetSwiftStep.auraSpeed(true, 77).toBytes(wire);
            PacketSetSwiftStep decoded = new PacketSetSwiftStep();
            decoded.fromBytes(wire.duplicate());
            decoded.toBytes(copyWire);
            check(copyWire.getInt(48) == 77 && copyWire.getInt(52) == -1 && copyWire.getInt(20) == -1,
                "breeding packet changes only the specified field");
            copyWire.clear();
            decoded.fromBytes(wire.slice(0, 44));
            decoded.toBytes(copyWire);
            check(copyWire.getInt(44) == -1 && copyWire.getInt(48) == -1 && copyWire.getInt(52) == -1,
                "pre-upgrade packet does not reset independent settings");
            wire.clear();
            SwiftStepAuraStats stats = new SwiftStepAuraStats();
            stats.crops = 5; stats.crosses = 2; stats.botania = 9; stats.updates = 123; stats.limited = true;
            new PacketSwiftStepAuraStatus(stats).toBytes(wire);
            PacketSwiftStepAuraStatus received = new PacketSwiftStepAuraStatus();
            received.fromBytes(wire);
            check(received.crops == 5 && received.crosses == 2 && received.botania == 9
                && received.updates == 123 && received.limited, "server feedback round trip");
        } finally { wire.release(); copyWire.release(); }
    }

    private static void schedulesAndBudget() {
        for (int radius : new int[] {1, 4, 8, 16}) {
            List<int[]> offsets = SwiftStepGrowthAura.offsets(radius);
            check(offsets.get(0)[0] == 0 && offsets.get(0)[1] == 0 && offsets.get(0)[2] == 0,
                "discovery starts with nearest targets");
            for (int[] p : offsets) check(p[0]*p[0]+p[1]*p[1]+p[2]*p[2] <= radius*radius,
                "discovery stays inside sphere");
        }
        SwiftStepBotaniaRegression.TestWorld world = new SwiftStepBotaniaRegression.TestWorld();
        SwiftStepAuraBudget budget = SwiftStepAuraBudget.get(world);
        Object key = SwiftStepAuraBudget.position(-1, 64, -1);
        check(!key.equals(SwiftStepAuraBudget.position(-1, 64, 1)), "negative coordinates do not collide");
        check(budget.claim(key, 1) && !budget.claim(key, 1) && budget.claim(key, 10),
            "overlap supplies only the strongest allowance");
        int calls = 2;
        while (budget.claim(Integer.valueOf(calls), 10)) calls++;
        check(calls == SwiftStepAuraBudget.MAX_UPDATES, "all adapters share a hard update cap");
        world.time++;
        check(SwiftStepAuraBudget.get(world).claim(key, 1), "new tick resets allowance without catch-up");
        budget = SwiftStepAuraBudget.get(world);
        int scans=0;
        while (budget.scan()) scans++;
        check(scans == SwiftStepAuraBudget.MAX_SCANS, "discovery has a separate bounded allowance");
        check(SwiftStepBotaniaAura.extraTicks(99, 0) == 0, "disabled channel never receives default-speed work");
    }

    private static TestTile planted(SwiftStepBotaniaRegression.TestWorld world, int x, TestCrop crop, int progress) {
        TestTile tile = new TestTile();
        world.put(x,64,0,Blocks.wheat,tile);
        tile.plantSeed(new SeedData(crop, new SeedStats((byte)20,(byte)20,(byte)20,false), new ItemStack(Items.wheat_seeds)));
        tile.setGrowthProgress(progress);
        return tile;
    }

    private static void growth() throws Exception {
        ConfigurationHandler.enableWeeds = false;
        ConfigurationHandler.growthMultiplier = 1;
        SwiftStepBotaniaRegression.TestWorld world = new SwiftStepBotaniaRegression.TestWorld();
        TestTile tile = planted(world, 0, new TestCrop("growth"), 0);
        java.lang.reflect.Field fertilizer = TileEntityCropSticks.class.getDeclaredField("fertilizerStorage");
        fertilizer.setAccessible(true); fertilizer.setInt(tile, 100);
        java.lang.reflect.Field water = TileEntityCropSticks.class.getDeclaredField("waterStorage");
        water.setAccessible(true); water.setInt(tile, 100);
        check(SwiftStepGrowthAura.boostCrop(tile) && tile.getGrowthProgress() == 20, "native growth tick advances normally");
        check(fertilizer.getInt(tile) == 99 && water.getInt(tile) == 99, "native water and fertilizer consumption preserved");
        tile.ready = false;
        check(!SwiftStepGrowthAura.boostCrop(tile) && tile.getGrowthProgress() == 20, "unmet requirements block extra work");
        tile.ready = true;
        tile.rate = 0;
        check(!SwiftStepGrowthAura.boostCrop(tile) && !tile.isSick(), "zero nutrients do not accelerate sickness");
        tile.rate = 20;
        tile.setGrowthProgress(100);
        check(!SwiftStepGrowthAura.boostCrop(tile), "mature crop consumes no extra work");
        tile.setGrowthProgress(0);
        check(SwiftStepGrowthAura.boostCrop(tile), "harvested/reset crop can grow again");
        world.isRemote = true;
        check(!SwiftStepGrowthAura.boostCrop(tile), "client may not mutate crop growth");
    }

    private static void breeding() throws Exception {
        ConfigurationHandler.breedingChance = 3;
        SwiftStepBotaniaRegression.TestWorld world = new SwiftStepBotaniaRegression.TestWorld();
        TestCrop a = new TestCrop("parent_a"), b = new TestCrop("parent_b"), out = new TestCrop("result");
        out.addSubSoilRequirement(SubSoilRequirement.get("swift_test_diamond")
            .addBlock(new BlockWithMeta(Blocks.diamond_block)));
        new CropMutation(out,a,b).register();
        planted(world,-1,a,100); planted(world,1,b,100);
        TestTile cross = new TestTile();
        world.put(0,64,0,Blocks.wheat,cross);
        cross.setCrossCrop(true);
        Method breed = SwiftStepGrowthAura.class.getDeclaredMethod("breed", net.minecraft.tileentity.TileEntity.class);
        breed.setAccessible(true);
        check(!SwiftStepGrowthAura.canBoostCrop(cross), "Agricarnation does not treat empty cross as a growing crop");
        for (int i=0;i<100;i++) breed.invoke(null,cross);
        check(!cross.hasCrop(), "missing subsoil prevents the native mutation");
        world.blocks.put(SwiftStepBotaniaRegression.key(0,62,0),Blocks.diamond_block);
        int successes=0;
        for (int i=0;i<600;i++) {
            cross.setCrossCrop(true);
            breed.invoke(null,cross);
            if (cross.hasCrop()) {
                successes++;
                check(cross.getSeed().getCrop() == out, "native deterministic result preserved");
                int value=cross.getSeed().getStats().getGrowth();
                check(value>=18 && value<=24, "native parent-stat average and variation preserved");
            }
        }
        check(successes>120 && successes<280, "native one-in-three chance retained instead of guaranteed output: " + successes);
        TestCrop m1=new TestCrop("machine_a"),m2=new TestCrop("machine_b"),mo=new TestCrop("machine_result");
        new CropMutation(mo,m1,m2).machineOnly().register();
        planted(world,-1,m1,100); planted(world,1,m2,100);
        cross.setCrossCrop(true);
        for(int i=0;i<100;i++) breed.invoke(null,cross);
        check(!cross.hasCrop(), "machine-only mutation cannot be bypassed by aura");
    }


    private static void scheduler() throws Exception {
        SwiftStepGrowthAura.clear();
        SwiftStepBotaniaRegression.TestWorld world = new SwiftStepBotaniaRegression.TestWorld();
        TestPlayer player = new TestPlayer(world);
        player.setPosition(0,64,0);
        ItemStack charm = new ItemStack(new ItemSwiftStep());
        ItemSwiftStep.setGrowthAuraEnabled(charm,true);
        ItemSwiftStep.setGrowthAuraRadius(charm,4);
        ItemSwiftStep.setGrowthAuraSpeed(charm,100);
        Class<?> scanType=Class.forName("com.futa_gtnh.common.SwiftStepGrowthAura$CropScan");
        java.lang.reflect.Constructor<?> ctor=scanType.getDeclaredConstructor(); ctor.setAccessible(true);
        Object scan=ctor.newInstance();
        Method process=SwiftStepGrowthAura.class.getDeclaredMethod("process",net.minecraft.world.World.class,
            net.minecraft.entity.player.EntityPlayer.class,ItemStack.class,scanType); process.setAccessible(true);
        TestTile tile=planted(world,0,new TestCrop("scheduler"),0);
        process.invoke(null,world,player,charm,scan);
        check(tile.getGrowthProgress()==20,"incremental discovery finds nearest native crop on first tick");
        Object overlap=ctor.newInstance();
        process.invoke(null,world,player,charm,overlap);
        check(tile.getGrowthProgress()==20,"overlapping scheduler does not duplicate a target allowance");
        world.time++;
        world.loaded=false;
        process.invoke(null,world,player,charm,scan);
        check(tile.getGrowthProgress()==20,"unloaded target is never updated");
        world.loaded=true;
        world.time++;
        SwiftStepGrowthAura.changed(world,0,64,0);
        // A moving player must stop affecting targets immediately, even during a partial rescan.
        player.setPosition(20,64,0);
        process.invoke(null,world,player,charm,overlap);
        check(tile.getGrowthProgress()==20,"movement prunes stale out-of-range targets before updates");
        SwiftStepGrowthAura.forget(world);
        SwiftStepGrowthAura.clear();
    }


    private static void agricarnationMixin() throws Exception {
        SwiftStepBotaniaRegression.TestWorld world=new SwiftStepBotaniaRegression.TestWorld();
        TestTile crop=planted(world,0,new TestCrop("agricarnation"),0);
        world.blocks.put(SwiftStepBotaniaRegression.key(0,64,0),Blocks.stone);
        vazkii.botania.common.block.subtile.functional.SubTileAgricarnation flower=
            new vazkii.botania.common.block.subtile.functional.SubTileAgricarnation();
        vazkii.botania.common.block.tile.TileSpecialFlower host=new vazkii.botania.common.block.tile.TileSpecialFlower();
        world.put(1,64,0,Blocks.yellow_flower,host);host.setSubTile(flower);
        Method isPlant=flower.getClass().getDeclaredMethod("isPlant",int.class,int.class,int.class);isPlant.setAccessible(true);
        check((Boolean)isPlant.invoke(flower,0,64,0),"real transformed Agricarnation recognizes crop-stick tile on a non-growable block");
        Method redirect=null;
        for(Method method:flower.getClass().getDeclaredMethods()) if(method.getName().contains("futa$growCropStick")) redirect=method;
        check(redirect!=null,"native Agricarnation update call has been redirected by production Mixin");
        redirect.setAccessible(true);
        redirect.invoke(flower,world,0,64,0,Blocks.stone,1);
        check(crop.getGrowthProgress()==20,"transformed Agricarnation redirects into native CropsNH growth");
        crop.ready=false;
        check(!(Boolean)isPlant.invoke(flower,0,64,0),"Agricarnation refuses unmet crop growth requirements");
    }

    private static void guiGeometry() throws Exception {
        com.futa_gtnh.client.GuiSwiftStep gui=new com.futa_gtnh.client.GuiSwiftStep(new ItemStack(new ItemSwiftStep()));
        gui.width=400;gui.height=400;
        java.lang.reflect.Field page=gui.getClass().getDeclaredField("page");page.setAccessible(true);
        java.lang.reflect.Field buttons=net.minecraft.client.gui.GuiScreen.class.getDeclaredField("buttonList");
        buttons.setAccessible(true);
        for(int p=0;p<4;p++) {
            page.setInt(gui,p);gui.initGui();
            java.util.List<?> list=(java.util.List<?>)buttons.get(gui);
            java.util.Set<Integer> ids=new java.util.HashSet<>();
            for(Object entry:list) {
                net.minecraft.client.gui.GuiButton b=(net.minecraft.client.gui.GuiButton)entry;
                check(ids.add(b.id),"GUI button ids are unique per page");
                check(b.xPosition>=100 && b.xPosition+b.width<=300 && b.yPosition>=28 && b.yPosition+b.height<=372,
                    "all controls fit in the actual 200 by 344 panel");
            }
        }
    }

    private static final class TestPlayer extends net.minecraft.entity.player.EntityPlayer {
        TestPlayer(net.minecraft.world.World world) {
            super(world,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"swift-check"));
        }
        @Override public net.minecraft.util.ChunkCoordinates getPlayerCoordinates() { return new net.minecraft.util.ChunkCoordinates(0,64,0); }
        @Override public boolean canCommandSenderUseCommand(int level,String command) { return true; }
        @Override public void addChatMessage(net.minecraft.util.IChatComponent message) {}
    }

    private static final class TestCrop extends NHCropCard {
        TestCrop(String id) { super("swift_"+id, Color.GREEN, Color.GREEN); setNumericId(nextCropId++); }
        @Override public int getTier() { return 1; }
        @Override public int getGrowthDuration() { return 100; }
        @Override public ItemStack getSeedItem(ISeedStats stats) { return new ItemStack(Items.wheat_seeds); }
    }

    private static final class TestTile extends TileEntityCropSticks {
        boolean ready = true;
        int rate = 20;
        @Override public boolean areGrowthRequirementsMet() { return ready; }
        @Override public int calcGrowthRate() { return rate; }
        @Override public boolean isValidSoilForCrop(ICropCard crop) { return true; }
        @Override public boolean canCross() { return false; }
    }
}
