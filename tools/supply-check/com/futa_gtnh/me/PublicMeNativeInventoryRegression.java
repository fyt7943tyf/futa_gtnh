package com.futa_gtnh.me;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import com.futa_gtnh.Config;
import com.futa_gtnh.ae2.AeSupplyTracking;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.FluidKey;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.parts.IPartHost;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMENetworkInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.me.cache.GridStorageCache;
import appeng.me.cache.ItemFlowGridCache;
import appeng.me.cache.NetworkMonitor;
import appeng.me.cache.SecurityCache;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.storage.MEMonitorPassThrough;
import appeng.me.storage.NetworkInventoryHandler;
import appeng.parts.misc.PartStorageBus;
import appeng.tile.grid.AENetworkTile;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import sun.misc.Unsafe;

/** Exercise the pinned native inventory processor exposed through the subnet storage-bus fast path. */
public final class PublicMeNativeInventoryRegression {
    private static int checks;
    private static Unsafe unsafe;
    public static void run(World world)throws Exception {
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
        GridStorageCache[] cacheRef={null};ItemFlowGridCache flow=new ItemFlowGridCache(null);
        IGrid main=(IGrid)Proxy.newProxyInstance(IGrid.class.getClassLoader(),new Class<?>[]{IGrid.class},(p,m,a)->{
            if(m.getName().equals("getCache")){if(a[0]==IStorageGrid.class)return cacheRef[0];if(a[0]==ItemFlowGridCache.class)return flow;}
            return defaultValue(p,m.getName(),m.getReturnType(),a);
        });
        GridStorageCache cache=new GridStorageCache(main);cacheRef[0]=cache;
        NetworkInventoryHandler<IAEItemStack> inventory=new NetworkInventoryHandler<>(AEItemStackType.ITEM_STACK_TYPE,new SecurityCache(main));
        Cell cell=new Cell();cell.amount=100;inventory.addNewStorage(cell);
        Monitor monitor=new Monitor(cache);
        ((Map)field(GridStorageCache.class,"inventoryHandlers").get(cache)).put(AEItemStackType.ITEM_STACK_TYPE,inventory);
        ((Map)field(GridStorageCache.class,"monitors").get(cache)).put(AEItemStackType.ITEM_STACK_TYPE,monitor);
        NetworkInventoryHandler<IAEFluidStack> fluidInventory=new NetworkInventoryHandler<>(AEFluidStackType.FLUID_STACK_TYPE,new SecurityCache(main));
        FluidCell fluidCell=new FluidCell();fluidCell.amount=10000;fluidInventory.addNewStorage(fluidCell);
        NetworkMonitor<IAEFluidStack> fluidMonitor=new NetworkMonitor<IAEFluidStack>(cache,AEFluidStackType.FLUID_STACK_TYPE){
            @Override protected void postChange(boolean add,Iterable<IAEStack<?>> changes,BaseActionSource source){}
        };
        ((Map)field(GridStorageCache.class,"inventoryHandlers").get(cache)).put(AEFluidStackType.FLUID_STACK_TYPE,fluidInventory);
        ((Map)field(GridStorageCache.class,"monitors").get(cache)).put(AEFluidStackType.FLUID_STACK_TYPE,fluidMonitor);
        installCore(main);
        Bus bus=(Bus)unsafe.allocateInstance(Bus.class);bus.location=new TileEntity();bus.location.setWorldObj(world);bus.location.xCoord=100;bus.location.yCoord=64;
        bus.partHost=(IPartHost)Proxy.newProxyInstance(IPartHost.class.getClassLoader(),new Class<?>[]{IPartHost.class},(p,m,a)->m.getName().equals("getTile")?bus.location:defaultValue(p,m.getName(),m.getReturnType(),a));
        bus.connected=main;
        Bus fluidBus=(Bus)unsafe.allocateInstance(Bus.class);fluidBus.fluid=true;fluidBus.connected=main;
        fluidBus.location=new TileEntity();fluidBus.location.setWorldObj(world);fluidBus.location.xCoord=101;fluidBus.location.yCoord=64;
        fluidBus.partHost=(IPartHost)Proxy.newProxyInstance(IPartHost.class.getClassLoader(),new Class<?>[]{IPartHost.class},(p,m,a)->m.getName().equals("getTile")?fluidBus.location:defaultValue(p,m.getName(),m.getReturnType(),a));
        List<Bus> buses=new ArrayList<>();buses.add(bus);buses.add(fluidBus);
        IGrid subnet=grid(buses);
        for(Bus entry:buses)entry.node=(IGridNode)Proxy.newProxyInstance(IGridNode.class.getClassLoader(),new Class<?>[]{IGridNode.class},(p,m,a)->m.getName().equals("getGrid")?subnet:m.getName().equals("getMachine")?entry:defaultValue(p,m.getName(),m.getReturnType(),a));
        Machine machine=new Machine();machine.setWorldObj(world);machine.xCoord=200;machine.yCoord=64;machine.node=node(subnet);
        MachineSource source=new MachineSource(machine);
        NetworkInventoryHandler<IAEItemStack> child=new NetworkInventoryHandler<>(AEItemStackType.ITEM_STACK_TYPE,new SecurityCache(subnet));
        IMEInventoryHandler<IAEItemStack> inlet=AeSupplyTracking.bus(new MEMonitorPassThrough<>(monitor,AEItemStackType.ITEM_STACK_TYPE),AEItemStackType.ITEM_STACK_TYPE,()->AccessRestriction.READ_WRITE,bus);
        child.addNewStorage(inlet);
        Config.enableSupplyTracking=true;SupplyTracker.start((File)null);AeSupplyTracking.clear();
        try{
            ItemKey iron=ItemKey.of(new ItemStack(Items.iron_ingot));
            check(inventory instanceof PublicMeTrackedInventory,"native inventory marker is transformed");
            check(PublicMeFlowTracking.inventoryGrid(inventory)==main,"native public inventory is identified without traversing any subnet");
            check(inlet.getExternalNetworkInventory()==inventory,"storage bus exposes the actual parent native inventory for optimized requests");
            check(child.getAvailableItems(AEItemStackType.ITEM_STACK_TYPE.createList(),1).findPrecise(stack(1)).getStackSize()==100,"subnet lists stock through interface-style pass-through");
            monitor.extractItems(stack(7),Actionable.MODULATE,source);
            check(cell.amount==93 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==7,"ordinary public monitor path conserves and records seven items: stock="+cell.amount+", recorded="+SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing);
            IMENetworkInventory<IAEItemStack> optimized=inlet.getExternalNetworkInventory();
            check(optimized.extractItems(stack(13),Actionable.MODULATE,source).getStackSize()==13 && cell.amount==80,"optimized subnet path actually takes thirteen more items");
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==20,"optimized parent handler withdrawal must be recorded, not lost outside monitorDifference");
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).rows.get(0).source.kind==SupplySource.AE_BUS,"optimized withdrawal resolves the physical subnet storage bus");
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).rows.get(0).source.x==100,"fluid inlet does not make the item inlet ambiguous");
            FluidKey water=FluidKey.of(new FluidStack(FluidRegistry.WATER,1));
            fluidMonitor.extractItems(fluid(1250),Actionable.MODULATE,source);
            fluidInventory.extractItems(fluid(750),Actionable.MODULATE,source);
            check(fluidCell.amount==8000 && SupplyTracker.LEDGER.snapshot(water,10,0).outgoing==2000,"ordinary and optimized fluid withdrawals count exactly once");
            check(SupplyTracker.LEDGER.snapshot(water,10,0).rows.size()==1 && SupplyTracker.LEDGER.snapshot(water,10,0).rows.get(0).source.x==101,"same machine source caches item and fluid inlets separately");
            check(child.extractItems(stack(5),Actionable.MODULATE,source).getStackSize()==5 && cell.amount==75,"ordinary child transfer still extracts actual stock");
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==25,"child wrapper plus parent monitor plus native hook count only once");
            optimized.extractItems(stack(11),Actionable.SIMULATE,source);
            fluidInventory.extractItems(fluid(1100),Actionable.SIMULATE,source);
            optimized.injectItems(stack(12),Actionable.SIMULATE,source);
            check(cell.amount==75 && fluidCell.amount==8000 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==25 && SupplyTracker.LEDGER.snapshot(water,10,0).outgoing==2000,"simulation does not mutate stock or record rates");
            check(optimized.extractItems(stack(1000),Actionable.MODULATE,source).getStackSize()==75,"partial withdrawal returns only remaining stock");
            check(cell.amount==0 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==100,"empty stock remains searchable with conserved full consumption");
            check(optimized.extractItems(stack(1),Actionable.MODULATE,source)==null && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==100,"rejected withdrawal records nothing");
            cell.capacity=12;
            check(optimized.injectItems(stack(20),Actionable.MODULATE,source).getStackSize()==8 && cell.amount==12,"partial insertion preserves actual accepted quantity");
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).incoming==12,"only twelve accepted items are recorded as return");
            check(optimized.injectItems(stack(3),Actionable.MODULATE,source).getStackSize()==3 && SupplyTracker.LEDGER.snapshot(iron,10,0).incoming==12,"full cell rejection does not inflate return rates");
            PublicMeFlowTracking.suppress(()->optimized.extractItems(stack(2),Actionable.MODULATE,source));
            check(cell.amount==10 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==100,"existing explicit Futa accounting suppression is preserved");
            cell.fail=true;
            try{optimized.extractItems(stack(1),Actionable.MODULATE,source);throw new AssertionError("native exception must propagate");}
            catch(IllegalStateException expected){check(cell.amount==10 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==100,"native failure propagates without inventing a transfer");}
            finally{cell.fail=false;((ThreadLocal<?>)field(NetworkInventoryHandler.class,"DEPTH_MOD").get(null)).remove();}
            optimized.extractItems(stack(1),Actionable.MODULATE,source);
            check(cell.amount==9 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==101,"analytic recursion depth is restored even when the native handler throws");
            NetworkInventoryHandler<IAEItemStack> privateInventory=new NetworkInventoryHandler<>(AEItemStackType.ITEM_STACK_TYPE,new SecurityCache(main));
            Cell privateCell=new Cell();privateCell.amount=10;privateInventory.addNewStorage(privateCell);
            check(PublicMeFlowTracking.inventoryGrid(privateInventory)==null,"a different native handler is not mistaken for the current public inventory");
            privateInventory.extractItems(stack(3),Actionable.MODULATE,source);
            check(privateCell.amount==7 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==101,"private inventory semantics are unchanged and excluded from public rates");
            Config.enableSupplyTracking=false;optimized.extractItems(stack(1),Actionable.MODULATE,source);Config.enableSupplyTracking=true;
            check(cell.amount==8 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==101,"disabled diagnostics do not alter real inventory behavior");
            fluidInventory.extractItems(fluid(20000),Actionable.MODULATE,source);
            check(fluidCell.amount==0 && SupplyTracker.LEDGER.snapshot(water,10,0).outgoing==10000,"zero-stock fluid retains its complete consumption history");
            check(SupplyTracker.LEDGER.snapshot(water,10,0).rows.get(0).source.x==101,"alternating item/fluid operations retain the correct physical IO");
        } finally {PublicMeNetwork.stop();SupplyTracker.stop();AeSupplyTracking.clear();}
        System.out.println("Public ME native inventory: "+checks+" checks passed");
    }
    private static void installCore(IGrid grid)throws Exception {
        IGridNode node=node(grid);UUID id=UUID.randomUUID();TilePublicMeCore core=new TilePublicMeCore();core.bind(id,0);
        WorldServer anchor=(WorldServer)unsafe.allocateInstance(WorldServer.class);core.setWorldObj(anchor);core.xCoord=7;core.yCoord=250;core.zCoord=8;
        field(AENetworkTile.class,"gridProxy").set(core,new AENetworkProxy(core,"fixture",null,false){@Override public IGridNode getNode(){return node;}});
        PublicMeWorldData saved=new PublicMeWorldData(PublicMeWorldData.NAME);saved.storageId=id;saved.located=true;saved.x=8;saved.y=250;saved.z=8;
        field(PublicMeNetwork.class,"world").set(null,anchor);field(PublicMeNetwork.class,"saved").set(null,saved);
        field(PublicMeNetwork.class,"storageId").set(null,id);field(PublicMeNetwork.class,"running").setBoolean(null,true);
        ((TilePublicMeCore[])field(PublicMeNetwork.class,"PORTS").get(null))[0]=core;
    }
    private static IGrid grid(List<Bus> buses){return (IGrid)Proxy.newProxyInstance(IGrid.class.getClassLoader(),new Class<?>[]{IGrid.class},(p,m,a)->{
        if(m.getName().equals("getMachinesClasses"))return collection(Collections.singletonList(Bus.class),m.getReturnType());
        if(m.getName().equals("getMachines")){List<IGridNode> nodes=new ArrayList<>();for(Bus bus:buses)nodes.add(bus.node);return collection(nodes,m.getReturnType());}
        return defaultValue(p,m.getName(),m.getReturnType(),a);
    });}
    private static Object collection(List<?> values,Class<?> type){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        if(m.getName().equals("iterator"))return values.iterator();if(m.getName().equals("size"))return values.size();if(m.getName().equals("isEmpty"))return values.isEmpty();return defaultValue(p,m.getName(),m.getReturnType(),a);
    });}
    private static IGridNode node(IGrid grid){return (IGridNode)Proxy.newProxyInstance(IGridNode.class.getClassLoader(),new Class<?>[]{IGridNode.class},(p,m,a)->m.getName().equals("getGrid")?grid:defaultValue(p,m.getName(),m.getReturnType(),a));}
    private static Object defaultValue(Object p,String name,Class<?> type,Object[] a){if(name.equals("equals"))return p==a[0];if(name.equals("hashCode"))return System.identityHashCode(p);if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static IAEItemStack stack(long amount){return AEItemStack.create(new ItemStack(Items.iron_ingot)).setStackSize(amount);}
    private static IAEFluidStack fluid(long amount){return AEFluidStack.create(new FluidStack(FluidRegistry.WATER,1)).setStackSize(amount);}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    public static final class Bus extends PartStorageBus {
        TileEntity location;IPartHost partHost;IGrid connected;IGridNode node;boolean fluid;
        public Bus(){super(null);}
        @Override public IPartHost getHost(){return partHost;}
        @Override public ForgeDirection getSide(){return ForgeDirection.EAST;}
        @Override public IGrid getConnectedGrid(){return connected;}
        @Override public IGridNode getActionableNode(){return node;}
        @Override public IAEStackType<?> getStackType(){return fluid?AEFluidStackType.FLUID_STACK_TYPE:AEItemStackType.ITEM_STACK_TYPE;}
    }
    private static final class Machine extends TileEntity implements IActionHost {
        IGridNode node;
        @Override public IGridNode getGridNode(ForgeDirection face){return node;}
        @Override public IGridNode getActionableNode(){return node;}
        @Override public appeng.api.util.AECableType getCableConnectionType(ForgeDirection face){return appeng.api.util.AECableType.SMART;}
        @Override public void securityBreak(){}
    }
    private static final class Monitor extends NetworkMonitor<IAEItemStack> {
        Monitor(GridStorageCache cache){super(cache,AEItemStackType.ITEM_STACK_TYPE);}
        @Override protected void postChange(boolean add,Iterable<IAEStack<?>> changes,BaseActionSource source){}
    }
    private static final class Cell implements IMEInventoryHandler<IAEItemStack> {
        long amount,capacity=Long.MAX_VALUE;boolean fail;
        @Override public IAEItemStack extractItems(IAEItemStack request,Actionable mode,BaseActionSource source){if(fail)throw new IllegalStateException("fixture failure");long n=Math.min(amount,request.getStackSize());if(mode==Actionable.MODULATE)amount-=n;return n==0?null:request.copy().setStackSize(n);}
        @Override public IAEItemStack injectItems(IAEItemStack input,Actionable mode,BaseActionSource source){long n=Math.min(input.getStackSize(),capacity-amount);if(mode==Actionable.MODULATE)amount+=n;return n==input.getStackSize()?null:input.copy().setStackSize(input.getStackSize()-n);}
        @Override public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> result,int iteration){if(amount>0)result.addStorage(stack(amount));return result;}
        @Override public IAEStackType<?> getStackType(){return AEItemStackType.ITEM_STACK_TYPE;}
        @Override public AccessRestriction getAccess(){return AccessRestriction.READ_WRITE;}
        @Override public boolean isPrioritized(IAEItemStack value){return false;}
        @Override public boolean canAccept(IAEItemStack value){return true;}
        @Override public int getPriority(){return 0;}
        @Override public int getSlot(){return 0;}
        @Override public boolean validForPass(int pass){return true;}
    }
    private static final class FluidCell implements IMEInventoryHandler<IAEFluidStack> {
        long amount;
        @Override public IAEFluidStack extractItems(IAEFluidStack request,Actionable mode,BaseActionSource source){long n=Math.min(amount,request.getStackSize());if(mode==Actionable.MODULATE)amount-=n;return n==0?null:request.copy().setStackSize(n);}
        @Override public IAEFluidStack injectItems(IAEFluidStack input,Actionable mode,BaseActionSource source){if(mode==Actionable.MODULATE)amount+=input.getStackSize();return null;}
        @Override public IItemList<IAEFluidStack> getAvailableItems(IItemList<IAEFluidStack> result,int iteration){if(amount>0)result.addStorage(fluid(amount));return result;}
        @Override public IAEStackType<?> getStackType(){return AEFluidStackType.FLUID_STACK_TYPE;}
        @Override public AccessRestriction getAccess(){return AccessRestriction.READ_WRITE;}
        @Override public boolean isPrioritized(IAEFluidStack value){return false;}
        @Override public boolean canAccept(IAEFluidStack value){return true;}
        @Override public int getPriority(){return 0;}
        @Override public int getSlot(){return 0;}
        @Override public boolean validForPass(int pass){return true;}
    }
}
