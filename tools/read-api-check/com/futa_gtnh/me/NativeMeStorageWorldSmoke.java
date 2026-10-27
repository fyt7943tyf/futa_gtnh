package com.futa_gtnh.me;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageFile;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.shared.NativeMeStorageData;
import com.futa_gtnh.shared.NativeMeMigration;
import com.futa_gtnh.client.ProductionTerminalWorldSmoke;
import appeng.api.config.Actionable;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Real old-server file -> native AE cells -> normal world save -> restart, in the exact opt-in smoke save. */
public final class NativeMeStorageWorldSmoke {
    private static volatile boolean complete;
    private static volatile String failure;
    private static final String BASELINE="native-me-migration-smoke.dat";
    public static void register(){complete=false;failure=null;FMLCommonHandler.instance().bus().register(new NativeMeStorageWorldSmoke());}
    public static boolean isComplete(){return complete;}
    public static String failure(){return failure;}

    /** Runs after all real mod registries load but before launching the isolated development server. */
    public static void prepare(File world) throws Exception {
        require(Boolean.getBoolean("futa.uiSmoke") && world.getName().equals(PublicMeWorldSmoke.saveName()),"refuse non-smoke save");
        File baseline=new File(world,BASELINE);
        if(baseline.exists())return;
        // Existing development saves stay native-only; use a fresh run directory for the old-server fixture.
        if(NativeMeMigration.dataFile(world).exists() || SharedStorageFile.fileFor(world).exists())return;
        SharedStorage old=new SharedStorage();
        String identity=UUID.randomUUID().toString();
        ItemStack item=new ItemStack(Items.emerald);NBTTagCompound tag=new NBTTagCompound();tag.setString("nativeMigrationSmoke",identity);item.setTagCompound(tag);
        ItemKey key=ItemKey.of(item);FluidKey fluid=FluidKey.of(new FluidStack(FluidRegistry.WATER,1,(NBTTagCompound)tag.copy()));
        long itemCount=4294967311L,fluidCount=27000000013L;
        old.insertItemManualDirect(key,itemCount);old.insertFluidManualDirect(fluid,fluidCount);
        old.reserveItemForCrafting(key,3);old.reserveFluidForCrafting(fluid,5);old.setItemLimit(key,1);old.setFluidLimit(fluid,2);
        require(SharedStorageFile.save(world,old),"write actual legacy file before server startup");
        SharedStorageFile.loadForAe(world); // Simulates the previous released version's stable warehouse identity.
        NBTTagCompound state=new NBTTagCompound();state.setString("warehouse",SharedStorageFile.getAeStorageId(world));
        state.setString("sourceSha256",digest(SharedStorageFile.fileFor(world)));state.setTag("item",key.writeToNbt());state.setTag("fluid",fluid.writeToNbt());
        state.setLong("items",itemCount-3);state.setLong("fluids",fluidCount-5);
        write(baseline,state);
        System.out.println("NATIVE_ME_OLD_WORLD_FIXTURE_READY original legacy file, v1 UUID, tagged long quantities, limits and uncertain transfers");
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END || complete || failure!=null || !PublicMeSubnetFlowWorldSmoke.isComplete()
            || !ProductionTerminalWorldSmoke.isComplete() || !PublicMeWorldSmoke.isComplete())return;
        if(!PublicMeNetwork.isOnline())return;
        MinecraftServer server=MinecraftServer.getServer();if(server==null)return;
        WorldServer world=server.worldServerForDimension(0);if(world==null)return;
        try{
            File directory=world.getSaveHandler().getWorldDirectory();
            require(Boolean.getBoolean("futa.uiSmoke") && directory.getName().equals(PublicMeWorldSmoke.saveName()) && world.getWorldInfo().getWorldName().equals(PublicMeWorldSmoke.worldName()),"refuse non-smoke world");
            SharedStorage access=SharedStorageManager.getStorage();
            require(access.nativeCells()!=null,"live facade owns no independent shared quantity pool");
            require(world.loadItemData(NativeMeStorageData.class,NativeMeStorageData.NAME) instanceof NativeMeStorageData,"Minecraft owns actual native cell world data");
            File baseline=new File(directory,BASELINE);
            if(baseline.isFile()){
                NBTTagCompound state=read(baseline);ItemKey item=ItemKey.readFromNbt(state.getCompoundTag("item"));FluidKey fluid=FluidKey.readFromNbt(state.getCompoundTag("fluid"));
                require(state.getString("warehouse").equals(SharedStorageManager.storageId().toString()),"old server UUID is preserved by native migration");
                require(digest(SharedStorageFile.fileFor(directory)).equals(state.getString("sourceSha256")),"old shared file is byte-for-byte unchanged after all native IO");
                long items=state.getLong("items"),fluids=state.getLong("fluids");
                boolean restart=state.getBoolean("consumed");if(restart){items-=101;fluids-=203;}
                require(access.getItemAmountPool(item)==items && access.getFluidAmountPool(fluid)==fluids,"tagged legacy quantity is loaded exactly without double import");
                require(access.snapshotCraftingItems().stream().anyMatch(row->row.getKey().equals(item) && row.getValue()==3)
                    && access.snapshotCraftingFluids().stream().anyMatch(row->row.getKey().equals(fluid) && row.getValue()==5),"uncertain transfers remain unavailable across migration/restart");
                require(access.getItemLimit(item)==1 && access.getFluidLimit(fluid)==2,"old limits do not truncate native stock");
                if(!restart){
                    require(PublicMeNetwork.monitor(AEItemStackType.ITEM_STACK_TYPE).extractItems(AEItemStack.create(item.prototype()).setStackSize(101),Actionable.MODULATE,PublicMeNetwork.actionSource()).getStackSize()==101,"native grid actually consumes 101 migrated items");
                    require(PublicMeNetwork.monitor(AEFluidStackType.FLUID_STACK_TYPE).extractItems(AEFluidStack.create(fluid.prototype()).setStackSize(203),Actionable.MODULATE,PublicMeNetwork.actionSource()).getStackSize()==203,"native grid actually consumes 203 migrated mB");
                    items-=101;fluids-=203;
                }
                SharedStorageManager.saveNow();
                NativeMeStorageData mounted=NativeMeMigration.load(directory);
                require(mounted.access().getItemAmountPool(item)==items && mounted.access().getFluidAmountPool(fluid)==fluids,"normal Minecraft world-data save persists actual native cell changes");
                NBTTagCompound media=new NBTTagCompound();mounted.cells().writeToNBT(media);
                require(ItemStack.loadItemStackFromNBT(media.getTagList("itemCells",10).getCompoundTagAt(0)).getItem() instanceof appeng.items.storage.ItemBasicStorageCell,"item medium is registered native AE storage cell");
                require(ItemStack.loadItemStackFromNBT(media.getTagList("fluidCells",10).getCompoundTagAt(0)).getItem() instanceof com.glodblock.github.common.item.FCBaseItemCell,"fluid medium is registered native AE2FC storage cell");
                require(digest(SharedStorageFile.fileFor(directory)).equals(state.getString("sourceSha256")),"normal world saves never write old shared file");
                state.setBoolean("consumed",true);write(baseline,state);
                String generation=com.futa_gtnh.api.SharedStorageReadApi.getStatus().getGeneration();
                require(SharedStorageManager.reloadFromDisk()>=0,"native storage can reload through the real world owner");
                require(!generation.equals(com.futa_gtnh.api.SharedStorageReadApi.getStatus().getGeneration()),"native reload advances read-API generation");
                require(SharedStorageManager.getStorage().getItemAmountPool(item)==items && SharedStorageManager.getStorage().getFluidAmountPool(fluid)==fluids,"real reload preserves latest native quantities");
                SharedStorageManager.saveNow();byte[] valid=Files.readAllBytes(NativeMeMigration.dataFile(directory).toPath());
                SharedStorage before=SharedStorageManager.getStorage();
                try {
                    NBTTagCompound damaged=read(NativeMeMigration.dataFile(directory));damaged.getCompoundTag("data").setInteger("version",999);write(NativeMeMigration.dataFile(directory),damaged);
                    require(SharedStorageManager.reloadFromDisk()==-1 && SharedStorageManager.isSavingBlocked() && SharedStorageManager.getStorage()==before,"failed native reload retains exact live inventory and blocks world-data saves");
                    SharedStorageManager.saveNow();require(read(NativeMeMigration.dataFile(directory)).getCompoundTag("data").getInteger("version")==999,"failed reload cannot overwrite the unreadable source");
                } finally {Files.write(NativeMeMigration.dataFile(directory).toPath(),valid);}
                require(SharedStorageManager.reloadFromDisk()>=0 && !SharedStorageManager.isSavingBlocked(),"repair reload restores native world-data persistence");
                require(SharedStorageManager.getStorage().getItemAmountPool(item)==items && SharedStorageManager.getStorage().getFluidAmountPool(fluid)==fluids,"repair preserves exact consumed quantities");
                System.out.println(restart?"NATIVE_ME_RESTART_SMOKE_SUCCESS stable old UUID; consumed long tagged item/fluid quantities do not resurrect; native world-data save; original legacy file unchanged":"NATIVE_ME_MIGRATION_SMOKE_SUCCESS actual old-server data to original native AE/AE2FC media; long tagged items/fluids, limits and unresolved escrow; consume 101 items and 203 mB; world-data remount exact; old shared file unchanged");
            } else {
                require(!SharedStorageFile.fileFor(directory).exists(),"native-only new world has no legacy shared file");
                System.out.println("NATIVE_ME_STORAGE_SMOKE_SUCCESS native-only save has no legacy shared storage file");
            }
            complete=true;
        }catch(Throwable error){failure="NATIVE_ME_STORAGE_SMOKE_FAILED "+error;error.printStackTrace();System.err.println(failure);}
    }
    private static String digest(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(Files.readAllBytes(file.toPath()));StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format("%02x",b&255));return result.toString();}
    private static NBTTagCompound read(File file)throws Exception{try(FileInputStream in=new FileInputStream(file)){return CompressedStreamTools.readCompressed(in);}}
    private static void write(File file,NBTTagCompound tag)throws Exception{file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){CompressedStreamTools.writeCompressed(tag,out);}}
    private static void require(boolean okay,String text){if(!okay)throw new AssertionError(text);}
}
