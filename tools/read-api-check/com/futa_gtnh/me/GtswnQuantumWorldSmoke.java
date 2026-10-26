package com.futa_gtnh.me;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.parts.IPartHost;
import appeng.api.util.AEColor;
import appeng.me.helpers.IGridProxyable;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in real GTSWN 1.8.8 jar, real AE graph and both dimensions. Never shipped in the release jar. */
public final class GtswnQuantumWorldSmoke {
    private static volatile boolean complete;
    private static volatile String failure;
    private final List<Fixture> fixtures=new ArrayList<>();
    private ForgeChunkManager.Ticket netherTicket;
    private WorldServer world;
    private EntityPlayer player;
    private Item terminal;
    private ItemStack first, second;
    private Class<?> registryClass,nodeClass;
    private Object registry;
    private Fixture active;
    private ItemKey cellKey;
    private FluidKey fluidKey;
    private int ticks,stage,checks,stressStarted;

    public static void register() {
        complete=!Boolean.getBoolean("futa.gtswnSmoke"); failure=null;
        if(!complete)FMLCommonHandler.instance().bus().register(new GtswnQuantumWorldSmoke());
    }
    public static boolean isComplete(){return complete;}
    public static String failure(){return failure;}
    private void require(boolean okay,String message){if(!okay)throw new AssertionError(message);checks++;}

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || complete || failure!=null || !PublicMeWorldSmoke.isComplete())return;
        MinecraftServer server=MinecraftServer.getServer();
        if(server==null)return;
        WorldServer candidate=server.worldServerForDimension(0);
        if(candidate==null)return;
        if(!Boolean.getBoolean("futa.uiSmoke") || !"FutaGtswnSmoke".equals(candidate.getSaveHandler().getWorldDirectory().getName())
            || !"Futa GTSWN smoke".equals(candidate.getWorldInfo().getWorldName())){
            failure="GTSWN_QUANTUM_SMOKE_FAILED refused non-fixture world";return;
        }
        world=candidate;
        try {
            if(++ticks>1600)throw new AssertionError("timeout at stage "+stage+": "+PublicMeNetwork.status());
            if(stage==0){
                if(!PublicMeNetwork.isOnline() || world.playerEntities.isEmpty())return;
                player=(EntityPlayer)world.playerEntities.get(0);
                Class<?> terminalClass=Class.forName("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal");
                registryClass=Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry");
                nodeClass=Class.forName("com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode");
                for(Object item:Item.itemRegistry)if(terminalClass.isInstance(item)){terminal=(Item)item;break;}
                require(terminal!=null,"real registered GTSWN terminal");
                first=new ItemStack(terminal);second=new ItemStack(terminal);
                registry=registryClass.getMethod("get",World.class).invoke(null,world);
                active=fixture(world);fixtures.add(active);
                require(world.setBlock(active.x,active.y,active.z,CommonProxy.blockPublicMeAccess,0,3),"place binding access");
                ((TilePublicMeAccess)world.getTileEntity(active.x,active.y,active.z)).getProxy().setOwner(player);
                stage=1;return;
            }
            if(stage==1){
                TileEntity access=world.getTileEntity(active.x,active.y,active.z);
                if(!GtswnQuantumCompat.canBind(access))return;
                require(use(first,world,active.x,active.y,active.z,false),"access click consumed by binding");
                require(publicBinding(first),"native terminal binds to actual public controller coordinates");
                require(quantized(),"native GTSWN quantization registry receives public controller");
                NBTTagCompound external=new NBTTagCompound();external.setString("label","retain me");
                first.getTagCompound().setTag("unrelated",external);
                require(use(second,world,active.x,active.y,active.z,false) && publicBinding(second),"second terminal independently binds");
                stage=2;return;
            }
            if(stage==2){
                if(!PublicMeNetwork.isOnline())return;
                verifyPorts();
                ordinaryController();
                placeNode(active,first);
                stage=3;return;
            }
            if(stage==3 || stage==5){
                TileEntity node=active.world.getTileEntity(active.x+4,active.y,active.z);
                TileEntity drive=active.world.getTileEntity(active.x+5,active.y,active.z);
                if(!Boolean.TRUE.equals(nodeClass.getMethod("isLinked").invoke(node)))return;
                IGridNode driveNode=((IGridHost)drive).getGridNode(ForgeDirection.UNKNOWN);
                if(driveNode==null || !driveNode.isActive() || !PublicMeNetwork.isOnline())return;
                verifyNode(node,driveNode);
                if(stage==3){
                    require(use(first,world,fixtures.get(0).x,fixtures.get(0).y,fixtures.get(0).z,true),"public terminal local-unbind handled");
                    require(!publicBinding(first) && publicBinding(second) && quantized(),"local unbind preserves registry and other terminal");
                    require("retain me".equals(first.getTagCompound().getCompoundTag("unrelated").getString("label")),"local unbind preserves unrelated terminal NBT");
                    require(Boolean.TRUE.equals(nodeClass.getMethod("isLinked").invoke(node)),"existing node survives terminal unbinding");
                    WorldServer nether=server.worldServerForDimension(-1);
                    netherTicket=ForgeChunkManager.requestTicket(FutaGtnhMod.instance,nether,ForgeChunkManager.Type.NORMAL);
                    require(netherTicket!=null,"finite Nether fixture ticket");netherTicket.setChunkListDepth(1);
                    ForgeChunkManager.forceChunk(netherTicket,new ChunkCoordIntPair(active.x>>4,active.z>>4));
                    nether.theChunkProviderServer.loadChunk(active.x>>4,active.z>>4);
                    active=fixture(nether);fixtures.add(active);placeNode(active,second);stage=5;return;
                }
                TileEntity controller=PublicMeNetwork.controller();
                require(use(second,world,controller.xCoord,controller.yCoord,controller.zCoord,true),"direct public-controller sneak action handled");
                require(!publicBinding(second) && quantized(),"direct controller unbind cannot dequantize shared network");
                verifyPorts();placeChannelStress(fixtures.get(0));stressStarted=ticks;stage=6;return;
            }
            if(stage==6){
                if(!PublicMeNetwork.isOnline() || ticks-stressStarted<120)return;
                Fixture f=fixtures.get(0);int online=0;
                IGridNode original=((IGridHost)f.world.getTileEntity(f.x+5,f.y,f.z)).getGridNode(ForgeDirection.UNKNOWN);
                if(original.isActive())online++;
                for(int dy=0;dy<32;dy++){
                    IGridNode node=((IGridHost)f.world.getTileEntity(f.x+5,f.y-dy,f.z+1)).getGridNode(ForgeDirection.UNKNOWN);
                    if(node.isActive())online++;
                }
                require(online==32,"native 32-channel limit leaves exactly one of 33 real quantum-branch drives without a channel; active="+online);
                TileEntity quantum=f.world.getTileEntity(f.x+4,f.y,f.z);NBTTagCompound state=new NBTTagCompound();quantum.writeToNBT(state);
                require(PublicMeNetwork.portUsedChannels(state.getInteger(GtswnQuantumCompat.PORT_NBT))==32,"all 32 active machine channels traverse the selected public controller port");
                verifyPorts();
                require(f.world.isAirBlock(f.x+4,f.y+1,f.z),"empty foreign-controller branch fixture");
                require(f.world.setBlock(f.x+4,f.y+1,f.z,AEApi.instance().definitions().blocks().controller().maybeBlock().orNull(),0,3),"attach foreign controller to an already connected quantum branch");
                f.conflict=true;stage=7;return;
            }
            if(stage==7){
                Fixture f=fixtures.get(0);TileEntity quantum=f.world.getTileEntity(f.x+4,f.y,f.z);
                if(Boolean.TRUE.equals(nodeClass.getMethod("isLinked").invoke(quantum)) || !PublicMeNetwork.isOnline())return;
                require("futa_gtnh.quantum.controllerConflict".equals(nodeClass.getMethod("getOfflineReasonKey").invoke(quantum)),"foreign-controller branch reports isolation while public network recovers");
                verifyPorts();f.world.setBlockToAir(f.x+4,f.y+1,f.z);f.conflict=false;stage=8;return;
            }
            if(stage==8){
                Fixture f=fixtures.get(0);TileEntity quantum=f.world.getTileEntity(f.x+4,f.y,f.z);
                if(!Boolean.TRUE.equals(nodeClass.getMethod("isLinked").invoke(quantum)) || !PublicMeNetwork.isOnline())return;
                require(!"futa_gtnh.quantum.controllerConflict".equals(nodeClass.getMethod("getOfflineReasonKey").invoke(quantum)),"isolated branch reconnects after foreign controller is removed");
                verifyPorts();cleanup();complete=true;
                System.out.println("GTSWN_QUANTUM_SMOKE_SUCCESS "+checks+" checks; real 1.8.8 terminal binding + six live ports + native quantum nodes/ME drives + item/fluid conservation + Nether + node NBT + local unbind + ordinary-controller isolation + 33-drive channel saturation + foreign-controller recovery");
            }
        }catch(Throwable error){
            try{cleanup();}catch(Throwable cleanup){error.addSuppressed(cleanup);}
            failure="GTSWN_QUANTUM_SMOKE_FAILED "+error;error.printStackTrace();System.err.println(failure);
        }
    }

    private Fixture fixture(WorldServer target){
        TileEntity core=PublicMeNetwork.core();Fixture f=new Fixture();f.world=target;
        f.x=(core.xCoord&~15)+2;f.y=core.yCoord-25;f.z=(core.zCoord&~15)+2;
        for(int dx:new int[]{0,4,5})require(target.isAirBlock(f.x+dx,f.y,f.z),"empty fixture position");
        require(target.isAirBlock(f.x+4,f.y-1,f.z),"empty fixture foundation");return f;
    }
    private boolean use(ItemStack stack,World target,int x,int y,int z,boolean sneak){
        boolean previous=player.isSneaking();player.setSneaking(sneak);
        try{return terminal.onItemUseFirst(stack,player,target,x,y,z,1,0.5F,0.5F,0.5F);}
        finally{player.setSneaking(previous);}
    }
    private boolean publicBinding(ItemStack stack) throws Exception {
        int[] anchor=(int[])terminal.getClass().getMethod("getAnchor",ItemStack.class).invoke(null,stack);
        return anchor!=null && PublicMeNetwork.isControllerLocation(anchor[0],anchor[1],anchor[2],anchor[3]);
    }
    private boolean quantized() throws Exception {
        TileEntity tile=PublicMeNetwork.controller();
        return (Boolean)registryClass.getMethod("isQuantized",int.class,int.class,int.class).invoke(registry,tile.xCoord,tile.yCoord,tile.zCoord);
    }
    private void verifyPorts(){
        TileEntity controller=PublicMeNetwork.controller();IGridNode node=((IGridHost)controller).getGridNode(ForgeDirection.UNKNOWN);
        int managed=0;for(IGridConnection link:node.getConnections())if(link.getOtherSide(node).getMachine() instanceof TilePublicMeCore)managed++;
        require(managed==6 && PublicMeNetwork.isOnline(),"quantization preserves all six live controller-port paths");
    }
    private void ordinaryController() throws Exception {
        Fixture f=fixtures.get(0);Block controller=AEApi.instance().definitions().blocks().controller().maybeBlock().orNull();
        require(f.world.isAirBlock(f.x,f.y,f.z+5),"empty ordinary controller fixture");
        require(f.world.setBlock(f.x,f.y,f.z+5,controller,0,3),"place ordinary controller");f.foreign=true;
        registryClass.getMethod("quantize",int.class,int.class,int.class).invoke(registry,f.x,f.y,f.z+5);
        Class<?> events=Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerEventHandler");
        events.getMethod("applyConnectionFilter",World.class,int.class,int.class,int.class).invoke(null,world,f.x,f.y,f.z+5);
        TileEntity tile=world.getTileEntity(f.x,f.y,f.z+5);
        require(((IGridProxyable)tile).getProxy().getConnectableSides().isEmpty(),"ordinary quantum controller keeps original face filter");
        ItemStack normal=new ItemStack(terminal);
        require(use(normal,world,f.x,f.y,f.z+5,false),"ordinary terminal binding retained");
        require(normal.getTagCompound().getInteger("QT_AnchorZ")==f.z+5,"ordinary binding targets its own controller");
        require(use(normal,world,f.x,f.y,f.z+5,true),"ordinary dequantization retained");
        require(!(Boolean)registryClass.getMethod("isQuantized",int.class,int.class,int.class).invoke(registry,f.x,f.y,f.z+5),"ordinary dequantization removes ordinary registry entry");
    }
    private void placeNode(Fixture f,ItemStack stack) throws Exception {
        require(f.world.setBlock(f.x+4,f.y-1,f.z,Blocks.stone,0,3),"place fixture foundation");f.foundation=true;
        require(use(stack,f.world,f.x+4,f.y-1,f.z,false),"real bound terminal places quantum node");
        require(nodeClass.isInstance(f.world.getTileEntity(f.x+4,f.y,f.z)),"real GTSWN node created");f.node=true;
        Block drive=AEApi.instance().definitions().blocks().drive().maybeBlock().orNull();
        ItemStack cell=AEApi.instance().definitions().items().cell1k().maybeStack(1).orNull();
        ItemStack prototype=new ItemStack(Items.gold_ingot);NBTTagCompound tag=new NBTTagCompound();tag.setString("gtswnSmoke",UUID.randomUUID().toString());prototype.setTagCompound(tag);
        cellKey=ItemKey.of(prototype);IAEItemStack items=AEItemStack.create(prototype);items.setStackSize(23);
        IMEInventory<IAEItemStack> inventory=AEApi.instance().registries().cell().getCellInventory(cell,null,AEItemStackType.ITEM_STACK_TYPE);
        require(inventory.injectItems(items,Actionable.MODULATE,PublicMeNetwork.actionSource())==null,"native cell seeded before mounting");
        require(f.world.setBlock(f.x+5,f.y,f.z,drive,0,3),"place native drive adjacent to quantum node");f.drive=true;
        ((IInventory)f.world.getTileEntity(f.x+5,f.y,f.z)).setInventorySlotContents(0,cell);
    }
    private void placeChannelStress(Fixture f){
        Block cable=AEApi.instance().definitions().blocks().multiPart().maybeBlock().orNull();
        Block drive=AEApi.instance().definitions().blocks().drive().maybeBlock().orNull();
        ItemStack dense=AEApi.instance().definitions().parts().cableDense().stack(AEColor.Transparent,1);
        for(int dy=0;dy<32;dy++){
            require(f.world.isAirBlock(f.x+4,f.y-dy,f.z+1) && f.world.isAirBlock(f.x+5,f.y-dy,f.z+1),"empty dense-backbone stress fixture");
            require(f.world.setBlock(f.x+4,f.y-dy,f.z+1,cable,0,3),"place native dense-backbone host");
            f.stress.add(new int[]{f.x+4,f.y-dy,f.z+1});
            require(((IPartHost)f.world.getTileEntity(f.x+4,f.y-dy,f.z+1)).addPart(dense.copy(),ForgeDirection.UNKNOWN,player)!=null,"install actual dense cable");
            require(f.world.setBlock(f.x+5,f.y-dy,f.z+1,drive,0,3),"place real channel-demanding ME drive");
            f.stress.add(new int[]{f.x+5,f.y-dy,f.z+1});
        }
    }
    @SuppressWarnings("unchecked")
    private void verifyNode(TileEntity tile,IGridNode driveNode) throws Exception {
        // GTSWN 1.8.8 treats UNKNOWN's zero offset as its own adjacent quantum node and returns null.
        IGridNode node=((IGridProxyable)tile).getProxy().getNode();
        require(node.getGrid()==PublicMeNetwork.grid() && driveNode.getGrid()==PublicMeNetwork.grid(),"quantum node and ordinary drive join exact public grid in dimension "+tile.getWorldObj().provider.dimensionId);
        Field connection=nodeClass.getDeclaredField("connection");connection.setAccessible(true);
        IGridConnection bridge=(IGridConnection)connection.get(tile);
        require(bridge.getOtherSide(node).getMachine() instanceof TilePublicMeCore,"quantum bridge uses bounded native public port, not an extra controller edge");
        require(bridge.getUsedChannels()>0 && bridge.getUsedChannels()<=32,"real device channel traverses public port bridge");
        require(SharedStorageManager.getStorage().getItemAmount(cellKey)==23 && SharedStorageManager.getStorage().getItemAmountPool(cellKey)==0,"quantum-connected native disk visible without copying raw pool");
        require(SharedStorageManager.getStorage().extractItem(cellKey,23)==23,"shared facade extracts actual quantum-connected disk stock");
        require(SharedStorageManager.getStorage().getItemAmount(cellKey)==0,"quantum-connected item quantity conserved");
        NBTTagCompound fluidTag=new NBTTagCompound();fluidTag.setString("gtswnSmoke",UUID.randomUUID().toString());
        fluidKey=FluidKey.of(new FluidStack(FluidRegistry.WATER,1,fluidTag));
        require(SharedStorageManager.getStorage().poolInsertFluid(fluidKey,137,true)==137,"seed exact tagged fluid quantity");
        IStorageGrid cache=node.getGrid().getCache(IStorageGrid.class);
        IMEMonitor<IAEFluidStack> fluids=(IMEMonitor<IAEFluidStack>)cache.getMEMonitor(AEFluidStackType.FLUID_STACK_TYPE);
        IAEFluidStack request=AEFluidStack.create(fluidKey.prototype());request.setStackSize(37);
        IAEFluidStack extracted=fluids.extractItems(request,Actionable.MODULATE,PublicMeNetwork.actionSource());
        require(extracted!=null && extracted.getStackSize()==37 && fluidKey.equals(FluidKey.of(extracted.getFluidStack())),"quantum node's native monitor preserves exact tagged fluid");
        require(SharedStorageManager.getStorage().getFluidAmountPool(fluidKey)==100,"quantum-connected fluid extraction counted once");
        require(SharedStorageManager.getStorage().extractFluid(fluidKey,100)==100,"remove remaining fluid fixture");fluidKey=null;
        NBTTagCompound saved=new NBTTagCompound();tile.writeToNBT(saved);int port=saved.getInteger(GtswnQuantumCompat.PORT_NBT);
        require(saved.hasKey(GtswnQuantumCompat.PORT_NBT) && port>=0 && port<6,"quantum public port persisted with native node NBT");
        TileEntity restored=(TileEntity)nodeClass.newInstance();restored.readFromNBT(saved);NBTTagCompound roundTrip=new NBTTagCompound();restored.writeToNBT(roundTrip);
        require(roundTrip.getInteger(GtswnQuantumCompat.PORT_NBT)==port && roundTrip.hasKey(GtswnQuantumCompat.PORT_NBT),"actual native node NBT reload preserves public port");
        Method overload=nodeClass.getDeclaredMethod("checkNetworkOverload");overload.setAccessible(true);overload.invoke(tile);
        require(PublicMeNetwork.controller()!=null && quantized(),"native public channel limits preserve managed core during GTSWN overload maintenance");
    }
    private void cleanup(){
        if(fluidKey!=null){long amount=SharedStorageManager.getStorage().getFluidAmountPool(fluidKey);if(amount>0)SharedStorageManager.getStorage().poolExtractFluid(fluidKey,amount);fluidKey=null;}
        for(Fixture f:fixtures){
            for(int[] position:f.stress)f.world.setBlockToAir(position[0],position[1],position[2]);
            if(f.drive){TileEntity tile=f.world.getTileEntity(f.x+5,f.y,f.z);if(tile instanceof IInventory)((IInventory)tile).setInventorySlotContents(0,null);f.world.setBlockToAir(f.x+5,f.y,f.z);}
            if(f.node)f.world.setBlockToAir(f.x+4,f.y,f.z);
            if(f.foundation)f.world.setBlockToAir(f.x+4,f.y-1,f.z);
            if(f.foreign)f.world.setBlockToAir(f.x,f.y,f.z+5);
            if(f.conflict)f.world.setBlockToAir(f.x+4,f.y+1,f.z);
            if(f.world.getBlock(f.x,f.y,f.z)==CommonProxy.blockPublicMeAccess)f.world.setBlockToAir(f.x,f.y,f.z);
        }
        if(netherTicket!=null)ForgeChunkManager.releaseTicket(netherTicket);netherTicket=null;
    }
    private static final class Fixture{WorldServer world;int x,y,z;boolean node,drive,foundation,foreign,conflict;final List<int[]> stress=new ArrayList<>();}
}
