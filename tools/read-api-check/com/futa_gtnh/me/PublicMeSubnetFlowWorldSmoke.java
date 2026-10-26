package com.futa_gtnh.me;

import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.client.ProductionTerminalWorldSmoke;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.supply.SupplyLedger;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;
import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.MachineSource;
import appeng.api.parts.IPartHost;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMENetworkInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.util.AEColor;
import appeng.parts.misc.PartStorageBus;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Actual native-cell/pool transfers through a powered native AE subnet; exact opt-in smoke save only. */
public final class PublicMeSubnetFlowWorldSmoke {
    private static volatile boolean complete;
    private static volatile String failure;
    private WorldServer world;
    private EntityPlayerMP player;
    private int x,y,z,ticks,stage;
    private String lineId;
    private ItemKey cellKey,poolKey;
    private FluidKey fluidKey;
    private Block cable,drive,energy;
    private boolean placed;
    public static void register(){complete=false;failure=null;FMLCommonHandler.instance().bus().register(new PublicMeSubnetFlowWorldSmoke());}
    public static boolean isComplete(){return complete;}
    public static String failure(){return failure;}

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END || complete || failure!=null || !PublicMeWorldSmoke.isComplete() || !ProductionTerminalWorldSmoke.isComplete())return;
        MinecraftServer server=MinecraftServer.getServer();if(server==null)return;
        WorldServer candidate=server.worldServerForDimension(0);if(candidate==null || candidate.playerEntities.isEmpty())return;
        if(!Boolean.getBoolean("futa.uiSmoke") || !PublicMeWorldSmoke.saveName().equals(candidate.getSaveHandler().getWorldDirectory().getName())
            || !PublicMeWorldSmoke.worldName().equals(candidate.getWorldInfo().getWorldName())){failure="PUBLIC_ME_SUBNET_FLOW_SMOKE_FAILED wrong save";return;}
        try{
            if(++ticks>1000)throw new AssertionError("timeout at stage "+stage+": "+PublicMeNetwork.status());
            if(!PublicMeNetwork.isOnline())return;
            if(stage==0){world=candidate;player=(EntityPlayerMP)world.playerEntities.get(0);setup();stage=1;return;}
            PartStorageBus item=bus(x+2,z,ForgeDirection.WEST),fluid=bus(x+1,z-1,ForgeDirection.SOUTH);
            IGrid subnet=item.getActionableNode().getGrid();
            if(!item.getGridNode().isActive() || !fluid.getGridNode().isActive())return;
            require(subnet==fluid.getActionableNode().getGrid() && subnet!=PublicMeNetwork.grid(),"two native buses share their own distinct powered subnet");
            if(item.getConnectedGrid()!=PublicMeNetwork.grid() || fluid.getConnectedGrid()!=PublicMeNetwork.grid())return;
            IMENetworkInventory<IAEItemStack> items=item.getInternalHandler().getExternalNetworkInventory();
            IMENetworkInventory<IAEFluidStack> fluids=fluid.getInternalHandler().getExternalNetworkInventory();
            if(items==null || fluids==null)return;
            if(SharedStorageManager.getStorage().getItemAmount(cellKey)!=37)return;
            require(items instanceof PublicMeTrackedInventory && fluids instanceof PublicMeTrackedInventory,"optimized native parent inventories have real transformed hooks");
            NBTTagCompound create=new NBTTagCompound();create.setString("action","create");create.setString("name","子网实流量烟测-"+UUID.randomUUID());
            NBTTagCompound result=SupplyTracker.productionMutation(player,create);require(result.getBoolean("ok"),"create isolated production line");lineId=result.getString("lineId");
            NBTTagCompound bind=new NBTTagCompound();bind.setString("action","bind");bind.setString("lineId",lineId);
            bind.setTag("source",new SupplySource(SupplySource.AE_MACHINE,0,x+1,y,z,-1,"").write());
            require(SupplyTracker.productionMutation(player,bind).getBoolean("ok"),"bind only the actual two-in-one interface");
            require(snapshot("").getInteger("sourceCount")==3,"both native buses inherit the interface production line");
            MachineSource source=new MachineSource(item);
            require(items.extractItems(item(cellKey,10),Actionable.SIMULATE,source).getStackSize()==10,"optimized simulation returns available stock");
            require(SharedStorageManager.getStorage().getItemAmount(cellKey)==37 && SupplyTracker.LEDGER.snapshot(cellKey,10,SupplyTracker.minute()).outgoing==0,"simulation is absent from actual consumption");
            // Both ordinary native-cell stock and the legacy public pool go through the exposed fast path.
            require(items.extractItems(item(cellKey,13),Actionable.MODULATE,source).getStackSize()==13,"optimized path removes thirteen from an ordinary native ME cell");
            require(item.getInternalHandler().extractItems(item(cellKey,7),Actionable.MODULATE,source).getStackSize()==7,"normal storage-bus path removes seven from the same native cell");
            require(items.extractItems(item(cellKey,100),Actionable.MODULATE,source).getStackSize()==17,"optimized partial extraction drains the remaining seventeen");
            require(items.extractItems(item(poolKey,50),Actionable.MODULATE,source).getStackSize()==19,"optimized path also removes actual legacy-pool stock");
            require(fluids.extractItems(fluid(5000),Actionable.MODULATE,source).getStackSize()==5000,"same requesting machine removes real fluid through the fluid inlet");
            require(fluid.getInternalHandler().extractItems(fluid(2000),Actionable.MODULATE,source).getStackSize()==2000,"ordinary fluid bus path removes two thousand");
            require(fluids.extractItems(fluid(20000),Actionable.MODULATE,source).getStackSize()==5000,"optimized partial fluid extraction drains the remainder");
            SharedStorage pool=SharedStorageManager.getStorage();
            require(pool.getItemAmount(cellKey)==0 && pool.getItemAmount(poolKey)==0 && pool.getFluidAmount(fluidKey)==0,"actual tagged native-cell/item-pool/fluid-pool stocks reach zero");
            SupplySource itemSource=SupplySource.at(SupplySource.AE_BUS,item.getHost().getTile(),ForgeDirection.WEST);
            SupplySource fluidSource=SupplySource.at(SupplySource.AE_BUS,fluid.getHost().getTile(),ForgeDirection.SOUTH);
            checkLedger(cellKey,37,itemSource);checkLedger(poolKey,19,itemSource);checkLedger(fluidKey,12000,fluidSource);
            NBTTagCompound totals=snapshot("").getCompoundTag("totals");
            require(totals.getLong("itemOut")==56 && totals.getLong("fluidOut")==12000 && totals.getLong("itemIn")==0 && totals.getLong("fluidIn")==0,"production totals exactly match actual item/fluid stock depletion without duplicate records");
            require(snapshot("").getTagList("resources",10).tagCount()==3,"all three depleted resources remain visible in production details");
            require(snapshot(itemSource.id()).getCompoundTag("totals").getLong("itemOut")==56 && snapshot(fluidSource.id()).getCompoundTag("totals").getLong("fluidOut")==12000,"each inherited IO reports its own channel's real consumption");
            cleanup();complete=true;
            System.out.println("PUBLIC_ME_SUBNET_FLOW_SMOKE_SUCCESS native powered subnet + actual two-in-one interface + both inherited buses; ordinary cell 37 + legacy item pool 19 = 56 items consumed; fluid pool 12000 mB consumed; optimized/normal/simulated/partial paths; per-IO and production totals match actual zero stock exactly");
        }catch(Throwable error){try{cleanup();}catch(Throwable cleanup){error.addSuppressed(cleanup);}failure="PUBLIC_ME_SUBNET_FLOW_SMOKE_FAILED "+error;error.printStackTrace();System.err.println(failure);}
    }
    private void setup(){
        TilePublicMeCore core=PublicMeNetwork.core();require(core!=null,"core forced chunk available");
        x=(core.xCoord&~15)+4;y=core.yCoord-20;z=(core.zCoord&~15)+4;
        for(int dx=-1;dx<=2;dx++)for(int dz=-2;dz<=0;dz++)require(world.isAirBlock(x+dx,y,z+dz),"fixture region must be empty before any mutation");
        NBTTagCompound tag=new NBTTagCompound();tag.setString("nativeSubnetSmoke",UUID.randomUUID().toString());
        ItemStack gold=new ItemStack(Items.gold_ingot);gold.setTagCompound((NBTTagCompound)tag.copy());cellKey=ItemKey.of(gold);
        ItemStack iron=new ItemStack(Items.iron_ingot);iron.setTagCompound((NBTTagCompound)tag.copy());poolKey=ItemKey.of(iron);
        fluidKey=FluidKey.of(new FluidStack(FluidRegistry.WATER,1,(NBTTagCompound)tag.copy()));
        SharedStorage pool=SharedStorageManager.getStorage();
        require(pool.poolInsertItem(poolKey,19,true)==19 && pool.poolInsertFluid(fluidKey,12000,true)==12000,"seed tagged isolated legacy item/fluid pool");
        cable=AEApi.instance().definitions().blocks().multiPart().maybeBlock().orNull();
        drive=AEApi.instance().definitions().blocks().drive().maybeBlock().orNull();
        energy=AEApi.instance().definitions().blocks().energyCellCreative().maybeBlock().orNull();
        require(cable!=null && drive!=null && energy!=null,"native drive, cable and creative energy definitions");
        ItemStack cell=AEApi.instance().definitions().items().cell1k().maybeStack(1).orNull();
        IMEInventory<IAEItemStack> inventory=AEApi.instance().registries().cell().getCellInventory(cell,null,AEItemStackType.ITEM_STACK_TYPE);
        require(inventory!=null && inventory.injectItems(item(cellKey,37),Actionable.MODULATE,PublicMeNetwork.actionSource())==null,"seed thirty-seven real native-cell items");
        placed=true;
        require(world.setBlock(x,y,z,CommonProxy.blockPublicMeAccess,0,3),"place public ME access");
        require(world.setBlock(x-1,y,z,drive,0,3),"place native ME drive");
        ((IInventory)world.getTileEntity(x-1,y,z)).setInventorySlotContents(0,cell);
        require(world.setBlock(x+1,y,z,com.glodblock.github.loader.ItemAndBlockHolder.INTERFACE,0,3),"place actual two-in-one ME interface on public grid");
        addCable(x+2,z);addCable(x+1,z-1);addCable(x+2,z-1);
        require(((IPartHost)world.getTileEntity(x+2,y,z)).addPart(AEApi.instance().definitions().parts().storageBus().maybeStack(1).orNull(),ForgeDirection.WEST,player)==ForgeDirection.WEST,"place actual native item storage bus");
        require(((IPartHost)world.getTileEntity(x+1,y,z-1)).addPart(new ItemStack(com.glodblock.github.loader.ItemAndBlockHolder.FLUID_STORAGE_BUS),ForgeDirection.SOUTH,player)==ForgeDirection.SOUTH,"place actual native fluid storage bus");
        require(world.setBlock(x+2,y,z-2,energy,0,3),"power separate child grid with native creative cell");
    }
    private void addCable(int bx,int bz){
        require(world.setBlock(bx,y,bz,cable,0,3),"place empty native cable host");
        require(((IPartHost)world.getTileEntity(bx,y,bz)).addPart(AEApi.instance().definitions().parts().cableGlass().stack(AEColor.Transparent,1),ForgeDirection.UNKNOWN,player)!=null,"install native glass cable");
    }
    private PartStorageBus bus(int bx,int bz,ForgeDirection face){return (PartStorageBus)((IPartHost)world.getTileEntity(bx,y,bz)).getPart(face);}
    private NBTTagCompound snapshot(String source){return SupplyTracker.productionSnapshot(lineId,source,10,0,0,0);}
    private static void checkLedger(Object key,long amount,SupplySource source){SupplyLedger.Snapshot record=SupplyTracker.LEDGER.snapshot(key,10,SupplyTracker.minute());require(record.outgoing==amount && record.incoming==0 && record.rows.size()==1 && record.rows.get(0).source.id().equals(source.id()),"real transfer ledger and physical bus must match actual depleted stock: "+amount);}
    private static IAEItemStack item(ItemKey key,long amount){return AEItemStack.create(key.prototype()).setStackSize(amount);}
    private IAEFluidStack fluid(long amount){return AEFluidStack.create(fluidKey.prototype()).setStackSize(amount);}
    private void cleanup(){
        if(world==null)return;
        if(lineId!=null){NBTTagCompound delete=new NBTTagCompound();delete.setString("action","delete");delete.setString("lineId",lineId);require(SupplyTracker.productionMutation(player,delete).getBoolean("ok"),"remove smoke production metadata");lineId=null;}
        if(placed){
            TileEntity tile=world.getTileEntity(x-1,y,z);if(tile instanceof IInventory)((IInventory)tile).setInventorySlotContents(0,null);
            for(int[] pos:new int[][]{{2,-2},{2,-1},{1,-1},{2,0},{1,0},{-1,0},{0,0}})world.setBlockToAir(x+pos[0],y,z+pos[1]);placed=false;
        }
        if(poolKey!=null)SharedStorageManager.getStorage().poolExtractItem(poolKey,Long.MAX_VALUE);
        if(fluidKey!=null)SharedStorageManager.getStorage().poolExtractFluid(fluidKey,Long.MAX_VALUE);
    }
    private static void require(boolean condition,String text){if(!condition)throw new AssertionError(text);}
}
