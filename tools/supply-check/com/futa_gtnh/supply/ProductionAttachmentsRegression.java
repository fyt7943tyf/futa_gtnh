package com.futa_gtnh.supply;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import appeng.parts.AEBasePart;
import appeng.parts.CableBusStorage;
import appeng.tile.networking.TileCableBus;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import sun.misc.Unsafe;

/** Real AE hit geometry plus loaded-world relationship discovery and derived metadata lifecycle. */
public final class ProductionAttachmentsRegression {
    private static int checks;
    private static Unsafe unsafe;
    public static void run()throws Exception {
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
        nativeHits();lifecycle();panels();
        System.out.println("Interface production attachments: "+checks+" checks passed (native geometry, item/fluid buses, inheritance and no duplicate flow)");
    }
    private static void nativeHits()throws Exception {
        TestWorld world=new TestWorld();
        for(int coordinate:new int[]{120,-120})for(String type:new String[]{"appeng.parts.misc.PartStorageBus","com.glodblock.github.common.parts.PartFluidStorageBus"})
            for(ForgeDirection side:ForgeDirection.VALID_DIRECTIONS){
                TileCableBus host=cable(world,coordinate,64,coordinate+7);
                AEBasePart part=part(host,side,type);
                float hitX=side.offsetX==0?.5F:side.offsetX>0?1F:0F;
                float hitY=side.offsetY==0?.5F:side.offsetY>0?1F:0F;
                float hitZ=side.offsetZ==0?.5F:side.offsetZ>0?1F:0F;
                ProductionTarget selected=ProductionTarget.hit(world,host.xCoord,host.yCoord,host.zCoord,side.ordinal(),hitX,hitY,hitZ,null);
                check(selected!=null && selected.identity==part && selected.source.side==side.ordinal(),"native collision geometry selects storage bus at nonzero/negative coordinates: "+type+" "+side);
                check(selected.matches(world),"native selected storage bus retains its pinned part identity");
            }
    }
    private static void lifecycle()throws Exception {
        TestWorld world=new TestWorld();
        TileEntity iface=(TileEntity)unsafe.allocateInstance(com.glodblock.github.common.tile.TileFluidInterface.class);world.place(iface,16,64,16);
        SupplySource parent=source(SupplySource.AE_MACHINE,16,64,16,-1);
        TileCableBus itemHost=cable(world,17,64,16);part(itemHost,ForgeDirection.WEST,"appeng.parts.misc.PartStorageBus");
        TileCableBus fluidHost=cable(world,16,64,15);part(fluidHost,ForgeDirection.SOUTH,"com.glodblock.github.common.parts.PartFluidStorageBus");
        TileCableBus nonBus=cable(world,16,65,16);part(nonBus,ForgeDirection.DOWN,"appeng.parts.automation.PartImportBus");
        SupplySource item=source(SupplySource.AE_BUS,17,64,16,4),fluid=source(SupplySource.AE_BUS,16,64,15,3);
        ProductionLines lines=new ProductionLines();SupplyLedger ledger=new SupplyLedger();
        String a=create(lines,ledger,"化工一线"),b=create(lines,ledger,"化工二线");
        lines.mutate(action("bind",a,parent),ledger);
        java.util.function.Function<SupplySource,ProductionAttachments.Scan> scanner=s->ProductionAttachments.scan(world,s);
        lines.refreshAttachments(scanner,32);
        check(lines.binding(item).getBoolean("autoBound") && lines.binding(fluid).getString("lineId").equals(a),"both storage bus channels inherit the bound two-in-one interface");
        check(lines.snapshot(ledger,a,"",10,0,0,0,30000).getInteger("sourceCount")==3,"unobserved item and fluid buses are listed, unrelated import bus is excluded");
        ItemKey iron=ItemKey.of(new ItemStack(Items.iron_ingot));FluidKey water=FluidKey.of(new FluidStack(FluidRegistry.WATER,1));
        ledger.record(iron,item,100,false,0);ledger.record(water,fluid,2000,false,0);ledger.record(iron,parent,20,true,0);
        NBTTagCompound totals=lines.snapshot(ledger,a,"",10,0,0,0,30000).getCompoundTag("totals");
        check(totals.getLong("itemOut")==100 && totals.getLong("fluidOut")==2000 && totals.getLong("itemIn")==20,"line merges inlet issuance and interface returns exactly once");
        check(lines.snapshot(ledger,"","",10,0,0,0,30000).getCompoundTag("totals").equals(totals),"global aggregation does not count inherited membership as additional transfers");
        check(lines.snapshot(ledger,a,fluid.id(),10,0,0,0,30000).getCompoundTag("totals").getLong("fluidOut")==2000,"automatic bus retains its own resource detail");
        lines.mutate(action("bind",b,parent),ledger);
        check(lines.binding(item).getString("lineId").equals(b) && lines.snapshot(ledger,a,"",10,0,0,0,30000).getInteger("sourceCount")==0,"changing interface line moves inherited buses without old-line residue");
        lines.mutate(action("bind",a,item),ledger);
        lines.refreshAttachments(scanner,32);
        check(!lines.binding(item).getBoolean("autoBound") && lines.binding(item).getString("lineId").equals(a),"explicit manual bus assignment takes precedence and is not overwritten");
        check(!lines.mutate(action("unbind","",fluid),ledger).getBoolean("ok"),"automatic bus cannot silently unbind and reappear on next refresh");
        lines.mutate(action("unbind","",item),ledger);lines.refreshRelated(item,scanner);
        check(lines.binding(item).getBoolean("autoBound") && lines.binding(item).getString("lineId").equals(b),"removing manual override restores interface inheritance");
        NBTTagCompound saved=lines.write();check(saved.getTagList("bindings",10).tagCount()==1,"only original interface binding is saved; disk schema has no automatic child records");
        ProductionLines restored=new ProductionLines();restored.read(saved);restored.refreshAttachments(scanner,32);
        check(restored.binding(fluid).getString("lineId").equals(b),"old saved interface bindings regain automatic buses after restart without rebinding");
        restored.refreshAttachments(s->{throw new IllegalStateException("temporarily unavailable host");},32);
        check(restored.binding(item).getBoolean("autoBound"),"failed host inspection preserves membership and cannot interrupt server tick");
        world.unloaded.add(key(16,64,16));int reads=world.reads;restored.refreshAttachments(scanner,32);
        check(restored.binding(item).getBoolean("autoBound") && reads==world.reads,"unloaded parent retains known metadata and never reads or loads its chunk");world.unloaded.clear();
        world.unloaded.add(key(16,64,15));world.tiles.remove(key(16,64,15));restored.refreshAttachments(scanner,32);
        check(restored.binding(fluid).getBoolean("autoBound"),"unloaded neighbor does not falsely detach its bus");world.unloaded.clear();restored.refreshAttachments(scanner,32);
        check(restored.binding(fluid).getString("lineId").isEmpty(),"confirmed removed storage bus loses inherited membership");
        world.place(fluidHost,16,64,15);restored.refreshAttachments(scanner,32);
        check(restored.binding(fluid).getBoolean("autoBound"),"new or replaced storage bus is discovered after initial binding");
        restored.mutate(action("bind",a,item),ledger);restored.mutate(action("unbind","",parent),ledger);
        check(restored.binding(fluid).getString("lineId").isEmpty() && restored.binding(item).getString("lineId").equals(a),"unbinding interface removes only inherited membership and preserves explicit override");
        restored.mutate(action("bind",b,parent),ledger);restored.refreshAttachments(scanner,32);
        world.place(new TileEntity(),16,64,16);restored.refreshAttachments(scanner,32);
        check(restored.binding(fluid).getString("lineId").isEmpty(),"loaded replacement that is not an interface detaches automatic children");
        restored.mutate(action("bind",b,source(SupplySource.AE_MACHINE,30,64,30,-1)),ledger);
        restored.mutate(action("bind",b,source(SupplySource.AE_MACHINE,40,64,40,-1)),ledger);
        AtomicInteger calls=new AtomicInteger();restored.refreshAttachments(s->{calls.incrementAndGet();return null;},2);
        check(calls.get()==2,"periodic discovery obeys its fixed parent scan budget");
        check(ProductionAttachments.scan(source(SupplySource.AE_MACHINE,0,64,0,-1))==null,"missing dimension is not initialized while discovering neighbors");
    }
    private static void panels()throws Exception {
        for(String type:new String[]{"appeng.tile.misc.TileInterface","com.glodblock.github.common.tile.TileFluidInterface"}){
            TestWorld world=new TestWorld();TileEntity iface=(TileEntity)unsafe.allocateInstance(Class.forName(type));world.place(iface,50,64,50);
            TileCableBus east=cable(world,51,64,50);part(east,ForgeDirection.WEST,"appeng.parts.misc.PartStorageBus");
            TileCableBus west=cable(world,49,64,50);part(west,ForgeDirection.EAST,"com.glodblock.github.common.parts.PartFluidStorageBus");
            ProductionAttachments.Scan scan=ProductionAttachments.scan(world,source(SupplySource.AE_MACHINE,50,64,50,-1));
            check(scan.interfacePresent && scan.buses.size()==2,"both block interface variants inherit item and fluid buses: "+type);
        }
        for(String type:new String[]{"appeng.parts.misc.PartInterface","com.glodblock.github.common.parts.PartFluidInterface"}){
            TestWorld world=new TestWorld();TileCableBus iface=cable(world,50,64,50);part(iface,ForgeDirection.EAST,type);
            TileCableBus east=cable(world,51,64,50);part(east,ForgeDirection.WEST,"appeng.parts.misc.PartStorageBus");
            TileCableBus west=cable(world,49,64,50);part(west,ForgeDirection.EAST,"appeng.parts.misc.PartStorageBus");
            ProductionAttachments.Scan scan=ProductionAttachments.scan(world,source(SupplySource.AE_MACHINE,50,64,50,5));
            check(scan.interfacePresent && scan.buses.size()==1 && scan.buses.get(0).x==51,"panel interface inherits only storage bus facing that panel: "+type);
        }
    }
    private static TileCableBus cable(TestWorld world,int x,int y,int z){TileCableBus host=new TileCableBus();world.place(host,x,y,z);return host;}
    private static AEBasePart part(TileCableBus host,ForgeDirection side,String type)throws Exception {
        AEBasePart part=(AEBasePart)unsafe.allocateInstance(Class.forName(type));
        for(Object[] setting:new Object[][]{{"host",host},{"side",side},{"is",new ItemStack(Items.paper)}}){Field f=AEBasePart.class.getDeclaredField((String)setting[0]);f.setAccessible(true);f.set(part,setting[1]);}
        Method method=CableBusStorage.class.getDeclaredMethod("setSide",ForgeDirection.class,appeng.api.parts.IPart.class);method.setAccessible(true);method.invoke(host.getCableBus(),side,part);return part;
    }
    private static SupplySource source(int kind,int x,int y,int z,int side){return new SupplySource(kind,0,x,y,z,side,"");}
    private static String create(ProductionLines lines,SupplyLedger ledger,String name){NBTTagCompound tag=action("create","",null);tag.setString("name",name);return lines.mutate(tag,ledger).getString("lineId");}
    private static NBTTagCompound action(String action,String line,SupplySource source){NBTTagCompound tag=new NBTTagCompound();tag.setString("action",action);tag.setString("lineId",line);if(source!=null)tag.setTag("source",source.write());return tag;}
    private static String key(int x,int y,int z){return x+":"+y+":"+z;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static final class TestWorld extends World {
        final Map<String,TileEntity> tiles=new HashMap<>();final Set<String> unloaded=new HashSet<>();int reads;
        TestWorld(){super(new SaveHandlerMP(),"attachment-check",new WorldSettings(0,WorldSettings.GameType.SURVIVAL,false,false,WorldType.DEFAULT),new WorldProviderSurface(),new Profiler());}
        void place(TileEntity tile,int x,int y,int z){tile.setWorldObj(this);tile.xCoord=x;tile.yCoord=y;tile.zCoord=z;tiles.put(key(x,y,z),tile);}
        @Override protected IChunkProvider createChunkProvider(){return null;}
        @Override protected int func_152379_p(){return 0;}
        @Override public Entity getEntityByID(int id){return null;}
        @Override public boolean blockExists(int x,int y,int z){return !unloaded.contains(key(x,y,z));}
        @Override public TileEntity getTileEntity(int x,int y,int z){if(!blockExists(x,y,z))throw new AssertionError("unloaded tile read");reads++;return tiles.get(key(x,y,z));}
        @Override public Block getBlock(int x,int y,int z){return Blocks.stone;}
    }
}
