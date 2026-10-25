package com.futa_gtnh.supply;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
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
import net.minecraftforge.fluids.*;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.storagebus.StorageBusRegistry;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

/** Exercise real IO extraction, destination acceptance/rollback and shared-bus internal-move exclusions. */
public final class SupplyIoRegression {
    private static int checks;
    private static Method push,pull,pushFluid;
    private static ItemKey iron;
    private static MemoryWorld world;
    public static void run() throws Exception {
        Class<?> engine=Class.forName("com.futa_gtnh.block.TerminalIoEngine");
        push=engine.getDeclaredMethod("pushItem",IInventory.class,ForgeDirection.class,SharedStorage.class,ItemKey.class,long.class,int.class);
        pull=engine.getDeclaredMethod("pullItems",IInventory.class,ForgeDirection.class,TerminalIoConfig.class);
        pushFluid=engine.getDeclaredMethod("pushFluid",IFluidHandler.class,ForgeDirection.class,SharedStorage.class,FluidKey.class,long.class,int.class);
        push.setAccessible(true); pull.setAccessible(true); pushFluid.setAccessible(true);
        iron=ItemKey.of(new ItemStack(Items.iron_ingot)); world=new MemoryWorld();
        Field active=SharedStorageManager.class.getDeclaredField("storage"); active.setAccessible(true); Object previous=active.get(null);
        try {
            SharedStorage store=new SharedStorage(); active.set(null,store); store.insertItemManual(iron,1000);
            SupplyTracker.start((File)null);
            Inventory target=inventory(1); target.writeLimit=3;
            int moved=(Integer)push.invoke(null,target,ForgeDirection.WEST,store,iron,1000L,16);
            SupplyLedger.Snapshot s=SupplyTracker.LEDGER.snapshot(iron,10,0);
            check(moved==3 && store.getItemAmount(iron)==997 && target.stack.stackSize==3,"partial destination acceptance preserves material");
            check(s.outgoing==3 && s.rows.get(0).source.kind==SupplySource.IO && s.rows.get(0).source.x==0
                && s.rows.get(0).source.side==ForgeDirection.EAST.ordinal(),"IO reports accepted count and source terminal face, excluding rollback");
            SupplyTracker.start((File)null); target=inventory(1); target.writeLimit=0;
            moved=(Integer)push.invoke(null,target,ForgeDirection.WEST,store,iron,997L,16);
            check(moved==0 && SupplyTracker.LEDGER.keyCount()==0 && store.getItemAmount(iron)==997,"rejected insertion leaves no telemetry or stock loss");
            target.writeLimit=64; target.stack=new ItemStack(Items.iron_ingot,7);
            pull.invoke(null,target,ForgeDirection.WEST,new TerminalIoConfig());
            check(SupplyTracker.LEDGER.snapshot(iron,10,0).incoming==7,"IO return counted only after source actually shrinks");
            SupplyTracker.start((File)null); target.stack=new ItemStack(Items.iron_ingot,7); target.reject=true;
            pull.invoke(null,target,ForgeDirection.WEST,new TerminalIoConfig());
            check(SupplyTracker.LEDGER.keyCount()==0,"failed source removal/provisional pool write is not an incoming transfer");

            SupplyTracker.start((File)null); target=inventory(1);
            TileEntityStorageBus bus=new TileEntityStorageBus(); bus.addFace(ForgeDirection.EAST); world.put(0,bus);
            StorageBusRegistry.register(bus.getLink(ForgeDirection.EAST));
            check(StorageBusRegistry.accountsFor(target,iron),"empty external container recognized as an accounted target");
            moved=(Integer)push.invoke(null,target,ForgeDirection.WEST,store,iron,store.getItemAmount(iron),16);
            check(moved==16 && SupplyTracker.LEDGER.keyCount()==0,"pool-to-shared-bus internal move is not issued material");
            bus.getConfig(ForgeDirection.EAST).setItemFilter(0,ItemKey.of(new ItemStack(Items.gold_ingot)));
            StorageBusRegistry.refresh(bus.getLink(ForgeDirection.EAST));
            target.stack=null;
            moved=(Integer)push.invoke(null,target,ForgeDirection.WEST,store,iron,store.getItemAmount(iron),16);
            check(moved==16 && SupplyTracker.LEDGER.snapshot(iron,10,0).outgoing==16,"filtered-out target stock is a genuine warehouse exit");
            StorageBusRegistry.clear();

            SupplyTracker.start((File)null);
            FluidKey water=FluidKey.of(new FluidStack(FluidRegistry.WATER,1)); store.insertFluidManual(water,1000);
            Tank tank=new Tank(); world.put(1,tank); tank.writeLimit=123;
            moved=(Integer)pushFluid.invoke(null,tank,ForgeDirection.WEST,store,water,1000L,500);
            check(moved==123 && store.getFluidAmount(water)==877 && SupplyTracker.LEDGER.snapshot(water,10,0).outgoing==123,
                "fluid accounting uses actual fill and excludes returned remainder");
            SupplyTracker.start((File)null); tank.writeLimit=0;
            moved=(Integer)pushFluid.invoke(null,tank,ForgeDirection.WEST,store,water,877L,500);
            check(moved==0 && SupplyTracker.LEDGER.keyCount()==0 && store.getFluidAmount(water)==877,"failed real fluid fill is not recorded");
        } finally { StorageBusRegistry.clear(); active.set(null,previous); SupplyTracker.stop(); }
        System.out.println("Supply IO integration: "+checks+" checks passed");
    }
    private static void check(boolean okay,String message) { if(!okay) throw new AssertionError(message); checks++; }
    private static Inventory inventory(int x) { Inventory result=new Inventory(); world.put(x,result); return result; }
    private static final class Inventory extends TileEntity implements IInventory {
        ItemStack stack; int writeLimit=64; boolean reject;
        @Override public int getSizeInventory(){return 1;}
        @Override public ItemStack getStackInSlot(int index){return stack;}
        @Override public void setInventorySlotContents(int index,ItemStack value){
            if(reject)return;
            if(value==null){stack=null;return;}
            if(stack==null){if(writeLimit<=0)return; stack=value.copy(); stack.stackSize=Math.min(value.stackSize,writeLimit);}
            else{int old=stack.stackSize; stack=value.copy(); stack.stackSize=Math.min(value.stackSize,old+writeLimit);}
        }
        @Override public ItemStack decrStackSize(int index,int count){if(stack==null)return null; ItemStack out=stack.splitStack(count);if(stack.stackSize<=0)stack=null;return out;}
        @Override public ItemStack getStackInSlotOnClosing(int index){return null;}
        @Override public String getInventoryName(){return "supply-check";}
        @Override public boolean hasCustomInventoryName(){return false;}
        @Override public int getInventoryStackLimit(){return 64;}
        @Override public boolean isUseableByPlayer(EntityPlayer player){return true;}
        @Override public void openInventory(){}
        @Override public void closeInventory(){}
        @Override public boolean isItemValidForSlot(int index,ItemStack item){return true;}
        @Override public void markDirty(){}
    }
    private static final class Tank extends TileEntity implements IFluidHandler {
        int amount,writeLimit=10000;
        @Override public int fill(ForgeDirection side,FluidStack input,boolean actual){int n=Math.min(input.amount,10000-amount);if(actual){n=Math.min(n,writeLimit);amount+=n;}return n;}
        @Override public FluidStack drain(ForgeDirection side,FluidStack request,boolean actual){return drain(side,request.amount,actual);}
        @Override public FluidStack drain(ForgeDirection side,int request,boolean actual){int n=Math.min(request,amount);if(actual)amount-=n;return n==0?null:new FluidStack(FluidRegistry.WATER,n);}
        @Override public boolean canFill(ForgeDirection side,Fluid fluid){return true;}
        @Override public boolean canDrain(ForgeDirection side,Fluid fluid){return true;}
        @Override public FluidTankInfo[] getTankInfo(ForgeDirection side){return new FluidTankInfo[]{new FluidTankInfo(new FluidStack(FluidRegistry.WATER,amount),10000)};}
    }
    private static final class MemoryWorld extends World {
        final Map<Integer,TileEntity> tiles=new HashMap<>();
        MemoryWorld(){super(new SaveHandlerMP(),"supply-io-check",new WorldSettings(0,WorldSettings.GameType.SURVIVAL,false,false,WorldType.DEFAULT),new WorldProviderSurface(),new Profiler());}
        void put(int x,TileEntity tile){tiles.put(x,tile);tile.xCoord=x;tile.yCoord=64;tile.setWorldObj(this);}
        @Override protected IChunkProvider createChunkProvider(){return null;}
        @Override protected int func_152379_p(){return 0;}
        @Override public Entity getEntityByID(int id){return null;}
        @Override public boolean blockExists(int x,int y,int z){return true;}
        @Override public TileEntity getTileEntity(int x,int y,int z){return y==64&&z==0?tiles.get(x):null;}
        @Override public Block getBlock(int x,int y,int z){return Blocks.air;}
        @Override public int getBlockMetadata(int x,int y,int z){return 0;}
        @Override public void markBlockForUpdate(int x,int y,int z){}
        @Override public void markTileEntityChunkModified(int x,int y,int z,TileEntity tile){}
        @Override public void func_147453_f(int x,int y,int z,Block block){}
    }
}
