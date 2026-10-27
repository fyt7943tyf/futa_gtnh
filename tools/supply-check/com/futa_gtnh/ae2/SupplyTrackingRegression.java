package com.futa_gtnh.ae2;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.Entity;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;
import com.futa_gtnh.Config;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.supply.SupplyLedger;
import com.futa_gtnh.supply.SupplyLedgerRegression;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.parts.IPartHost;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.IReadOnlyCollection;
import appeng.parts.misc.PartStorageBus;
import appeng.util.item.AEItemStackType;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

/** Real pinned AE storage-bus Mixin, real shared-cell mutations and bounded cross-network attribution. */
public final class SupplyTrackingRegression {
    private static int checks;
    private static Object unsafe;
    private static Method allocate;
    private static ItemKey IRON;
    private static World world;

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(net.minecraft.launchwrapper.Launch.classLoader);
        Loader.injectData(new Object[] {"7","99","40","1614","1.7.10","9.05",new File("."),Collections.emptyList()});
        Field side=FMLRelaunchLog.class.getDeclaredField("side"); side.setAccessible(true); side.set(null,Side.SERVER);
        Field mods=Loader.class.getDeclaredField("namedMods"); mods.setAccessible(true); mods.set(Loader.instance(),new HashMap<>());
        Bootstrap.func_151354_b();
        cpw.mods.fml.common.LoadController controller = new cpw.mods.fml.common.LoadController(Loader.instance());
        Field container = cpw.mods.fml.common.LoadController.class.getDeclaredField("activeContainer"); container.setAccessible(true);
        container.set(controller, new cpw.mods.fml.common.DummyModContainer(new cpw.mods.fml.common.ModMetadata()));
        Field control = Loader.class.getDeclaredField("modController"); control.setAccessible(true); control.set(Loader.instance(), controller);
        File configDirectory = new File("build/supply-check/ae-config"); configDirectory.mkdirs();
        Field home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome"); home.setAccessible(true);
        home.set(null, new File("build/supply-check"));
        appeng.core.AEConfig.instance = new appeng.core.AEConfig(new File(configDirectory, "AE2.cfg"));
        IRON=ItemKey.of(new ItemStack(Items.iron_ingot));
        Class<?> type=Class.forName("sun.misc.Unsafe"); Field f=type.getDeclaredField("theUnsafe"); f.setAccessible(true);
        unsafe=f.get(null); allocate=type.getMethod("allocateInstance",Class.class);
        world=new MemoryWorld();
        SupplyLedgerRegression.run();
        transform();
        realCellAndBoundary();
        optimizedAttribution();
        com.futa_gtnh.supply.SupplyIoRegression.run();
        com.futa_gtnh.supply.SupplyNavigationRegression.run();
        com.futa_gtnh.me.PublicMeRegression.run(world);
        com.futa_gtnh.me.PublicMeNativeInventoryRegression.run(world);
        com.futa_gtnh.shared.NativeMeMigrationRegression.run();
        com.futa_gtnh.supply.ProductionTerminalRegression.run();
        com.futa_gtnh.supply.ProductionAttachmentsRegression.run();
        System.out.println("AE supply tracking: " + checks + " checks passed (real bus Mixin and shared-cell accounting)");
    }

    private static void check(boolean okay,String text) { if(!okay) throw new AssertionError(text); checks++; }
    private static void reset() {
        Config.enableSupplyTracking=true; SupplyTracker.start((File)null); AeSupplyTracking.clear();
    }
    private static void transform() throws Exception {
        Class<?> type=Class.forName("appeng.parts.misc.PartStorageBus",false,net.minecraft.launchwrapper.Launch.classLoader);
        boolean found=false;
        for(Method method:type.getDeclaredMethods()) if(method.getName().contains("futa$supplyInlet")) found=true;
        check(found,"native AE storage bus constructor redirect transforms with require=1");
    }

    private static FakeBus bus(int x,ForgeDirection side) throws Exception {
        FakeBus bus=(FakeBus)allocate.invoke(unsafe,FakeBus.class);
        bus.location=new TileEntity(); bus.location.setWorldObj(world); bus.location.xCoord=x; bus.location.yCoord=64;
        bus.facing=side;
        bus.partHost=(IPartHost)Proxy.newProxyInstance(IPartHost.class.getClassLoader(),new Class<?>[]{IPartHost.class},
            (p,m,a)->m.getName().equals("getTile")?bus.location:defaultValue(p,m.getName(),m.getReturnType(),a));
        return bus;
    }

    private static IMEInventoryHandler<IAEItemStack> wrapped(SharedItemCellInventory cell,FakeBus bus) {
        return AeSupplyTracking.bus(cell,AEItemStackType.ITEM_STACK_TYPE,()->AccessRestriction.READ_WRITE,bus);
    }

    private static void realCellAndBoundary() throws Exception {
        reset();
        Field active=SharedStorageManager.class.getDeclaredField("storage"); active.setAccessible(true);
        Object previous=active.get(null); SharedStorage storage=new SharedStorage(); active.set(null,storage);
        try {
            storage.insertItemManual(IRON,1000);
            SharedItemCellInventory cell=new SharedItemCellInventory(new ItemStack(Items.paper),null);
            FakeBus part=bus(100,ForgeDirection.EAST);
            IMEInventoryHandler<IAEItemStack> bus=wrapped(cell,part);
            check(bus.extractItems(stack(2000),Actionable.SIMULATE,null).getStackSize()==1000,"simulate still reports available stock");
            check(SupplyTracker.LEDGER.keyCount()==0 && storage.getItemAmount(IRON)==1000,"simulate never records or modifies stock");
            IAEItemStack taken=bus.extractItems(stack(64),Actionable.MODULATE,null);
            check(taken.getStackSize()==64 && storage.getItemAmount(IRON)==936,"real extraction semantics unchanged");
            SupplyLedger.Snapshot s=SupplyTracker.LEDGER.snapshot(IRON,10,0);
            check(s.rows.size()==1 && s.rows.get(0).source.kind==SupplySource.AE_BUS && s.rows.get(0).source.x==100
                && s.rows.get(0).source.side==ForgeDirection.EAST.ordinal(),"shared cell attributes the actual extraction to subnet bus position and face");
            bus.injectItems(stack(16),Actionable.MODULATE,null);
            s=SupplyTracker.LEDGER.snapshot(IRON,10,0);
            check(s.outgoing==64 && s.incoming==16 && s.rows.size()==1,"same-inlet return recorded separately for net issuance");
            bus.injectItems(stack(16),Actionable.SIMULATE,null);
            check(SupplyTracker.LEDGER.snapshot(IRON,10,0).incoming==16,"simulation of returns is also ignored");
            FakeBus outer=bus(200,ForgeDirection.WEST);
            IMEInventoryHandler<IAEItemStack> nested=AeSupplyTracking.bus(bus,AEItemStackType.ITEM_STACK_TYPE,()->AccessRestriction.READ_WRITE,outer);
            nested.extractItems(stack(10),Actionable.MODULATE,null);
            check(SupplyTracker.LEDGER.snapshot(IRON,10,0).rows.size()==1,"nested subnet forwarding is counted once, at the inlet next to shared storage");
            cell.extractItems(stack(5),Actionable.MODULATE,null);
            s=SupplyTracker.LEDGER.snapshot(IRON,10,0);
            check(s.rows.size()==2 && s.rows.get(1).source.kind==SupplySource.UNKNOWN,"thread-local inlet restored after nested extraction");
            Config.enableSupplyTracking=false;
            bus.extractItems(stack(3),Actionable.MODULATE,null);
            check(SupplyTracker.LEDGER.snapshot(IRON,10,0).outgoing==79,"disabled telemetry leaves real extraction working without records");
            Config.enableSupplyTracking=true;
            part.partHost=null;
            bus.extractItems(stack(2),Actionable.MODULATE,null);
            check(storage.getItemAmount(IRON)==932,"detached telemetry host cannot break an otherwise valid inventory extraction");
        } finally { active.set(null,previous); SupplyTracker.stop(); AeSupplyTracking.clear(); }
    }

    private static void optimizedAttribution() throws Exception {
        reset();
        IGrid main=grid(new ArrayList<>(),new AtomicInteger());
        FakeBus part=bus(321,ForgeDirection.NORTH); part.connected=main;
        List<FakeBus> buses=new ArrayList<>(); buses.add(part);
        AtomicInteger scans=new AtomicInteger(); IGrid subnet=grid(buses,scans);
        FakeMachine host=new FakeMachine(); host.setWorldObj(world); host.xCoord=999; host.current=subnet;
        BaseActionSource source=new MachineSource(host);
        for(int i=0;i<1000;i++) AeSupplyTracking.record(IRON,1,false,source,main);
        SupplyLedger.Snapshot s=SupplyTracker.LEDGER.snapshot(IRON,10,0);
        check(s.rows.get(0).source.kind==SupplySource.AE_BUS && s.rows.get(0).source.x==321,
            "direct/optimized parent-inventory requests resolve the unique subnet inlet");
        check(scans.get()==1,"one-hop lookup cached for repeated transfers; no repeated network traversal");
        IGrid changed=grid(new ArrayList<>(),new AtomicInteger()); host.current=changed;
        AeSupplyTracking.record(IRON,1,false,source,main);
        s=SupplyTracker.LEDGER.snapshot(IRON,10,0);
        check(s.rows.size()==2 && s.rows.get(1).source.kind==SupplySource.AE_MACHINE,"requester grid replacement invalidates attribution cache");
        FakeBus second=bus(444,ForgeDirection.UP); second.connected=main; buses.add(second); host.current=subnet;
        AeSupplyTracking.clear(); AeSupplyTracking.record(IRON,1,false,source,main);
        s=SupplyTracker.LEDGER.snapshot(IRON,10,0);
        check(s.rows.get(0).outgoing==1000,"ambiguous shared interface routes are not guessed");
        check(SupplyTracker.LEDGER.snapshot(IRON,10,0).outgoing==1002,"unresolved entries remain part of total accounting");
        SupplyTracker.stop(); AeSupplyTracking.clear();
    }

    @SuppressWarnings("unchecked")
    private static IGrid grid(List<FakeBus> buses,AtomicInteger scans) {
        return (IGrid)Proxy.newProxyInstance(IGrid.class.getClassLoader(),new Class<?>[]{IGrid.class},(p,m,a)-> {
            if(m.getName().equals("getMachinesClasses")) { scans.incrementAndGet(); return collection(Collections.singletonList(FakeBus.class),m.getReturnType()); }
            if(m.getName().equals("getMachines")) {
                List<IGridNode> nodes=new ArrayList<>();
                for(FakeBus bus:buses) nodes.add((IGridNode)Proxy.newProxyInstance(IGridNode.class.getClassLoader(),new Class<?>[]{IGridNode.class},
                    (np,nm,na)->nm.getName().equals("getMachine")?bus:defaultValue(np,nm.getName(),nm.getReturnType(),na)));
                return collection(nodes,m.getReturnType());
            }
            return defaultValue(p,m.getName(),m.getReturnType(),a);
        });
    }
    private static Object collection(List<?> list,Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {
            if(m.getName().equals("iterator")) return list.iterator();
            if(m.getName().equals("size")) return list.size();
            if(m.getName().equals("isEmpty")) return list.isEmpty();
            return defaultValue(p,m.getName(),m.getReturnType(),a);
        });
    }
    private static Object defaultValue(Object proxy,String name,Class<?> result,Object[] args) {
        if(name.equals("hashCode")) return System.identityHashCode(proxy);
        if(name.equals("equals")) return proxy==args[0];
        if(result==boolean.class) return false;
        if(result==int.class) return 0;
        if(result==long.class) return 0L;
        return null;
    }
    private static IAEItemStack stack(long count) {
        long[] amount={count};
        return (IAEItemStack)Proxy.newProxyInstance(IAEItemStack.class.getClassLoader(),new Class<?>[]{IAEItemStack.class},(p,m,a)-> {
            switch(m.getName()) {
                case "getItemStack": return new ItemStack(Items.iron_ingot);
                case "getStackSize": return amount[0];
                case "setStackSize": amount[0]=(Long)a[0]; return p;
                case "copy": return stack(amount[0]);
                case "getStackType": return AEItemStackType.ITEM_STACK_TYPE;
                default: return defaultValue(p,m.getName(),m.getReturnType(),a);
            }
        });
    }
    public static class FakeBus extends PartStorageBus {
        TileEntity location; ForgeDirection facing; IPartHost partHost; IGrid connected;
        public FakeBus() { super(null); }
        @Override public IPartHost getHost() { return partHost; }
        @Override public ForgeDirection getSide() { return facing; }
        @Override public IGrid getConnectedGrid() { return connected; }
    }
    private static final class FakeMachine extends TileEntity implements IActionHost {
        IGrid current;
        @Override public IGridNode getGridNode(ForgeDirection side) { return getActionableNode(); }
        @Override public appeng.api.util.AECableType getCableConnectionType(ForgeDirection side) { return appeng.api.util.AECableType.SMART; }
        @Override public void securityBreak() {}
        @Override public IGridNode getActionableNode() {
            return (IGridNode)Proxy.newProxyInstance(IGridNode.class.getClassLoader(),new Class<?>[]{IGridNode.class},
                (p,m,a)->m.getName().equals("getGrid")?current:defaultValue(p,m.getName(),m.getReturnType(),a));
        }
    }
    private static final class MemoryWorld extends World {
        MemoryWorld() { super(new SaveHandlerMP(),"supply-check",new WorldSettings(0,WorldSettings.GameType.SURVIVAL,false,false,WorldType.DEFAULT),new WorldProviderSurface(),new Profiler()); }
        @Override protected IChunkProvider createChunkProvider() { return null; }
        @Override protected int func_152379_p() { return 0; }
        @Override public Entity getEntityByID(int id) { return null; }
        @Override public boolean blockExists(int x,int y,int z) { return true; }
    }
}
