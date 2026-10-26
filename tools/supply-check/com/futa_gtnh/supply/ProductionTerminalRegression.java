package com.futa_gtnh.supply;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;
import com.futa_gtnh.ae2.AeSupplyTracking;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.inventory.ContainerProductionTerminal;
import com.futa_gtnh.item.ItemProductionTerminal;
import com.futa_gtnh.network.PacketProductionTerminalUse;
import appeng.api.networking.security.IActionHost;
import appeng.api.parts.IPart;
import appeng.api.parts.SelectedPart;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.AEBasePart;
import appeng.tile.networking.TileCableBus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import sun.misc.Unsafe;

/** Physical identities, prebinding, window authority and real packet decoding without loading any chunk. */
public final class ProductionTerminalRegression {
    private static int checks;
    private static Unsafe unsafe;
    public static void run() throws Exception {
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
        TestWorld world=new TestWorld();
        Player player=(Player)unsafe.allocateInstance(Player.class);player.worldObj=world;
        player.inventory=new InventoryPlayer(player);player.inventory.mainInventory[0]=new ItemStack(new ItemProductionTerminal());
        player.posX=10.5;player.posY=64.5;player.posZ=10.5;player.admin=true;
        TileEntitySharedTerminal io=new TileEntitySharedTerminal();place(world,io);
        ContainerProductionTerminal container=new ContainerProductionTerminal(player,world,10,64,10,5,true);
        check(container.canInteractWith(player),"held terminal opens a nearby physical IO");
        check(!container.accepts(1,false) && container.accepts(1,true) && !container.accepts(2,true),"a window pins its first query session and rejects mutation-first and reused sessions");
        check(container.inventorySlots.size()==36 && container.getSlotFromInventory(player.inventory,0)!=null,
            "metadata GUI retains vanilla held-item reconciliation slots");
        check(container.slotClick(0,0,0,player)==null && player.inventory.mainInventory[0].getItem() instanceof ItemProductionTerminal,
            "hidden slots cannot move the terminal or player inventory");
        NBTTagCompound forged=new NBTTagCompound();forged.setString("action","bind");
        SupplySource other=new SupplySource(SupplySource.IO,99,123,65,456,2,"");
        forged.setString("sourceId",other.id());forged.setTag("source",other.write());
        NBTTagCompound safe=container.constrain(forged);
        check(SupplySource.read(safe.getCompoundTag("source")).equals(container.target.source)
            && safe.getString("sourceId").equals(container.target.source.id()),"client coordinates and IDs cannot redirect a binding");
        check(forged.getString("sourceId").equals(other.id()),"constraining a request does not mutate the decoded client payload");
        NBTTagCompound forbidden=new NBTTagCompound();forbidden.setString("action","delete");
        check(container.constrain(forbidden)==null,"binding window cannot delete arbitrary lines");
        SupplyTracker.start((File)null);
        try {
            NBTTagCompound create=new NBTTagCompound();create.setString("action","create");create.setString("name","现场线");
            String line=SupplyTracker.productionMutation(player,create).getString("lineId");safe.setString("lineId",line);
            check(SupplyTracker.LEDGER.source(container.target.source.id())==null,"target has never transferred any resource");
            player.admin=false;
            check(!SupplyTracker.productionMutation(player,safe).getBoolean("ok"),"physical terminal does not bypass the shared OP 4 permission check");
            player.admin=true;
            check(SupplyTracker.productionMutation(player,safe).getBoolean("ok"),"unobserved physical IO can be bound without manufactured flow");
            NBTTagCompound snapshot=SupplyTracker.productionBindingSnapshot(container.target,10,0);
            check(snapshot.getCompoundTag("currentBinding").getString("lineId").equals(line)
                && SupplyTracker.LEDGER.source(container.target.source.id())==null,"binding UI shows persisted metadata independently of activity");
        } finally {SupplyTracker.stop();}
        player.posX=30;check(!container.canInteractWith(player),"walking away invalidates physical binding window");player.posX=10.5;
        player.inventory.mainInventory[0]=new ItemStack(Items.paper);check(!container.canInteractWith(player),"changing held item invalidates window");
        player.inventory.mainInventory[0]=new ItemStack(new ItemProductionTerminal());
        place(world,new TileEntitySharedTerminal());check(!container.canInteractWith(player),"replacement tile cannot inherit an already open window");
        place(world,io);world.loaded=false;int reads=world.reads;
        check(!container.canInteractWith(player) && world.reads==reads,"unloaded target is rejected before accessing a block or tile");world.loaded=true;
        aeParts(world);
        IActionHost gt=(IActionHost)Proxy.newProxyInstance(ProductionTerminalRegression.class.getClassLoader(),
            new Class<?>[]{IActionHost.class,IGridProxyable.class},(p,m,a)->m.getName().equals("getLocation")?new DimensionalCoord(world,15,64,16):null);
        SupplySource gtSource=AeSupplyTracking.sourceFor(gt);
        check(gtSource.kind==SupplySource.AE_MACHINE && gtSource.x==15 && gtSource.z==16 && gtSource.side==-1,
            "non-tile GT action hosts resolve through grid proxy coordinates without a dimension lookup");
        protocol();
        System.out.println("Production terminal: "+checks+" checks passed (physical IO, prebinding, authority and packet bounds)");
    }
    private static void aeParts(TestWorld world)throws Exception {
        Cable cable=(Cable)unsafe.allocateInstance(Cable.class);place(world,cable);
        for(String type:new String[]{"appeng.parts.automation.PartImportBus","appeng.parts.automation.PartExportBus",
            "appeng.parts.misc.PartStorageBus","appeng.parts.misc.PartInterface",
            "com.glodblock.github.common.parts.PartFluidImportBus","com.glodblock.github.common.parts.PartFluidExportBus",
            "com.glodblock.github.common.parts.PartFluidInterface"}) {
            AEBasePart part=(AEBasePart)unsafe.allocateInstance(Class.forName(type));
            set(AEBasePart.class,part,"host",cable);set(AEBasePart.class,part,"side",ForgeDirection.EAST);
            set(AEBasePart.class,part,"is",new ItemStack(Items.paper));cable.part=part;cable.selected=new SelectedPart(part,ForgeDirection.EAST);
            ProductionTarget target=ProductionTarget.hit(world,10,64,10,2,.5F,.5F,.5F,null);
            check(target!=null && target.source.side==5 && target.identity==part,"multipart hit chooses actual part rather than outer block face: "+type);
            check(target.source.equals(AeSupplyTracking.sourceFor(part)),"physical identity matches telemetry: "+type);
            cable.part=null;check(!target.matches(world),"removed multipart invalidates pinned target: "+type);
        }
        cable.selected=new SelectedPart();check(ProductionTarget.hit(world,10,64,10,2,.5F,.5F,.5F,null)==null,"empty cable hit cannot be bound as a bus");
        AEBasePart terminal=(AEBasePart)unsafe.allocateInstance(appeng.parts.reporting.PartTerminal.class);
        cable.part=terminal;check(ProductionTarget.atFace(world,10,64,10,5)==null,"terminal panels are not IO");
        TileEntity iface=(TileEntity)unsafe.allocateInstance(appeng.tile.misc.TileInterface.class);place(world,iface);
        ProductionTarget full=ProductionTarget.atFace(world,10,64,10,3);
        check(full!=null && full.source.side==-1 && full.source.equals(AeSupplyTracking.sourceFor((IActionHost)iface)),"block ME interface binds as whole-device telemetry identity");
        place(world,new TileEntity());check(ProductionTarget.atFace(world,10,64,10,5)==null,"ordinary tiles are not guessed to be IO");
    }
    private static void protocol()throws Exception {
        Field valid=PacketProductionTerminalUse.class.getDeclaredField("valid");valid.setAccessible(true);
        ByteBuf bytes=Unpooled.buffer();new PacketProductionTerminalUse(10,64,10,5,.25F,.5F,1F).toBytes(bytes);
        PacketProductionTerminalUse decoded=new PacketProductionTerminalUse();decoded.fromBytes(bytes.copy());
        check(valid.getBoolean(decoded),"valid physical hit packet decodes");
        decoded.fromBytes(bytes.copy(0,25));check(!valid.getBoolean(decoded),"truncated hit packet rejected before reads");
        ByteBuf invalid=bytes.copy();invalid.setFloat(14,Float.NaN);decoded.fromBytes(invalid);
        check(!valid.getBoolean(decoded),"NaN hit cannot reach native multipart selection");
        invalid=bytes.copy();invalid.setByte(13,6);decoded.fromBytes(invalid);check(!valid.getBoolean(decoded),"unknown hit face rejected");
        invalid=bytes.copy();invalid.setInt(1,Integer.MIN_VALUE);decoded.fromBytes(invalid);check(!valid.getBoolean(decoded),"out-of-world coordinates rejected without overflow");
        bytes=Unpooled.buffer();new PacketProductionTerminalUse().toBytes(bytes);decoded.fromBytes(bytes);
        check(valid.getBoolean(decoded),"air-use packet opens the handheld manager without a target");
    }
    private static void set(Class<?> type,Object object,String name,Object value)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static void place(TestWorld world,TileEntity tile){tile.setWorldObj(world);tile.xCoord=10;tile.yCoord=64;tile.zCoord=10;world.tile=tile;}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    private static final class Player extends EntityPlayerMP {
        boolean admin;
        Player(){super(null,null,null,null);}
        @Override public boolean canCommandSenderUseCommand(int level,String command){return admin;}
    }
    public static final class Cable extends TileCableBus {
        IPart part;SelectedPart selected;
        @Override public IPart getPart(ForgeDirection face){return face==ForgeDirection.EAST?part:null;}
        @Override public SelectedPart selectPart(Vec3 hit){return selected;}
    }
    private static final class TestWorld extends World {
        boolean loaded=true;int reads;TileEntity tile;
        TestWorld(){super(new SaveHandlerMP(),"binding-check",new WorldSettings(0,WorldSettings.GameType.SURVIVAL,false,false,WorldType.DEFAULT),new WorldProviderSurface(),new Profiler());}
        @Override protected IChunkProvider createChunkProvider(){return null;}
        @Override protected int func_152379_p(){return 0;}
        @Override public Entity getEntityByID(int id){return null;}
        @Override public boolean blockExists(int x,int y,int z){return loaded;}
        @Override public TileEntity getTileEntity(int x,int y,int z){if(!loaded)throw new AssertionError("loaded chunk required");reads++;return tile;}
        @Override public Block getBlock(int x,int y,int z){if(!loaded)throw new AssertionError("loaded chunk required");reads++;return Blocks.stone;}
    }
}
