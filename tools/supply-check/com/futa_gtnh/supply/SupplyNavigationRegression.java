package com.futa_gtnh.supply;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import com.futa_gtnh.Config;
import com.futa_gtnh.shared.ItemKey;
import sun.misc.Unsafe;

/** Actual navigation/landing checks with a read-counted world and captured player transport. */
public final class SupplyNavigationRegression {
    private static int checks;
    public static void run() throws Exception {
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe unsafe=(Unsafe)f.get(null);
        TestWorld world=(TestWorld)unsafe.allocateInstance(TestWorld.class);
        Field provider=net.minecraft.world.World.class.getDeclaredField("provider");provider.setAccessible(true);
        provider.set(world,new WorldProviderSurface());world.loaded=true;world.device=true;
        world.chunks=(IChunkProvider)Proxy.newProxyInstance(SupplyNavigationRegression.class.getClassLoader(),
            new Class<?>[]{IChunkProvider.class},(proxy,method,args)->method.getName().equals("chunkExists")?world.loaded:null);
        Player player=(Player)unsafe.allocateInstance(Player.class);player.worldObj=world;player.width=0.6F;player.height=1.8F;
        ItemKey key=ItemKey.of(new ItemStack(Items.iron_ingot));
        SupplySource source=new SupplySource(SupplySource.AE_MACHINE,0,0,64,0,-1,"");
        Config.enableSupplyTracking=true;Config.enableSupplyTeleport=true;SupplyTracker.start((File)null);
        try {
            SupplyTracker.record(key,source,1,false);
            check(!SupplySource.UNIDENTIFIED.hasPosition(),"unknown source has no teleport destination");
            check(!new SupplySource(SupplySource.PLAYER,0,0,64,0,-1,"p").hasPosition(),"player name cannot be treated as a block position");
            check(!new SupplySource(SupplySource.IO,0,Integer.MAX_VALUE,64,0,0,"").hasPosition(),"out-of-world source rejected");
            check(message(SupplyNavigation.navigate(player,key,"forged")).equals("noDestination"),"arbitrary client source rejected");
            check(message(SupplyNavigation.navigate(player,ItemKey.of(new ItemStack(Items.apple)),source.id())).equals("noDestination"),"source must belong to the selected exact resource");
            check(world.reads==0,"invalid destinations never scan the world");
            Config.enableSupplyTeleport=false;
            check(message(SupplyNavigation.navigate(player,key,source.id())).equals("teleportDisabled"),"server switch enforced before world access");
            Config.enableSupplyTeleport=true;world.loaded=false;
            check(message(SupplyNavigation.navigate(player,key,source.id())).equals("destinationUnloaded"),"unloaded chunks are not generated for teleport");
            world.loaded=true;world.device=false;
            check(message(SupplyNavigation.navigate(player,key,source.id())).equals("destinationGone"),"removed device rejected");
            world.device=true;world.lava=true;
            check(message(SupplyNavigation.navigate(player,key,source.id())).equals("noSafeSpot"),"lava has no safe landing and does not trigger excavation");
            check(!player.closed && player.moves==0,"failed landing preserves the open terminal and player position");
            world.lava=false;world.collide=true;
            check(message(SupplyNavigation.navigate(player,key,source.id())).equals("noSafeSpot"),"collision boxes reject apparently passable blocks");
            world.collide=false;world.reads=0;
            player.fallDistance=80;player.motionX=player.motionY=player.motionZ=2;
            NBTTagCompound arrived=SupplyNavigation.navigate(player,key,source.id());
            check(message(arrived).equals("arrived") && SupplySource.read(arrived.getCompoundTag("target")).equals(source),"successful response preserves authoritative target identity");
            check(player.closed && player.moves==1 && player.posY>=64 && player.posY<=65,"terminal closes only after a real safe spot is found");
            check(player.fallDistance==0 && player.motionX==0 && player.motionY==0 && player.motionZ==0,"teleport clears fall damage and previous momentum");
            check(world.reads<20000,"click-only search has a small fixed read budget");
            int reads=world.reads;
            SupplySource elsewhere=new SupplySource(SupplySource.IO,-100000,0,64,0,1,"");SupplyTracker.record(key,elsewhere,1,false);
            check(message(SupplyNavigation.navigate(player,key,elsewhere.id())).equals("destinationUnloaded"),"missing dimension is not initialized implicitly");
            check(world.reads==reads,"cross-dimension failure does not scan the current world");
            SupplyNavigation.DirectTeleporter teleporter=new SupplyNavigation.DirectTeleporter(world,new int[]{-12,70,35});
            teleporter.placeInPortal(player,1,2,3,0);
            check(player.posX==-11.5D && player.posY==70 && player.posZ==35.5D,"direct dimension placement uses exact block centers and no Nether scaling");
            check(SupplyTracker.LEDGER.source(key,source.id(),60)==null,"expired resource/source pairs cannot authorize teleport");
        } finally { SupplyTracker.stop();Config.enableSupplyTeleport=true; }
        System.out.println("Supply navigation: "+checks+" checks passed (ledger authorization, safe landing, no edits/loading and exact dimension placement)");
    }
    private static String message(NBTTagCompound tag){return tag.getString("message");}
    private static void check(boolean okay,String text){if(!okay)throw new AssertionError(text);checks++;}
    private static final class Player extends EntityPlayerMP {
        boolean closed;int moves;
        Player(){super(null,null,null,null);}
        @Override public void closeScreen(){closed=true;}
        @Override public void setPositionAndUpdate(double x,double y,double z){moves++;posX=x;posY=y;posZ=z;}
        @Override public void setLocationAndAngles(double x,double y,double z,float yaw,float pitch){posX=x;posY=y;posZ=z;}
    }
    private static final class TestWorld extends WorldServer {
        boolean loaded,device,lava,collide;int reads;IChunkProvider chunks;
        TestWorld(){super(null,new SaveHandlerMP(),"navigation-check",0,new WorldSettings(0,WorldSettings.GameType.SURVIVAL,false,false,WorldType.DEFAULT),new Profiler());}
        @Override public int getHeight(){return 256;}
        @Override public long getSeed(){return 0;}
        @Override public boolean blockExists(int x,int y,int z){return loaded;}
        @Override public IChunkProvider getChunkProvider(){return chunks;}
        @Override public TileEntity getTileEntity(int x,int y,int z){return device?new TileEntity():null;}
        @Override public Block getBlock(int x,int y,int z){reads++;if(!loaded)throw new AssertionError("unloaded block read");return y==63?Blocks.stone:x==0&&y==64&&z==0?Blocks.iron_block:lava?Blocks.lava:Blocks.air;}
        @Override public List getCollidingBoundingBoxes(Entity entity,AxisAlignedBB box){return collide?Collections.singletonList(box):Collections.emptyList();}
        @Override public boolean setBlock(int x,int y,int z,Block block,int meta,int flags){throw new AssertionError("navigation must never edit factory blocks");}
    }
}
