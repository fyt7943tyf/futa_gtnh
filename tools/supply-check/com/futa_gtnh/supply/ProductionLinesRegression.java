package com.futa_gtnh.supply;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import com.futa_gtnh.Config;
import com.futa_gtnh.command.CommandProductionLines;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/** Metadata/flow tests never resolve a world, tile, AE grid or live inventory. */
public final class ProductionLinesRegression {
    private static int checks;
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
    private static SupplySource source(int x) { return new SupplySource(SupplySource.IO, -1, x, 64, 4, 5, ""); }
    private static NBTTagCompound action(String action, String line, SupplySource source, String name) {
        NBTTagCompound tag=new NBTTagCompound(); tag.setString("action",action); tag.setString("lineId",line);
        tag.setString("name",name); if(source!=null) tag.setTag("source",source.write()); return tag;
    }
    private static String create(ProductionLines registry,SupplyLedger ledger,String name) {
        NBTTagCompound response=registry.mutate(action("create","",null,name),ledger);
        check(response.getBoolean("ok"),"line created with UUID"); return response.getString("lineId");
    }
    public static int run() throws Exception {
        flows(); membership(); persistenceAndPermissions(); protocol();
        System.out.println("Production lines: "+checks+" checks passed"); return checks;
    }
    private static void flows() {
        SupplyLedger ledger=new SupplyLedger(); ProductionLines registry=new ProductionLines();
        ItemKey iron=ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water=FluidKey.of(new FluidStack(FluidRegistry.WATER,1));
        String line=create(registry,ledger,"湿法线");
        registry.mutate(action("bind",line,source(1),""),ledger);
        registry.mutate(action("bind",line,source(2),""),ledger);
        ledger.record(iron,source(1),60,false,0); ledger.record(iron,source(1),10,true,0);
        ledger.record(iron,source(2),20,false,0); ledger.record(water,source(2),12000,true,0);
        ledger.record(water,source(2),3000,false,0);
        NBTTagCompound snapshot=registry.snapshot(ledger,line,"",10,0,0,0,30000);
        NBTTagCompound totals=snapshot.getCompoundTag("totals");
        check(totals.getLong("itemOut")==80 && totals.getLong("itemIn")==10,"line totals sum distinct devices and retain both directions");
        check(totals.getLong("fluidOut")==3000 && totals.getLong("fluidIn")==12000,"fluids are counted separately in mB");
        check(snapshot.getLong("observedMillis")==30000 && totals.getDouble("itemOutPerMinute")==160d,
            "warmup rate uses thirty observed seconds rather than ten unobserved minutes");
        check(totals.getDouble("fluidInPerMinute")==24000d,"fluid rate is mB per minute");
        check(snapshot.getTagList("resources",10).tagCount()==2,"item/fluid resource identities remain separate");
        snapshot=registry.snapshot(ledger,line,source(1).id(),10,0,0,0,30000);
        check(snapshot.getCompoundTag("totals").getLong("itemOut")==60 && snapshot.getTagList("resources",10).tagCount()==1,
            "device selection filters detail and totals without changing line membership");
        ledger.record(iron,source(1),6,false,1);
        snapshot=registry.snapshot(ledger,line,source(1).id(),1,0,0,0,90000);
        check(snapshot.getCompoundTag("totals").getLong("itemOut")==6
            && snapshot.getCompoundTag("totals").getDouble("itemOutPerMinute")==12d
            && snapshot.getLong("observedMillis")==30000,"one-minute bucket excludes previous minute and reports partial bucket duration");
        check(ledger.snapshot(iron,1,1).outgoing==6,"legacy resource snapshot now supports the one-minute window");
        snapshot=registry.snapshot(ledger,line,"",1,0,0,0,60000);
        check(!snapshot.getBoolean("hasRate") && snapshot.getCompoundTag("totals").getDouble("itemOutPerMinute")==0,
            "no division by zero or invented rate at a fresh bucket boundary");
        ledger.expire(61);
        snapshot=registry.snapshot(ledger,line,"",60,0,0,0,3660000);
        check(snapshot.getInteger("sourceCount")==2 && snapshot.getTagList("sources",10).tagCount()==2
            && !snapshot.getTagList("sources",10).getCompoundTagAt(0).getBoolean("active"),
            "bound devices remain visible without recent transfers and are not called online");
    }
    private static void membership() {
        SupplyLedger ledger=new SupplyLedger(); ProductionLines registry=new ProductionLines();
        String first=create(registry,ledger,"一线"),second=create(registry,ledger,"二线");
        check(registry.mutate(action("bind",first,source(1),""),ledger).getBoolean("ok"),"inactive device can be bound by coordinates without loading a world");
        registry.mutate(action("bind",second,source(1),""),ledger);
        check(registry.snapshot(ledger,first,"",10,0,0,0,30000).getInteger("sourceCount")==0
            && registry.snapshot(ledger,second,"",10,0,0,0,30000).getInteger("sourceCount")==1,"rebinding transfers membership instead of double counting");
        check(!registry.mutate(action("bind",first,SupplySource.UNIDENTIFIED,""),ledger).getBoolean("ok"),"unknown sources cannot become phantom positioned devices");
        NBTTagCompound saved=registry.write();
        ProductionLines restored=new ProductionLines(); restored.read(saved);
        check(restored.snapshot(ledger,second,"",10,0,0,0,0).getInteger("sourceCount")==1,"metadata round trip preserves inactive binding and UUID");
        NBTTagCompound broken=(NBTTagCompound)saved.copy();
        broken.getTagList("bindings",10).getCompoundTagAt(0).setString("lineId","missing");
        boolean failed=false;try{restored.read(broken);}catch(IllegalArgumentException expected){failed=true;}
        check(failed && restored.snapshot(ledger,second,"",10,0,0,0,0).getInteger("sourceCount")==1,"invalid metadata is rejected before replacing live registry");
        restored.mutate(action("rename",second,null,"二线升级"),ledger);
        check(restored.snapshot(ledger,second,"",10,0,0,0,0).getString("lineName").equals("二线升级"),"renaming preserves UUID and membership");
        restored.mutate(action("unbind","",source(1),""),ledger);
        check(restored.snapshot(ledger,second,"",10,0,0,0,0).getInteger("sourceCount")==0,"unbind only removes membership");
        for(int i=0;i<10;i++)restored.mutate(action("bind",second,source(i),""),ledger);
        NBTTagCompound page=restored.snapshot(ledger,second,"",10,0,999,0,0);
        check(page.getInteger("sourcePage")==1 && page.getInteger("sourcePages")==2 && page.getTagList("sources",10).tagCount()==2,
            "device pages are bounded and stale page requests clamp safely");
        check(!restored.snapshot(ledger,"missing","",10,0,0,0,0).getBoolean("lineFound"),"unknown line never falls back to all devices");
        restored.mutate(action("delete",second,null,""),ledger);
        check(!restored.snapshot(ledger,second,"",10,0,0,0,0).getBoolean("lineFound"),"deleting grouping clears its membership only");
    }
    private static ICommandSender sender(boolean admin,List<String> messages) {
        return (ICommandSender)Proxy.newProxyInstance(ProductionLinesRegression.class.getClassLoader(),new Class<?>[]{ICommandSender.class},(proxy,method,args)->{
            if(method.getName().equals("canCommandSenderUseCommand"))return admin;
            if(method.getName().equals("addChatMessage")){messages.add(((IChatComponent)args[0]).getUnformattedText());return null;}
            if(method.getName().equals("getCommandSenderName"))return "test";
            if(method.getName().equals("getEntityWorld"))throw new AssertionError("flow queries must not load worlds");
            if(method.getReturnType()==boolean.class)return false;
            if(method.getReturnType()==int.class)return 0;
            return null;
        });
    }
    private static void persistenceAndPermissions() throws Exception {
        Config.enableSupplyTracking=true;
        File dir=Files.createTempDirectory(new File("build").toPath(),"production-lines-").toFile();
        SupplyTracker.start(dir);
        List<String> messages=new ArrayList<>(); ICommandSender admin=sender(true,messages),guest=sender(false,messages);
        check(!SupplyTracker.productionMutation(guest,action("create","",null,"禁止")).getBoolean("ok"),"mutation API enforces admin even when caller does not");
        NBTTagCompound result=SupplyTracker.productionMutation(admin,action("create","",null,"持久线"));
        String line=result.getString("lineId");
        check(result.getBoolean("ok") && result.getBoolean("saved"),"admin creation is saved immediately");
        new CommandProductionLines().processCommand(admin,new String[]{"bindpos",line,"io","-1","20","64","4","5"});
        check(SupplyTracker.productionSnapshot(line,"",10,0,0,0).getInteger("sourceCount")==1,"coordinate command accepts its documented eight tokens");
        SupplyTracker.stop();SupplyTracker.start(dir);
        check(SupplyTracker.productionSnapshot(line,"",10,0,0,0).getInteger("sourceCount")==1,"restart retains line UUID and inactive device membership");
        check(SupplyTracker.productionSnapshot(line,"",10,0,0,0).getCompoundTag("totals").getLong("itemOut")==0,"history is not confused with persisted membership");
        new CommandProductionLines().processCommand(guest,new String[]{"list"});
        check(messages.stream().anyMatch(text->text.contains("持久线")),"ordinary players can list production groups");
        new CommandProductionLines().processCommand(guest,new String[]{"rename",line,"禁止"});
        check(SupplyTracker.productionSnapshot(line,"",10,0,0,0).getString("lineName").equals("持久线"),"command cannot bypass API admin check");
        NBTTagCompound legacy=new NBTTagCompound();legacy.setInteger("version",1);legacy.setTag("names",new NBTTagList());legacy.setTag("watches",new NBTTagList());
        SupplyTracker.readSettings(legacy);
        check(SupplyTracker.productionSnapshot("","",10,0,0,0).getInteger("lineCount")==0,"legacy version-one settings load with empty production metadata");
        SupplyTracker.stop();
    }
    private static void protocol() throws Exception {
        com.futa_gtnh.network.PacketProductionRequest request=new com.futa_gtnh.network.PacketProductionRequest();
        request.window=31;request.session=123456789L;request.sequence=7;request.minutes=1;
        request.lineId="line";request.sourceId=source(1).id();request.linePage=2;request.sourcePage=3;request.resourcePage=4;
        request.mutation=action("rename","line",null,"新名称");
        io.netty.buffer.ByteBuf buffer=io.netty.buffer.Unpooled.buffer();request.toBytes(buffer);
        com.futa_gtnh.network.PacketProductionRequest decoded=new com.futa_gtnh.network.PacketProductionRequest();decoded.fromBytes(buffer);buffer.release();
        java.lang.reflect.Field valid=com.futa_gtnh.network.PacketProductionRequest.class.getDeclaredField("valid");valid.setAccessible(true);
        check(valid.getBoolean(decoded) && decoded.window==31 && decoded.session==123456789L && decoded.sequence==7
            && decoded.minutes==1 && decoded.resourcePage==4 && decoded.mutation.getString("name").equals("新名称"),
            "production request retains window, screen session, sequence, one-minute window and mutation");
        request.minutes=2;buffer=io.netty.buffer.Unpooled.buffer();request.toBytes(buffer);decoded=new com.futa_gtnh.network.PacketProductionRequest();decoded.fromBytes(buffer);buffer.release();
        check(!valid.getBoolean(decoded),"invalid production rate window rejected before scheduling");
        buffer=io.netty.buffer.Unpooled.buffer().writeZero(8193);decoded=new com.futa_gtnh.network.PacketProductionRequest();decoded.fromBytes(buffer);buffer.release();
        check(!valid.getBoolean(decoded),"oversized production request rejected before NBT parsing");
        buffer=io.netty.buffer.Unpooled.buffer().writeZero(29);decoded=new com.futa_gtnh.network.PacketProductionRequest();decoded.fromBytes(buffer);buffer.release();
        check(!valid.getBoolean(decoded),"truncated production payload is rejected without leaking an underflow");
        NBTTagCompound data=new NBTTagCompound();data.setLong("itemOut",Long.MAX_VALUE);data.setLong("observedMillis",30000);
        com.futa_gtnh.network.PacketProductionSnapshot response=new com.futa_gtnh.network.PacketProductionSnapshot(31,123456789L,7,data);
        buffer=io.netty.buffer.Unpooled.buffer();response.toBytes(buffer);com.futa_gtnh.network.PacketProductionSnapshot copy=new com.futa_gtnh.network.PacketProductionSnapshot();copy.fromBytes(buffer);buffer.release();
        check(copy.session==123456789L && copy.sequence==7 && copy.data.getLong("itemOut")==Long.MAX_VALUE,"response preserves exact long totals and session identity");
    }
}
