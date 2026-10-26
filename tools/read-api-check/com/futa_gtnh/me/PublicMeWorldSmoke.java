package com.futa_gtnh.me;

import java.util.UUID;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.shared.SharedStorageFile;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.parts.IPartHost;
import appeng.api.util.AEColor;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Destructive fixtures are strictly confined to the named opt-in development save. Never packaged in the mod jar. */
public final class PublicMeWorldSmoke {
    private static volatile boolean complete;
    private static volatile String failure;
    private int ticks;
    private int stage;
    private int x, y, z;
    private ItemKey poolKey;
    private ItemKey cellKey;
    private FluidKey fluidKey;
    private WorldServer world;
    private WorldServer fixtureWorld;
    private ForgeChunkManager.Ticket fixtureTicket;
    private Block fixtureDrive,fixtureCable;

    public static void register() {
        complete=false; failure=null;
        FMLCommonHandler.instance().bus().register(new PublicMeWorldSmoke());
    }

    public static boolean isComplete() { return complete; }
    public static String failure() { return failure; }
    public static String saveName() { return Boolean.getBoolean("futa.gtswnSmoke") ? "FutaGtswnSmoke" : "FutaUiSmoke"; }
    public static String worldName() { return Boolean.getBoolean("futa.gtswnSmoke") ? "Futa GTSWN smoke" : "Futa UI smoke"; }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || complete || failure!=null) return;
        MinecraftServer server=MinecraftServer.getServer();
        if(server==null || server.worldServers==null || server.worldServers.length==0) return;
        WorldServer candidate=server.worldServerForDimension(0);
        if(candidate==null) return;
        if(!Boolean.getBoolean("futa.uiSmoke") || !saveName().equals(candidate.getSaveHandler().getWorldDirectory().getName())
            || !worldName().equals(candidate.getWorldInfo().getWorldName())) {
            fail("Refused to mutate a world other than the exact FutaUiSmoke development save"); return;
        }
        world=candidate;
        if(++ticks>1200){fail("Timed out at stage "+stage+": "+PublicMeNetwork.status());return;}
        try {
            if(stage==0){
                if(!SharedStorageManager.isLoaded() || !PublicMeNetwork.isRunning())return;
                String id=UUID.randomUUID().toString();
                ItemStack paper=new ItemStack(Items.paper);NBTTagCompound tag=new NBTTagCompound();tag.setString("publicMeSmoke",id);
                paper.setTagCompound(tag);poolKey=ItemKey.of(paper);
                ItemStack gold=new ItemStack(Items.gold_ingot);gold.setTagCompound((NBTTagCompound)tag.copy());cellKey=ItemKey.of(gold);
                fluidKey=FluidKey.of(new FluidStack(FluidRegistry.WATER,1,(NBTTagCompound)tag.copy()));
                SharedStorage pool=SharedStorageManager.getStorage();
                require(pool.poolInsertItem(poolKey,37,true)==37,"legacy pool seed");
                require(pool.poolInsertFluid(fluidKey,12000,true)==12000,"legacy fluid seed");
                stage=1;return;
            }
            if(!PublicMeNetwork.isOnline())return;
            if(stage==1){
                if(world.playerEntities.isEmpty())return;
                verifyPersistence();
                verifyPool();
                placeNativeDrive(world);
                stage=2;return;
            }
            TileEntity access=fixtureWorld.getTileEntity(x,y,z),drive=fixtureWorld.getTileEntity(x+2,y,z);
            if(!(access instanceof IGridHost) || !(drive instanceof IGridHost))throw new AssertionError("native fixture tiles missing");
            IGridNode accessNode=((IGridHost)access).getGridNode(ForgeDirection.UNKNOWN);
            IGridNode driveNode=((IGridHost)drive).getGridNode(ForgeDirection.UNKNOWN);
            TileEntity cableTile=fixtureWorld.getTileEntity(x+1,y,z);
            require(cableTile instanceof IPartHost,"native cable host remains present");
            IGridNode cableNode=((IPartHost)cableTile).getPart(ForgeDirection.UNKNOWN).getGridNode();
            if(accessNode==null || driveNode==null || !driveNode.isActive()
                || cableNode==null || cableNode.getGrid()!=PublicMeNetwork.grid()
                || accessNode.getGrid()!=PublicMeNetwork.grid() || driveNode.getGrid()!=PublicMeNetwork.grid())return;
            IStorageGrid storage=driveNode.getGrid().getCache(IStorageGrid.class);
            IMEMonitor<IAEItemStack> monitor=(IMEMonitor<IAEItemStack>)storage.getMEMonitor(AEItemStackType.ITEM_STACK_TYPE);
            IAEItemStack cellStock=monitor.getStorageList().findPrecise(AEItemStack.create(cellKey.prototype()));
            if(cellStock==null || cellStock.getStackSize()!=23)return;
            SharedStorage pool=SharedStorageManager.getStorage();
            require(pool.getItemAmountPool(cellKey)==0,"native cell contents are not copied into the legacy pool");
            require(pool.getItemAmount(cellKey)==23,"shared facade reads a real ordinary ME cell");
            require(pool.extractItem(cellKey,23)==23,"shared facade extracts actual native cell contents");
            require(pool.getItemAmount(cellKey)==0 && pool.getItemAmountPool(cellKey)==0,"native cell extraction conserves quantity");
            cleanupFixtures();
            if(stage==2){
                WorldServer nether=server.worldServerForDimension(-1);
                require(nether!=null && nether.provider.dimensionId==-1,"native Nether world is available");
                placeNativeDrive(nether);stage=3;return;
            }
            complete=true;
            System.out.println("PUBLIC_ME_SMOKE_SUCCESS legacy pool + tagged item/fluid conservation + real glass cable/native drive + cross-dimension access same grid + ordinary ME cell via shared facade + saved UUID");
        }catch(Throwable error){fail(error.toString());error.printStackTrace();}
    }

    private void verifyPool(){
        SharedStorage pool=SharedStorageManager.getStorage();
        IMEMonitor<IAEItemStack> items=PublicMeNetwork.monitor(AEItemStackType.ITEM_STACK_TYPE);
        IAEItemStack before=items.getStorageList().findPrecise(item(poolKey,1));
        require(before!=null && before.getStackSize()==37,"legacy pool appears once in native ME monitor");
        require(items.injectItems(item(poolKey,11),Actionable.SIMULATE,PublicMeNetwork.actionSource())==null,"native insert simulation");
        require(items.extractItems(item(poolKey,5),Actionable.SIMULATE,PublicMeNetwork.actionSource()).getStackSize()==5,"native extract simulation");
        require(pool.getItemAmountPool(poolKey)==37,"item simulations do not mutate durable pool");
        require(items.injectItems(item(poolKey,13),Actionable.MODULATE,PublicMeNetwork.actionSource())==null,"native insertion accepted");
        require(pool.getItemAmountPool(poolKey)==50,"native insertion reaches legacy pool exactly once");
        require(items.extractItems(item(poolKey,7),Actionable.MODULATE,PublicMeNetwork.actionSource()).getStackSize()==7,"native extraction result");
        require(pool.getItemAmountPool(poolKey)==43,"native extraction subtracts once");
        require(items.extractItems(item(poolKey,43),Actionable.MODULATE,PublicMeNetwork.actionSource()).getStackSize()==43,"item fixture cleanup");
        require(pool.getItemAmountPool(poolKey)==0,"item conservation");
        IMEMonitor<IAEFluidStack> fluids=PublicMeNetwork.monitor(AEFluidStackType.FLUID_STACK_TYPE);
        IAEFluidStack stored=fluids.getStorageList().findPrecise(fluid(1));
        require(stored!=null && stored.getStackSize()==12000,"legacy fluid appears once in native monitor");
        fluids.injectItems(fluid(1000),Actionable.SIMULATE,PublicMeNetwork.actionSource());
        fluids.extractItems(fluid(500),Actionable.SIMULATE,PublicMeNetwork.actionSource());
        require(pool.getFluidAmountPool(fluidKey)==12000,"fluid simulation is read only");
        require(fluids.injectItems(fluid(345),Actionable.MODULATE,PublicMeNetwork.actionSource())==null,"fluid insert");
        require(fluids.extractItems(fluid(2345),Actionable.MODULATE,PublicMeNetwork.actionSource()).getStackSize()==2345,"fluid extract");
        require(pool.getFluidAmountPool(fluidKey)==10000,"fluid transfer conservation");
        require(fluids.extractItems(fluid(10000),Actionable.MODULATE,PublicMeNetwork.actionSource()).getStackSize()==10000,"fluid fixture cleanup");
        require(pool.getFluidAmountPool(fluidKey)==0,"fluid conservation after cleanup");
    }

    private void placeNativeDrive(WorldServer target){
        fixtureWorld=target;
        TilePublicMeCore core=PublicMeNetwork.core();
        require(core!=null,"live forced core chunk");
        y=core.yCoord-10;boolean found=false;
        if(target!=world){
            fixtureTicket=ForgeChunkManager.requestTicket(FutaGtnhMod.instance,target,ForgeChunkManager.Type.NORMAL);
            require(fixtureTicket!=null,"finite cross-dimension fixture ticket");
            fixtureTicket.setChunkListDepth(1);
            ForgeChunkManager.forceChunk(fixtureTicket,new ChunkCoordIntPair(core.xCoord>>4,core.zCoord>>4));
            target.theChunkProviderServer.loadChunk(core.xCoord>>4,core.zCoord>>4);
        }
        for(int candidate=0;candidate<4;candidate++){
            x=(core.xCoord&~15)+2+candidate*3;z=(core.zCoord&~15)+2;
            if(target.isAirBlock(x,y,z) && target.isAirBlock(x+1,y,z) && target.isAirBlock(x+2,y,z)){found=true;break;}
        }
        require(found,"empty isolated fixture site");
        Block drive=AEApi.instance().definitions().blocks().drive().maybeBlock().orNull();
        fixtureDrive=drive;
        fixtureCable=AEApi.instance().definitions().blocks().multiPart().maybeBlock().orNull();
        ItemStack cell=AEApi.instance().definitions().items().cell1k().maybeStack(1).orNull();
        ItemStack glass=AEApi.instance().definitions().parts().cableGlass().stack(AEColor.Transparent,1);
        require(drive!=null && cell!=null && fixtureCable!=null && glass!=null,"native ME drive/cell/cable definitions available");
        IMEInventory<IAEItemStack> inventory=AEApi.instance().registries().cell().getCellInventory(cell,null,AEItemStackType.ITEM_STACK_TYPE);
        require(inventory!=null && inventory.injectItems(item(cellKey,23),Actionable.MODULATE,PublicMeNetwork.actionSource())==null,"native cell filled before mounting");
        require(target.setBlock(x,y,z,CommonProxy.blockPublicMeAccess,0,3),"place real public network access");
        require(target.setBlock(x+1,y,z,fixtureCable,0,3),"place native cable host");
        TileEntity cable=target.getTileEntity(x+1,y,z);require(cable instanceof IPartHost,"native cable host");
        require(((IPartHost)cable).addPart(glass,ForgeDirection.UNKNOWN,(net.minecraft.entity.player.EntityPlayer)world.playerEntities.get(0))!=null,"install actual transparent glass cable part");
        require(target.setBlock(x+2,y,z,drive,0,3),"place real native ME drive beyond glass cable");
        TileEntity tile=target.getTileEntity(x+2,y,z);require(tile instanceof IInventory,"native drive inventory");
        ((IInventory)tile).setInventorySlotContents(0,cell);
    }

    private void cleanupFixtures(){
        try {
            if(fixtureWorld!=null){
                if(fixtureDrive!=null && fixtureWorld.getBlock(x+2,y,z)==fixtureDrive){
                    TileEntity drive=fixtureWorld.getTileEntity(x+2,y,z);
                    if(drive instanceof IInventory)((IInventory)drive).setInventorySlotContents(0,null);
                    fixtureWorld.setBlockToAir(x+2,y,z);
                }
                if(fixtureCable!=null && fixtureWorld.getBlock(x+1,y,z)==fixtureCable)fixtureWorld.setBlockToAir(x+1,y,z);
                if(fixtureWorld.getBlock(x,y,z)==CommonProxy.blockPublicMeAccess)fixtureWorld.setBlockToAir(x,y,z);
            }
        } finally {
            fixtureWorld=null;fixtureDrive=null;fixtureCable=null;
            if(fixtureTicket!=null)ForgeChunkManager.releaseTicket(fixtureTicket);
            fixtureTicket=null;
        }
    }

    private void verifyPersistence() throws Exception {
        PublicMeWorldData saved=(PublicMeWorldData)world.loadItemData(PublicMeWorldData.class,PublicMeWorldData.NAME);
        require(saved!=null && saved.located && saved.storageId!=null,"persisted public anchor identity exists");
        File directory=world.getSaveHandler().getWorldDirectory();
        require(saved.storageId.toString().equals(SharedStorageFile.getAeStorageId(directory)),"public anchor and warehouse file use the same UUID");
        File file=new File(directory,"public-me-smoke-baseline.dat");
        if(file.isFile()){
            NBTTagCompound previous;
            try(FileInputStream stream=new FileInputStream(file)){previous=CompressedStreamTools.readCompressed(stream);}
            require(saved.storageId.toString().equals(previous.getString("id")) && saved.x==previous.getInteger("x")
                && saved.y==previous.getInteger("y") && saved.z==previous.getInteger("z"),"server restart preserves public anchor UUID and location");
            System.out.println("PUBLIC_ME_RESTART_SUCCESS UUID and physical anchor match the previous saved run");
        } else {
            NBTTagCompound baseline=new NBTTagCompound();baseline.setString("id",saved.storageId.toString());
            baseline.setInteger("x",saved.x);baseline.setInteger("y",saved.y);baseline.setInteger("z",saved.z);
            try(FileOutputStream stream=new FileOutputStream(file)){CompressedStreamTools.writeCompressed(baseline,stream);}
            System.out.println("PUBLIC_ME_RESTART_BASELINE saved UUID and anchor for the next run");
        }
    }

    private static IAEItemStack item(ItemKey key,long amount){IAEItemStack value=AEItemStack.create(key.prototype());value.setStackSize(amount);return value;}
    private IAEFluidStack fluid(long amount){IAEFluidStack value=AEFluidStack.create(fluidKey.prototype());value.setStackSize(amount);return value;}
    private static void require(boolean okay,String text){if(!okay)throw new AssertionError(text);}
    private void fail(String message){
        try {cleanupFixtures();}catch(Throwable cleanup){message+="; cleanup: "+cleanup;}
        failure=message;System.err.println("PUBLIC_ME_SMOKE_FAILED "+message);
    }
}
