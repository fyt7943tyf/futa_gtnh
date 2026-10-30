package com.futa_gtnh.aewireless;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.config.Configuration;

import com.mojang.authlib.GameProfile;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.core.worlddata.IWorldData;
import appeng.core.worlddata.IWorldGridStorageData;
import appeng.core.worlddata.IWorldPlayerData;
import appeng.core.worlddata.WorldData;
import appeng.me.GridNode;
import appeng.me.GridStorage;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.Platform;
import sun.misc.Unsafe;

/** Real AE player IDs, proxy NBT and security audit; only world/grid services are fixtures. */
public final class AeSignalSecurityRegression {
    private static int checks;
    public static void run() throws Exception {
        Field unsafeField=field(Unsafe.class,"theUnsafe");Unsafe unsafe=(Unsafe)unsafeField.get(null);
        Constructor<?> constructor=Class.forName("appeng.core.worlddata.PlayerData").getDeclaredConstructor(Configuration.class);
        constructor.setAccessible(true);
        Configuration config=new Configuration(Files.createTempDirectory(Paths.get("build"),"ae-signal-owner-").resolve("players.cfg").toFile());
        IWorldPlayerData players=(IWorldPlayerData)constructor.newInstance(config);
        GameProfile owner=new GameProfile(UUID.randomUUID(),"SignalOwner");
        GameProfile other=new GameProfile(UUID.randomUUID(),"OtherPlayer");
        int ownerId=players.getPlayerID(owner),otherId=players.getPlayerID(other);
        GridStorage savedStorage=new GridStorage(123,null);
        IWorldGridStorageData storage=proxy(IWorldGridStorageData.class,(method,args)->method.equals("getGridStorage")?savedStorage:null);
        Field instance=field(WorldData.class,"instance");Object previous=instance.get(null);
        instance.set(null,proxy(IWorldData.class,(method,args)->method.equals("playerData")?players:method.equals("storageData")?storage:null));
        try {
            IEnergyGrid energy=proxy(IEnergyGrid.class,(method,args)->method.equals("isNetworkPowered"));
            ISecurityGrid security=proxy(ISecurityGrid.class,(method,args)->method.equals("isAvailable")
                || method.equals("hasPermission") && ((Integer)args[0])==ownerId && args[1]==SecurityPermissions.BUILD);
            IGrid grid=proxy(IGrid.class,(method,args)->method.equals("getCache")?(args[0]==IEnergyGrid.class?energy:args[0]==ISecurityGrid.class?security:null):null);
            MemoryWorld world=new MemoryWorld();
            TileAeSignalTransmitter baseline=tile(world);
            baseline.setOwner(owner.getId());
            SecurityNode unidentified=attach(baseline,grid);
            SecurityNode peer=new SecurityNode(baseline.getProxy(),grid);peer.setLastSecurityKey(456);
            check(Platform.securityCheck(unidentified,peer),"native AE rejects an unowned device connected to an active secured network");
            baseline.securityBreak();
            check(world.broken && world.drops,"native securityBreak actually breaks the device and drops it");

            Player placer=(Player)unsafe.allocateInstance(Player.class);placer.profile=owner;placer.worldObj=world;
            TileAeSignalTransmitter placed=tile(world);
            new BlockAeSignalTransmitter().onBlockPlacedBy(world,7,64,8,placer,null);
            check(owner.getId().equals(placed.getOwner()),"placement keeps wireless team ownership");
            check(field(AENetworkProxy.class,"owner").get(placed.getProxy())==placer,"placement supplies the actual placer to AE before joining");
            SecurityNode node=attach(placed,grid);placed.getProxy().readFromNBT(null);
            check(node.getPlayerID()==ownerId,"AE consumes the placer's real world player ID");
            check(!Platform.securityCheck(node,peer),"authorized placer passes the real AE security audit");
            check(placed.getProxy().getFlags().contains(GridFlags.REQUIRE_CHANNEL)
                && placed.getProxy().getIdlePowerUsage()==0.5D,"channel and idle power rules are retained");
            placed.setSignalName("secured-line");
            field(GridNode.class,"myStorage").set(node,savedStorage);
            NBTTagCompound tag=new NBTTagCompound();placed.writeToNBT_AENetwork(tag);placed.writeToNBT_Signal(tag);
            check(tag.getCompoundTag("proxy").getInteger("p")==ownerId,"native AE NBT persists the correct player ID");

            TileAeSignalTransmitter restored=tile(world);restored.readFromNBT_Signal(tag);
            SecurityNode restoredNode=attach(restored,grid);restored.readFromNBT_AENetwork(tag);
            check(restoredNode.getPlayerID()==ownerId && restored.getSignalName().equals("secured-line")
                && owner.getId().equals(restored.getOwner()),"reload preserves both AE and wireless ownership plus signal name");

            NBTTagCompound legacy=(NBTTagCompound)tag.copy();legacy.getCompoundTag("proxy").setInteger("p",-1);
            TileAeSignalTransmitter migrated=tile(world);migrated.readFromNBT_Signal(legacy);
            SecurityNode migratedNode=attach(migrated,grid);migrated.readFromNBT_AENetwork(legacy);
            check(migratedNode.getPlayerID()==ownerId,"legacy -1 ID is repaired by UUID without an online player");
            check(!Platform.securityCheck(migratedNode,peer),"repaired legacy node passes actual permission checking");
            NBTTagCompound saved=new NBTTagCompound();migrated.writeToNBT_AENetwork(saved);
            check(saved.getCompoundTag("proxy").getInteger("p")==ownerId,"next AE save records the repaired ID using the existing format");

            NBTTagCompound foreign=(NBTTagCompound)tag.copy();foreign.getCompoundTag("proxy").setInteger("p",otherId);
            TileAeSignalTransmitter preserve=tile(world);preserve.readFromNBT_Signal(foreign);
            SecurityNode preservedNode=attach(preserve,grid);preserve.readFromNBT_AENetwork(foreign);
            check(preservedNode.getPlayerID()==otherId,"migration never overwrites a valid existing AE owner");
            check(Platform.securityCheck(preservedNode,peer),"a player lacking BUILD permission is still denied");

            legacy.removeTag("futaSignalOwnerM");legacy.removeTag("futaSignalOwnerL");
            TileAeSignalTransmitter ownerless=tile(world);ownerless.readFromNBT_Signal(legacy);
            SecurityNode ownerlessNode=attach(ownerless,grid);ownerless.readFromNBT_AENetwork(legacy);
            check(ownerlessNode.getPlayerID()==-1 && Platform.securityCheck(ownerlessNode,peer),"unknown owner is never replaced with the network owner's identity");
        } finally { instance.set(null,previous); }
        System.out.println("AE wireless security: "+checks+" checks passed (real player data, placement, legacy NBT and native security audit)");
    }
    private static TileAeSignalTransmitter tile(MemoryWorld world){TileAeSignalTransmitter tile=new TileAeSignalTransmitter();tile.setWorldObj(world);tile.xCoord=7;tile.yCoord=64;tile.zCoord=8;world.tile=tile;return tile;}
    private static SecurityNode attach(TileAeSignalTransmitter tile,IGrid grid)throws Exception{SecurityNode node=new SecurityNode(tile.getProxy(),grid);field(AENetworkProxy.class,"node").set(tile.getProxy(),node);return node;}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void check(boolean okay,String text){if(!okay)throw new AssertionError(text);checks++;}
    private interface Service {Object call(String method,Object[] args);}
    private static <T>T proxy(Class<T> type,Service service){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->service.call(m.getName(),a)));}
    private static final class SecurityNode extends GridNode {
        private final IGrid grid;
        SecurityNode(IGridBlock block,IGrid grid){super(block);this.grid=grid;}
        @Override public IGrid getGrid(){return grid;}
    }
    private static final class Player extends EntityPlayerMP {
        GameProfile profile;
        Player(){super(null,null,null,null);}
        @Override public GameProfile getGameProfile(){return profile;}
        @Override public UUID getUniqueID(){return profile.getId();}
    }
    private static final class MemoryWorld extends World {
        TileEntity tile;boolean broken,drops;
        MemoryWorld(){super(new SaveHandlerMP(),"ae-signal-security",new WorldSettings(0,WorldSettings.GameType.SURVIVAL,false,false,WorldType.DEFAULT),new WorldProviderSurface(),new Profiler());}
        @Override protected IChunkProvider createChunkProvider(){return null;}
        @Override protected int func_152379_p(){return 0;}
        @Override public Entity getEntityByID(int id){return null;}
        @Override public TileEntity getTileEntity(int x,int y,int z){return tile;}
        @Override public int getBlockMetadata(int x,int y,int z){return 0;}
        @Override public net.minecraft.block.Block getBlock(int x,int y,int z){return net.minecraft.init.Blocks.air;}
        @Override public void markTileEntityChunkModified(int x,int y,int z,TileEntity tile){}
        @Override public void markBlockForUpdate(int x,int y,int z){}
        @Override public boolean func_147480_a(int x,int y,int z,boolean drop){broken=true;drops=drop;return true;}
    }
}
