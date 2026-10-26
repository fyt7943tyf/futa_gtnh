package com.futa_gtnh.me;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.futa_gtnh.Config;
import com.futa_gtnh.ae2.AeSupplyTracking;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.api.util.AECableType;
import appeng.me.cache.GridStorageCache;
import appeng.me.cache.ItemFlowGridCache;
import appeng.me.cache.NetworkMonitor;
import appeng.me.helpers.AENetworkProxy;
import appeng.tile.grid.AENetworkTile;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import sun.misc.Unsafe;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Runs with the pinned real NetworkMonitor and its required Mixin, without generating or loading a world. */
public final class PublicMeRegression {
    private static int checks;
    private static Unsafe unsafe;
    private PublicMeRegression() {}
    public static void run(World machineWorld) throws Exception {
        Field access=Unsafe.class.getDeclaredField("theUnsafe"); access.setAccessible(true); unsafe=(Unsafe)access.get(null);
        explicitChunkLoad();
        coreBinding();
        fluidNbt();
        nativeMonitor(machineWorld);
        craftingIdentity();
        System.out.println("Public ME runtime: "+checks+" checks passed (native monitor transform, transfer accounting, port NBT and plan identity)");
    }
    private static void check(boolean okay,String description) { if(!okay) throw new AssertionError(description); checks++; }
    private static Field field(Class<?> type,String name) throws Exception { Field field=type.getDeclaredField(name);field.setAccessible(true);return field; }

    private static void explicitChunkLoad() throws Exception {
        WorldServer world=(WorldServer)unsafe.allocateInstance(WorldServer.class);
        ChunkProbe provider=new ChunkProbe(world);provider.loadChunkOnProvideRequest=false;
        check(provider.provideChunk(23,-7) instanceof EmptyChunk,"ordinary chunk lookup can return an empty placeholder outside spawn");
        world.theChunkProviderServer=provider;
        field(PublicMeNetwork.class,"world").set(null,world);
        Method load=PublicMeNetwork.class.getDeclaredMethod("loadChunk",int.class,int.class);load.setAccessible(true);
        try {
            check((Boolean)load.invoke(null,23,-7) && provider.loads==1,"bootstrap explicitly loads a real remote chunk before modifying blocks");
            provider.empty=true;
            check(!(Boolean)load.invoke(null,23,-7),"bootstrap refuses a placeholder even if a provider returns one from loadChunk");
        } finally { PublicMeNetwork.stop(); }
    }

    private static final class ChunkProbe extends ChunkProviderServer {
        int loads; boolean empty;
        ChunkProbe(WorldServer world){super(world,null,null);}
        @Override public Chunk loadChunk(int x,int z){loads++;return empty?new EmptyChunk(worldObj,x,z):new Chunk(worldObj,x,z);}
    }

    private static void fluidNbt() throws Exception {
        boolean transformed=false;
        for(Method method:AEFluidStack.class.getDeclaredMethods()) if(method.getName().contains("futa$preserveFluidTag"))transformed=true;
        check(transformed,"pinned native fluid constructor NBT repair transforms with require=1");
        NBTTagCompound tag=new NBTTagCompound();tag.setString("owner","first");
        NBTTagCompound nested=new NBTTagCompound();nested.setInteger("batch",17);tag.setTag("nested",nested);
        FluidStack source=new FluidStack(FluidRegistry.WATER,37,(NBTTagCompound)tag.copy());
        IAEFluidStack first=AEFluidStack.create(source);
        IAEFluidStack same=AEFluidStack.create(new FluidStack(FluidRegistry.WATER,37,(NBTTagCompound)tag.copy()));
        NBTTagCompound other=(NBTTagCompound)tag.copy();other.setString("owner","second");
        IAEFluidStack second=AEFluidStack.create(new FluidStack(FluidRegistry.WATER,11,other));
        check(first.hasTagCompound() && tag.equals(first.getFluidStack().tag),"native fluid roundtrip preserves complete unmodified NBT");
        check(first.equals(same) && first.hashCode()==same.hashCode(),"equal fluid NBT uses native shared identity and stable hash");
        check(!first.equals(second) && !first.isSameType(second) && !first.isSameType((Object)second),"all native precise identity paths distinguish different fluid tags");
        source.tag.getCompoundTag("nested").setInteger("batch",99);
        first.getFluidStack().tag.setString("owner","mutated");
        check(first.equals(same) && tag.equals(first.getFluidStack().tag),"caller-owned input and returned fluid tags cannot mutate interned identity");
        appeng.util.item.FluidList list=new appeng.util.item.FluidList();list.add(first);list.add(second);
        check(list.size()==2 && list.findPrecise(same).getStackSize()==37 && list.findPrecise(second).getStackSize()==11,"native hash-set fluid inventory does not merge tagged variants");
        check(first.copy().equals(first) && first.empty().equals(first) && first.empty().getStackSize()==0,"copy and empty retain tagged identity");
        NBTTagCompound encoded=new NBTTagCompound();first.writeToNBT(encoded);
        IAEFluidStack restored=AEFluidStack.loadFluidStackFromNBT(encoded);
        check(first.equals(restored) && restored.getStackSize()==37,"native persistent fluid NBT restores both quantity and identity");
        encoded.getCompoundTag("Tag").setString("owner","mutated serialized copy");
        check(first.equals(same),"native serialization cannot expose mutable interned NBT");
        first.writeToNBT(encoded);encoded.setTag("tag",encoded.getTag("Tag"));encoded.removeTag("Tag");
        check(first.equals(AEFluidStack.loadFluidStackFromNBT(encoded)),"legacy lowercase AE fluid NBT remains readable");
        ByteBuf packet=Unpooled.buffer();
        try {first.writeToPacket(packet);IAEFluidStack decoded=AEFluidStack.loadFluidStackFromPacket(packet);
            check(first.equals(decoded) && decoded.getStackSize()==37 && !packet.isReadable(),"native fluid packet preserves tag and quantity exactly");
        } finally {packet.release();}
        check(first.equals(new FluidStack(FluidRegistry.WATER,1,(NBTTagCompound)tag.copy())),"native FluidStack equality remains compatible with interned tags");
        IAEFluidStack changed=first.copy();changed.setTagCompound(other);
        check(changed.equals(second) && changed.hashCode()==second.hashCode(),"native explicit tag setter updates identity and its hash");
        changed.setTagCompound(new NBTTagCompound());IAEFluidStack plain=AEFluidStack.create(new FluidStack(FluidRegistry.WATER,1));
        check(changed.equals(plain) && changed.hashCode()==plain.hashCode() && !changed.hasTagCompound(),"empty fluid tags normalize to plain fluid identity");
    }

    private static void coreBinding() throws Exception {
        UUID id=UUID.randomUUID(); TilePublicMeCore core=new TilePublicMeCore();
        check(core.getProxy().getFlags().contains(GridFlags.DENSE_CAPACITY),"core is a real dense path");
        check(!core.isInfinite(),"unbound manually placed core cannot power a network");
        core.bind(id,0); NBTTagCompound tag=new NBTTagCompound();core.writePublicMe(tag);
        TilePublicMeCore restored=new TilePublicMeCore();restored.readPublicMe(tag);
        check(restored.belongsTo(id,0) && restored.isInfinite(),"persisted primary can supply power before ServerStarted restores the registry");
        restored.bind(id,1);check(!restored.isInfinite() && restored.getAECurrentPower()==0,"other five ports do not create independent power stores");
        check(!restored.belongsTo(UUID.randomUUID(),1),"a different warehouse cannot claim a persisted port");
        TilePublicMeAccess access=new TilePublicMeAccess();
        check(access.getProxy().getFlags().contains(GridFlags.DENSE_CAPACITY),"access node advertises dense transmission");
        check(!access.getProxy().getFlags().contains(GridFlags.REQUIRE_CHANNEL),"the cable bridge is not itself a consuming terminal");
        tag.setInteger("futaPublicMePort",4);access.readPublicMeAccess(tag);NBTTagCompound saved=new NBTTagCompound();access.writePublicMeAccess(saved);
        TilePublicMeAccess loaded=new TilePublicMeAccess();loaded.readPublicMeAccess(saved);
        check(loaded.getPort()==4,"controller-port assignment survives reload");
        check(!((Object)access instanceof appeng.api.storage.ICellProvider),"an access bridge never publishes duplicate storage");
        try { PublicMeFlowTracking.suppress(()->{ check(PublicMeFlowTracking.isSuppressed(),"outer accounting suppression active");
            PublicMeFlowTracking.suppress(()->check(PublicMeFlowTracking.isSuppressed(),"nested accounting suppression active"));
            throw new IllegalStateException("deliberate transfer failure"); }); } catch(IllegalStateException expected) {}
        check(!PublicMeFlowTracking.isSuppressed(),"exception restores accounting context");
    }

    private static void nativeMonitor(World machineWorld) throws Exception {
        boolean transformed=false;
        for(Method method:NetworkMonitor.class.getDeclaredMethods()) if(method.getName().contains("futa$committedNetworkTransfer")) transformed=true;
        check(transformed,"pinned NetworkMonitor monitorDifference hook transforms with require=1");
        ItemFlowGridCache flow=new ItemFlowGridCache(null);
        IGrid grid=(IGrid)Proxy.newProxyInstance(IGrid.class.getClassLoader(),new Class<?>[]{IGrid.class},(p,m,a)-> {
            if(m.getName().equals("getCache") && a[0]==ItemFlowGridCache.class)return flow;
            return defaultValue(p,m.getName(),m.getReturnType(),a);
        });
        IGridNode node=(IGridNode)Proxy.newProxyInstance(IGridNode.class.getClassLoader(),new Class<?>[]{IGridNode.class},
            (p,m,a)->m.getName().equals("getGrid")?grid:defaultValue(p,m.getName(),m.getReturnType(),a));
        TilePublicMeCore core=new TilePublicMeCore();UUID id=UUID.randomUUID();core.bind(id,0);
        WorldServer anchorWorld=(WorldServer)unsafe.allocateInstance(WorldServer.class);core.setWorldObj(anchorWorld);core.xCoord=7;core.yCoord=250;core.zCoord=8;
        AENetworkProxy proxy=new AENetworkProxy(core,"fixture",null,false) { @Override public IGridNode getNode(){return node;} };
        field(AENetworkTile.class,"gridProxy").set(core,proxy);
        PublicMeWorldData saved=new PublicMeWorldData(PublicMeWorldData.NAME);saved.storageId=id;saved.located=true;saved.x=8;saved.y=250;saved.z=8;
        field(PublicMeNetwork.class,"world").set(null,anchorWorld);field(PublicMeNetwork.class,"saved").set(null,saved);
        field(PublicMeNetwork.class,"storageId").set(null,id);field(PublicMeNetwork.class,"running").setBoolean(null,true);
        ((TilePublicMeCore[])field(PublicMeNetwork.class,"PORTS").get(null))[0]=core;
        GridStorageCache cache=(GridStorageCache)unsafe.allocateInstance(GridStorageCache.class);field(GridStorageCache.class,"myGrid").set(cache,grid);
        Machine machine=new Machine();machine.setWorldObj(machineWorld);machine.xCoord=912;machine.yCoord=64;machine.node=node;
        MachineSource source=new MachineSource(machine);
        Config.enableSupplyTracking=true;SupplyTracker.start((File)null);AeSupplyTracking.clear();
        try {
            check(PublicMeNetwork.grid()==grid,"public grid is resolved from the live primary node");
            Inventory<IAEItemStack> items=new Inventory<>(AEItemStackType.ITEM_STACK_TYPE);items.amount=50;items.capacity=100;
            TestMonitor<IAEItemStack> monitor=new TestMonitor<>(cache,AEItemStackType.ITEM_STACK_TYPE,items);items.monitor=monitor;
            ItemKey iron=ItemKey.of(new ItemStack(Items.iron_ingot));
            monitor.extractItems(item(7),Actionable.SIMULATE,source);
            monitor.injectItems(item(90),Actionable.SIMULATE,source);
            check(SupplyTracker.LEDGER.keyCount()==0 && items.amount==50,"native simulation never mutates or records");
            check(monitor.injectItems(item(70),Actionable.MODULATE,source).getStackSize()==20,"native partial insert preserves remainder");
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).incoming==50,"native ordinary-cell insert records only accepted amount");
            monitor.injectItems(item(2),Actionable.MODULATE,source);
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).incoming==50,"fully rejected insert does not record");
            monitor.extractItems(item(20),Actionable.MODULATE,source);
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==20,"native extraction records actual result once");
            items.reenter=true;monitor.extractItems(item(7),Actionable.MODULATE,source);
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==27 && items.amount==73,"recursive same-monitor extraction is counted only at outer return");
            PublicMeFlowTracking.suppress(()->monitor.extractItems(item(3),Actionable.MODULATE,source));
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==27 && items.amount==70,"Futa commit accounting suppression preserves actual native transfer");
            monitor.setLocked(true);monitor.extractItems(item(5),Actionable.MODULATE,source);monitor.setLocked(false);
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==27,"locked network refusal is not production");
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).rows.get(0).source.x==912,"native source keeps the physical machine location");
            Inventory<IAEFluidStack> fluids=new Inventory<>(AEFluidStackType.FLUID_STACK_TYPE);fluids.capacity=1000;
            TestMonitor<IAEFluidStack> fluidMonitor=new TestMonitor<>(cache,AEFluidStackType.FLUID_STACK_TYPE,fluids);fluids.monitor=fluidMonitor;
            fluidMonitor.injectItems(AEFluidStack.create(new FluidStack(FluidRegistry.WATER,1500)),Actionable.MODULATE,source);
            FluidKey water=FluidKey.of(new FluidStack(FluidRegistry.WATER,1));
            check(SupplyTracker.LEDGER.snapshot(water,10,0).incoming==1000,"native fluid-cell partial insert is measured in exact millibuckets");
            field(PublicMeNetwork.class,"running").setBoolean(null,false);
            monitor.extractItems(item(5),Actionable.MODULATE,source);
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==27,"unrelated/private network operations are not public flow");
        } finally { PublicMeNetwork.stop();SupplyTracker.stop();AeSupplyTracking.clear(); }
    }

    @SuppressWarnings("unchecked")
    private static void craftingIdentity() throws Exception {
        Player player=(Player)unsafe.allocateInstance(Player.class);player.id=UUID.randomUUID();
        Class<?> type=Class.forName("com.futa_gtnh.me.PublicMeCrafting$Session");Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        Object session=constructor.newInstance();AtomicInteger canceled=new AtomicInteger();
        ICraftingLink link=(ICraftingLink)Proxy.newProxyInstance(ICraftingLink.class.getClassLoader(),new Class<?>[]{ICraftingLink.class},(p,m,a)-> {
            if(m.getName().equals("cancel")){canceled.incrementAndGet();return null;}
            if(m.getName().equals("getCraftingID"))return "fixture-job";
            return defaultValue(p,m.getName(),m.getReturnType(),a);
        });
        field(type,"player").set(session,player);field(type,"output").set(session,item(64));field(type,"link").set(session,link);
        field(type,"state").setInt(session,4);field(type,"message").set(session,"running");
        Map<UUID,Object> sessions=(Map<UUID,Object>)field(PublicMeCrafting.class,"SESSIONS").get(null);sessions.put(player.id,session);
        String id=(String)field(type,"planId").get(session);
        check(PublicMeCrafting.snapshot(player).getString("planId").equals(id),"snapshot identifies the exact plan it describes");
        PublicMeCrafting.cancel(player,UUID.randomUUID().toString());
        check(canceled.get()==0 && field(type,"state").getInt(session)==4,"stale UI cancel cannot cancel a replacement job");
        PublicMeCrafting.cancel(player,id);check(canceled.get()==1,"matching plan cancellation reaches the native link");
        canceled.set(0);field(type,"state").setInt(session,4);PublicMeCrafting.clear();
        check(canceled.get()==0,"server shutdown does not cancel native CPU jobs");
        Object calculation=constructor.newInstance();CompletableFuture<Object> future=new CompletableFuture<>();field(type,"future").set(calculation,future);
        sessions.put(player.id,calculation);PublicMeCrafting.clear();check(future.isCancelled(),"shutdown cancels only unfinished plan calculations");
    }

    private static IAEItemStack item(long amount){IAEItemStack stack=AEItemStack.create(new ItemStack(Items.iron_ingot));stack.setStackSize(amount);return stack;}
    private static Object defaultValue(Object proxy,String name,Class<?> result,Object[] args){
        if(name.equals("equals"))return proxy==args[0];if(name.equals("hashCode"))return System.identityHashCode(proxy);
        if(result==boolean.class)return false;if(result==int.class)return 0;if(result==long.class)return 0L;return null;
    }
    private static final class TestMonitor<T extends IAEStack<T>> extends NetworkMonitor<T> {
        private final IMEInventoryHandler<T> inventory;
        TestMonitor(GridStorageCache cache,IAEStackType<T> type,IMEInventoryHandler<T> inventory){super(cache,type);this.inventory=inventory;}
        @Override public IMEInventoryHandler<T> getHandler(){return inventory;}
        @Override protected void postChange(boolean add,Iterable<IAEStack<?>> changes,BaseActionSource source){}
    }
    private static final class Inventory<T extends IAEStack<T>> implements IMEInventoryHandler<T> {
        final IAEStackType<T> type;long amount,capacity;boolean reenter;TestMonitor<T> monitor;
        Inventory(IAEStackType<T> type){this.type=type;}
        @Override public T injectItems(T input,Actionable action,BaseActionSource source){long accepted=Math.min(input.getStackSize(),capacity-amount);if(action==Actionable.MODULATE)amount+=accepted;return accepted==input.getStackSize()?null:input.copy().setStackSize(input.getStackSize()-accepted);}
        @Override public T extractItems(T request,Actionable action,BaseActionSource source){if(reenter){reenter=false;return monitor.extractItems(request,action,source);}long taken=Math.min(amount,request.getStackSize());if(action==Actionable.MODULATE)amount-=taken;return taken==0?null:request.copy().setStackSize(taken);}
        @Override public IItemList<T> getAvailableItems(IItemList<T> out,int iteration){return out;}
        @Override public IAEStackType<?> getStackType(){return type;}
        @Override public AccessRestriction getAccess(){return AccessRestriction.READ_WRITE;}
        @Override public boolean isPrioritized(T stack){return false;}
        @Override public boolean canAccept(T stack){return true;}
        @Override public int getPriority(){return 0;}
        @Override public int getSlot(){return 0;}
        @Override public boolean validForPass(int pass){return true;}
    }
    private static final class Machine extends TileEntity implements IActionHost {
        IGridNode node;
        @Override public IGridNode getActionableNode(){return node;}
        @Override public IGridNode getGridNode(ForgeDirection side){return node;}
        @Override public AECableType getCableConnectionType(ForgeDirection side){return AECableType.SMART;}
        @Override public void securityBreak(){}
    }
    private static final class Player extends EntityPlayerMP {
        UUID id;
        Player(){super(null,null,null,null);}
        @Override public UUID getUniqueID(){return id;}
    }
}
