package com.futa_gtnh.shared;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.futa_gtnh.me.NativeMeCellArray;
import appeng.items.storage.ItemExtremeStorageCell;
import com.glodblock.github.common.item.ItemFluidExtremeStorageCell;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventoryHandler;
import appeng.util.item.AEItemStackType;

/** Real AE/AE2FC media, CellInventory and migration files; no substitute quantity engine. */
public final class NativeMeMigrationRegression {
    private static int checks;
    private static Item itemCell,fluidCell;
    private static Supplier<NativeMeCellArray> factory;
    private static final File root=new File("build/native-me-migration-check");
    public static void run() throws Exception {
        itemCell=new ItemExtremeStorageCell("Universe",576460752303423487L,63,16384,600000);
        fluidCell=new ItemFluidExtremeStorageCell("fluid_storage.Universe",4503599627370495L,16384,63,600000);
        Item.itemRegistry.addObject(31000,"futa_native_check:item_cell",itemCell);
        Item.itemRegistry.addObject(31001,"futa_native_check:fluid_cell",fluidCell);
        factory=()->new NativeMeCellArray(fluid->new ItemStack(fluid?fluidCell:itemCell));
        root.mkdirs();
        File world=Files.createTempDirectory(root.toPath(),"exact-").toFile();
        ItemKey iron=ItemKey.of(new ItemStack(Items.iron_ingot));
        NBTTagCompound fluidTag=new NBTTagCompound();fluidTag.setString("batch","preserve-native-tag");
        FluidKey water=FluidKey.of(new FluidStack(FluidRegistry.WATER,1,fluidTag));
        SharedStorage legacy=new SharedStorage();
        legacy.insertItemManualDirect(iron,Long.MAX_VALUE-4);legacy.insertFluidManualDirect(water,Long.MAX_VALUE-7);
        legacy.reserveItemForCrafting(iron,3);legacy.reserveFluidForCrafting(water,5);
        legacy.setItemLimit(iron,1);legacy.setFluidLimit(water,2);
        NBTTagCompound source=legacy.writeToNbt();
        NBTTagCompound unknown=new NBTTagCompound();unknown.setString("fluid","native_check_missing");unknown.setLong("amount",99);
        source.getTagList("fluids",10).appendTag(unknown);source.setString("extra-field","immutable original bytes");
        File old=SharedStorageFile.fileFor(world);write(old,source);byte[] original=Files.readAllBytes(old.toPath());
        NativeMeStorageData data=NativeMeMigration.load(world,factory);
        check(data.access().getItemAmountPool(iron)==Long.MAX_VALUE-7,"native items preserve near-long-max quantity");
        check(data.access().getFluidAmountPool(water)==Long.MAX_VALUE-12,"native fluids preserve near-long-max quantity and NBT");
        check(data.access().getItemLimit(iron)==1 && data.access().getFluidLimit(water)==2,"migration never truncates over-limit stock");
        check(data.access().snapshotCraftingItems().get(0).getValue()==3 && data.access().snapshotCraftingFluids().get(0).getValue()==5,"uncertain transfers remain unavailable");
        check(data.access().getUnreadableFluidCount()==1,"unknown legacy fluid remains quarantined");
        check(data.cells().cellCount()>=5 && data.cells().cellCount()<20,"huge quantities use a small number of actual native cells");
        check(Arrays.equals(original,Files.readAllBytes(old.toPath())) && Arrays.equals(original,Files.readAllBytes(NativeMeMigration.backupFile(world).toPath())),"primary and dedicated backup preserve original bytes");
        check(!SharedStorageFile.save(world,data.access()),"old writer refuses native ownership");
        check(NativeMeMigration.load(world,factory).access().getItemAmountPool(iron)==Long.MAX_VALUE-7,"repeated load does not import twice");
        NBTTagCompound saved=read(NativeMeMigration.dataFile(world)).getCompoundTag("data");
        check(saved.getCompoundTag("metadata").getTagList("items",10).tagCount()==0 && saved.getCompoundTag("metadata").getTagList("fluids",10).tagCount()==1,"metadata has no spendable quantity table");
        check(saved.getTagList("itemCells",10).tagCount()>0 && saved.getTagList("fluidCells",10).tagCount()>0,"native ItemStack NBT is the actual persisted inventory");
        try{data.access().writeToNbt();throw new AssertionError("legacy serializer must reject live cells");}catch(IllegalStateException expected){checks++;}
        data.access().poolExtractItem(iron,21);data.access().poolExtractFluid(water,34);
        data.access().clearItemLimit(iron);data.access().clearFluidLimit(water);
        NBTTagCompound changed=new NBTTagCompound();data.writeToNBT(changed);NBTTagCompound outer=new NBTTagCompound();outer.setTag("data",changed);write(NativeMeMigration.dataFile(world),outer);
        NativeMeStorageData restart=NativeMeMigration.load(world,factory);
        check(restart.storageId().equals(data.storageId()) && restart.access().getItemAmountPool(iron)==Long.MAX_VALUE-28 && restart.access().getFluidAmountPool(water)==Long.MAX_VALUE-46,"restart loads current cell contents instead of archived stock");
        int cells=restart.cells().cellCount();
        check(restart.cells().cellCount()==cells,"queries do not expand storage");
        ItemStack media=ItemStack.loadItemStackFromNBT(changed.getTagList("itemCells",10).getCompoundTagAt(0));
        IMEInventoryHandler<?> nativeHandler=appeng.me.storage.CellInventory.getCell(media,null,AEItemStackType.ITEM_STACK_TYPE);
        check(nativeHandler instanceof ICellInventoryHandler,"media opens directly in native AE engine");
        check(((ICellInventoryHandler<?>)nativeHandler).getCellInv().getItemStack().getItem()==itemCell,"media item is an actual native AE item");
        Files.delete(NativeMeMigration.dataFile(world).toPath());
        expectFailure(()->NativeMeMigration.load(world,factory),"missing native data must not resurrect old pool");
        check(Arrays.equals(original,Files.readAllBytes(old.toPath())),"failed restart leaves old source unchanged");
        interrupted();newWorld();nativeSemantics();corruption();stableLegacyIdentity();
        NativeMeIndexRegression.run(itemCell,fluidCell);
        NativeMePriorityRegression.run(itemCell,fluidCell);
        System.out.println("Native ME migration: "+checks+" checks passed");
    }
    private static void interrupted() throws Exception {
        File world=Files.createTempDirectory(root.toPath(),"resume-").toFile();SharedStorage old=new SharedStorage();old.insertItemManualDirect(ItemKey.of(new ItemStack(Items.gold_ingot)),42);write(SharedStorageFile.fileFor(world),old.writeToNbt());
        File obstruction=new File(NativeMeMigration.markerFile(world).getPath()+".tmp");obstruction.mkdir();
        expectFailure(()->NativeMeMigration.load(world,factory),"ownership failure leaves exact resumable native snapshot");
        check(NativeMeMigration.dataFile(world).isFile() && !NativeMeMigration.markerFile(world).exists(),"partial migration has snapshot but no ownership");
        obstruction.delete();NativeMeStorageData resumed=NativeMeMigration.load(world,factory);
        check(resumed.access().getItemAmountPool(ItemKey.of(new ItemStack(Items.gold_ingot)))==42,"resume does not duplicate stock");
    }
    private static void newWorld() throws Exception {
        File world=Files.createTempDirectory(root.toPath(),"new-").toFile();NativeMeStorageData data=NativeMeMigration.load(world,factory);
        check(!SharedStorageFile.fileFor(world).exists() && !NativeMeMigration.backupFile(world).exists(),"new world never creates a legacy file");
        check(data.cells().cellCount()==0 && NativeMeMigration.isCommitted(world),"empty world establishes native ownership without preallocation");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static void nativeSemantics() throws Exception {
        NativeMeCellArray array=factory.get();SharedStorage access=new SharedStorage();int[] dirty={0};access.attachNativeCells(array,()->dirty[0]++);array.setPublished(true);
        ItemKey iron=ItemKey.of(new ItemStack(Items.iron_ingot));
        appeng.api.storage.IMEInventoryHandler allocator=array.getCellArray(AEItemStackType.ITEM_STACK_TYPE).get(0);
        appeng.api.storage.data.IAEItemStack request=appeng.util.item.AEItemStack.create(iron.prototype()).setStackSize(10);
        check(allocator.injectItems(request,appeng.api.config.Actionable.SIMULATE,null)==null && array.cellCount()==0 && dirty[0]==0,"simulated expansion changes no inventory, cells or persistence state");
        check(allocator.injectItems(request,appeng.api.config.Actionable.MODULATE,null)==null && array.itemView().get(iron)==10 && array.cellCount()==1,"allocator commits to an actual native cell");
        appeng.api.storage.IMEInventoryHandler stale=array.getCellArray(AEItemStackType.ITEM_STACK_TYPE).get(0);
        for(int i=0;i<70;i++){ItemStack paper=new ItemStack(Items.paper);NBTTagCompound n=new NBTTagCompound();n.setInteger("type",i);paper.setTagCompound(n);array.itemView().put(ItemKey.of(paper),1L);}
        check(array.cellCount()==2 && array.itemView().size()==71,"native type limit triggers automatic expansion beyond sixty-three types");
        check(stale.extractItems(request,appeng.api.config.Actionable.SIMULATE,null)!=null && array.itemView().get(iron)==10,"simulation does not consume native stock");
        access.setItemLimit(iron,11);
        appeng.api.storage.data.IAEItemStack rest=(appeng.api.storage.data.IAEItemStack)stale.injectItems(request,appeng.api.config.Actionable.MODULATE,null);
        check(rest.getStackSize()==9 && array.itemView().get(iron)==11,"native cell wrapper retains per-resource limit and exact partial acceptance");
        array.setPublished(false);
        check(stale.extractItems(request,appeng.api.config.Actionable.MODULATE,null)==null && allocator.injectItems(request,appeng.api.config.Actionable.MODULATE,null)==request && array.itemView().get(iron)==11,"detached old grid handles cannot withdraw or allocate against replaced inventory");
        array.setPublished(true);array.itemView().clear();
        check(stale.extractItems(request,appeng.api.config.Actionable.MODULATE,null)==null && array.cellCount()==0 && array.itemView().isEmpty(),"clear invalidates old native handles immediately");
        try{access.readFromNbt(new NBTTagCompound());throw new AssertionError("legacy reader must not overwrite active native storage");}catch(IllegalStateException expected){checks++;}
        array.itemView().put(iron,1L);NBTTagCompound raw=new NBTTagCompound();array.writeToNBT(raw);
        NBTTagCompound outer=raw.getTagList("itemCells",10).getCompoundTagAt(0);NBTTagCompound item=outer.getCompoundTag("tag").getCompoundTag("#0");
        NBTTagCompound original=(NBTTagCompound)item.copy();NBTTagCompound missing=new NBTTagCompound();missing.setShort("id",(short)32767);missing.setByte("Count",(byte)1);outer.getCompoundTag("tag").setTag("#0",missing);
        NativeMeCellArray frozen=factory.get();frozen.readFromNBT(raw);
        check(frozen.frozenCellCount()==1 && frozen.itemView().isEmpty(),"missing mod contents freeze the original native medium instead of native load discarding unknown rows");
        NBTTagCompound frozenSaved=new NBTTagCompound();frozen.writeToNBT(frozenSaved);
        check(frozenSaved.getTagList("itemCells",10).equals(raw.getTagList("itemCells",10)),"quarantined native cell retains every original NBT field");
        outer.getCompoundTag("tag").setTag("#0",original);NativeMeCellArray restored=factory.get();restored.readFromNBT(raw);
        check(restored.frozenCellCount()==0 && restored.itemView().get(iron)==1,"returning mod contents remount the original native media");
        outer.getCompoundTag("tag").setLong("ic",999);
        expectFailure(()->factory.get().readFromNBT(raw),"mismatched native counters refuse silent loss");
    }
    private static void corruption() throws Exception {
        File world=Files.createTempDirectory(root.toPath(),"corrupt-").toFile();NativeMeMigration.load(world,factory);
        NBTTagCompound broken=read(NativeMeMigration.dataFile(world));broken.getCompoundTag("data").setInteger("version",999);write(NativeMeMigration.dataFile(world),broken);
        expectFailure(()->NativeMeMigration.load(world,factory),"future native data format is rejected");
        check(read(NativeMeMigration.dataFile(world)).getCompoundTag("data").getInteger("version")==999,"future data is never overwritten");
        write(NativeMeMigration.markerFile(world),new NBTTagCompound());expectFailure(()->NativeMeMigration.load(world,factory),"corrupt ownership marker cannot start an empty warehouse");
    }
    private static void stableLegacyIdentity() throws Exception {
        File world=Files.createTempDirectory(root.toPath(),"old-public-").toFile();SharedStorage old=new SharedStorage();ItemKey key=ItemKey.of(new ItemStack(Items.diamond));old.insertItemManualDirect(key,9007199254740993L);
        write(SharedStorageFile.fileFor(world),old.writeToNbt());SharedStorageFile.loadForAe(world);String id=SharedStorageFile.getAeStorageId(world);
        NativeMeStorageData data=NativeMeMigration.load(world,factory);
        check(data.storageId().toString().equals(id) && data.access().getItemAmountPool(key)==9007199254740993L,"previous public AE warehouse UUID survives exact native conversion");
        expectFailure(()->SharedStorageFile.loadForAe(world),"archived legacy loader cannot reopen a second spendable pool");
        expectFailure(()->SharedStorageFile.load(world),"generic legacy loader also rejects archived stock");
        expectFailure(()->SharedMigrationState.checkReady(world,true),"previous-release ownership decoder refuses archived shared pool after native migration");
        check(new File(SharedMigrationState.markerFor(world).getPath()+".pre-native-v1").isFile(),"old ownership marker is archived before upgrading its format");
        byte[] live=Files.readAllBytes(NativeMeMigration.dataFile(world).toPath());
        write(NativeMeMigration.backupFile(world),new NBTTagCompound());
        expectFailure(()->NativeMeMigration.load(world,factory),"corrupt immutable source backup prevents unsafe restart");
        check(Arrays.equals(live,Files.readAllBytes(NativeMeMigration.dataFile(world).toPath())),"failed backup verification never overwrites live native inventory");
        expectFailure(()->new NativeMeCellArray(fluid->null).validateMedia(),"disabled media are detected before publishing the warehouse");
    }
    private static NBTTagCompound read(File file)throws Exception{try(FileInputStream in=new FileInputStream(file)){return CompressedStreamTools.readCompressed(in);}}
    private static void write(File file,NBTTagCompound tag)throws Exception{file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){CompressedStreamTools.writeCompressed(tag,out);}}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
    private static void expectFailure(Runnable action,String text){try{action.run();throw new AssertionError(text);}catch(IllegalStateException expected){checks++;}}
}
