package com.futa_gtnh.supply;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import com.futa_gtnh.Config;
import com.futa_gtnh.network.PacketSupplyRequest;
import com.futa_gtnh.network.PacketSupplySnapshot;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class SupplyLedgerRegression {
    private static int checks;
    public static int run() throws Exception {
        rings(); bounds(); settings(); protocol(); trendAndWarnings(); watchBudget();
        SupplyTracker.stop();
        System.out.println("Supply ledger/settings/protocol: " + checks + " checks passed");
        return checks;
    }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); checks++; }
    private static SupplySource source(int x) { return new SupplySource(SupplySource.AE_BUS, 0, x, 64, 0, 5, ""); }
    private static void rings() {
        SupplyLedger ledger = new SupplyLedger();
        Object key = new Object();
        ledger.record(key, source(1), 100, false, 0);
        ledger.record(key, source(1), 30, true, 0);
        ledger.record(key, source(2), 80, false, 1);
        SupplyLedger.Snapshot s = ledger.snapshot(key, 10, 1);
        check(s.outgoing == 180 && s.incoming == 30 && s.rows.get(0).source.x == 2, "net-issued sorting deducts returned material");
        check(ledger.snapshot(key,10,10).outgoing==80,"ten-minute bucket boundary excludes expired minute");
        check(ledger.snapshot(key,60,59).outgoing==180,"one-hour window retains minute zero until rollover");
        check(ledger.snapshot(key,60,60).outgoing==80,"one-hour window rolls over without stale slot reuse");
        ledger.record(key,source(1),7,false,65);
        check(ledger.snapshot(key,60,65).outgoing==7,"idle rings do not resurrect old counts");
        ledger.expire(125);
        check(ledger.keyCount()==0 && ledger.pairCount()==0 && ledger.source(source(1).id())==null,"inactive keys/pairs/source identities expire");
        ledger.record(key,source(1),Long.MAX_VALUE,false,125);
        ledger.record(key,source(1),99,false,125);
        check(ledger.snapshot(key,60,125).outgoing==Long.MAX_VALUE,"long counts saturate safely");
        ledger.record(key,source(1),7,false,124);
        check(ledger.snapshot(key,60,125).outgoing==Long.MAX_VALUE,"clock regression cannot corrupt a ring");
        SupplySource restored=SupplySource.read(source(99).write());
        check(restored.equals(source(99)) && restored.hashCode()==source(99).hashCode(),"source position/face identity round trip");
        check(!source(1).equals(new SupplySource(SupplySource.AE_BUS,0,1,64,0,4,"")),"two faces on one cable host remain distinct");
    }
    private static void bounds() {
        SupplyLedger ledger=new SupplyLedger();
        for (int i=0;i<SupplyLedger.MAX_KEYS+20;i++) ledger.record(Integer.valueOf(i),source(0),1,false,0);
        check(ledger.keyCount()==SupplyLedger.MAX_KEYS,"resource count bounded");
        check(ledger.snapshot(Integer.valueOf(0),10,0).dropped==20,"capacity omissions reported");
        ledger.clear();
        for (int i=0;i<SupplyLedger.MAX_SOURCES+5;i++) ledger.record("key",source(i),1,false,0);
        check(ledger.pairCount()==SupplyLedger.MAX_SOURCES,"source identities bounded");
        ledger.clear();
        for (int i=0;i<SupplyLedger.MAX_PAIRS+10;i++) ledger.record(Integer.valueOf(i/8),source(i%8),1,false,0);
        check(ledger.pairCount()==SupplyLedger.MAX_PAIRS,"resource/source pair count bounded");
        ledger.clear();
        Object hot=new Object(); SupplySource inlet=source(1);
        long start=System.nanoTime();
        for(int i=0;i<500_000;i++) ledger.record(hot,inlet,1,false,0);
        double elapsed=(System.nanoTime()-start)/1_000_000d;
        check(ledger.keyCount()==1 && ledger.pairCount()==1 && ledger.snapshot(hot,10,0).outgoing==500_000,"steady-state records reuse one pair/ring");
        System.out.printf(java.util.Locale.ROOT,"Supply synthetic hot path: 500000 updates %.2f ms; one key, one pair (not a TPS measurement)%n",elapsed);
    }
    private static void settings() throws Exception {
        File dir=Files.createTempDirectory(new File("build").toPath(),"supply-settings-").toFile();
        Config.enableSupplyTracking=true;
        SupplyTracker.start(dir);
        ItemKey iron=ItemKey.of(new ItemStack(Items.iron_ingot));
        SupplyTracker.record(iron,source(1),16,false);
        NBTTagCompound catalog=SupplyTracker.snapshot(null,10,0,0,2);
        check(catalog.getTagList("watches",10).tagCount()==1
            && SupplyTracker.readKey(catalog.getTagList("watches",10).getCompoundTagAt(0)).equals(iron),
            "history catalog retains an issued resource even when its current stock is zero");
        check(SupplyTracker.rename(source(1).id(),"铜箔产线"),"observed inlet can be named");
        check(!SupplyTracker.rename("invented", "wrong"),"unobserved source identity rejected");
        check(SupplyTracker.watch(iron,4000),"low-stock watch added");
        NBTTagCompound saved=SupplyTracker.settings();
        SupplyTracker.stop(); SupplyTracker.start(dir);
        check(SupplyTracker.snapshot(iron,10,0,0).getLong("threshold")==4000,"watch persists across restart");
        check(SupplyTracker.name(source(1)).equals("铜箔产线"),"inlet name persists across restart");
        check(SupplyTracker.snapshot(iron,10,0,0).getLong("outgoing")==0,"rolling history intentionally resets at restart");
        check(SupplyTracker.watch(iron,-1),"watch removed independently of stock");
        SupplyTracker.readSettings(saved);
        check(SupplyTracker.snapshot(iron,60,0,0).getLong("threshold")==4000,"metadata round trip preserves configuration");
        ItemStack oversized=new ItemStack(Items.iron_ingot);
        oversized.setTagCompound(new NBTTagCompound()); oversized.getTagCompound().setString("huge",String.join("",java.util.Collections.nCopies(5000,"x")));
        check(!SupplyTracker.watch(ItemKey.of(oversized),1),"oversized watched key cannot inflate snapshots or disk metadata");
        SupplyTracker.stop();
        File file=new File(dir,"data/futa_gtnh_supply_watch.dat");
        byte[] bad={1,2,3,4}; Files.write(file.toPath(),bad);
        SupplyTracker.start(dir);
        check(!SupplyTracker.watch(iron,1),"failed metadata load blocks overwriting original file");
        SupplyTracker.stop();
        check(Arrays.equals(Files.readAllBytes(file.toPath()),bad),"unreadable settings file preserved byte-for-byte");
    }
    private static void protocol() throws Exception {
        java.util.UUID player=java.util.UUID.randomUUID();
        net.minecraft.network.INetHandler oldConnection=(net.minecraft.network.INetHandler)java.lang.reflect.Proxy.newProxyInstance(
            SupplyLedgerRegression.class.getClassLoader(),new Class<?>[]{net.minecraft.network.INetHandler.class},(proxy,method,args)->null);
        net.minecraft.network.INetHandler newConnection=(net.minecraft.network.INetHandler)java.lang.reflect.Proxy.newProxyInstance(
            SupplyLedgerRegression.class.getClassLoader(),new Class<?>[]{net.minecraft.network.INetHandler.class},(proxy,method,args)->null);
        com.futa_gtnh.network.SupplyClients.clear();
        check(!com.futa_gtnh.network.SupplyClients.supports(player,oldConnection),"legacy clients receive no new packet discriminator");
        com.futa_gtnh.network.SupplyClients.register(player,oldConnection);
        check(com.futa_gtnh.network.SupplyClients.supports(player,oldConnection)
            && !com.futa_gtnh.network.SupplyClients.supports(player,newConnection),"capability belongs to the exact connection");
        com.futa_gtnh.network.SupplyClients.register(player,newConnection);
        com.futa_gtnh.network.SupplyClients.forget(player,oldConnection);
        check(com.futa_gtnh.network.SupplyClients.supports(player,newConnection),"delayed old logout cannot clear a reconnected client");
        com.futa_gtnh.network.SupplyClients.forget(player,newConnection);
        check(!com.futa_gtnh.network.SupplyClients.supports(player,newConnection),"logout clears capability");
        com.futa_gtnh.network.PacketStorageAction ready=new com.futa_gtnh.network.PacketStorageAction(com.futa_gtnh.network.PacketStorageAction.SUPPLY_CLIENT_READY);
        ByteBuf hello=Unpooled.buffer(); ready.toBytes(hello);
        com.futa_gtnh.network.PacketStorageAction decodedReady=new com.futa_gtnh.network.PacketStorageAction(); decodedReady.fromBytes(hello); hello.release();
        check(decodedReady.getAction()==36 && decodedReady.getLayoutTag()==null,"capability opt-in uses the unchanged legacy action packet layout");
        PacketSupplyRequest request=new PacketSupplyRequest();
        request.window=17; request.sequence=91; request.operation=PacketSupplyRequest.WATCH; request.minutes=60;
        request.key=SupplyTracker.writeKey(ItemKey.of(new ItemStack(Items.iron_ingot))); request.threshold=Long.MAX_VALUE;
        ByteBuf buf=Unpooled.buffer(); request.toBytes(buf);
        PacketSupplyRequest decoded=new PacketSupplyRequest(); decoded.fromBytes(buf); buf.release();
        check(decoded.window==17 && decoded.sequence==91 && decoded.minutes==60 && decoded.threshold==Long.MAX_VALUE
            && SupplyTracker.readKey(decoded.key).equals(SupplyTracker.readKey(request.key)),"request exact key/window/sequence/long round trip");
        Field valid=PacketSupplyRequest.class.getDeclaredField("valid"); valid.setAccessible(true);
        check(valid.getBoolean(decoded),"valid request accepted");
        request.operation=PacketSupplyRequest.TELEPORT;request.source=source(1).id();
        buf=Unpooled.buffer();request.toBytes(buf);decoded=new PacketSupplyRequest();decoded.fromBytes(buf);buf.release();
        check(valid.getBoolean(decoded) && decoded.operation==3 && decoded.source.equals(source(1).id()),"teleport extends the existing request layout with only a recorded source id");
        request.operation=4;buf=Unpooled.buffer();request.toBytes(buf);decoded=new PacketSupplyRequest();decoded.fromBytes(buf);buf.release();
        check(!valid.getBoolean(decoded),"unknown future operation rejected");
        buf=Unpooled.buffer().writeZero(8193); decoded=new PacketSupplyRequest(); decoded.fromBytes(buf); buf.release();
        check(!valid.getBoolean(decoded),"oversized request rejected before NBT parsing");
        buf=Unpooled.buffer().writeZero(29); decoded=new PacketSupplyRequest(); decoded.fromBytes(buf); buf.release();
        check(!valid.getBoolean(decoded),"short request rejected without underflow");
        check(!PacketSupplyRequest.authorized(null,17),"missing player cannot query or edit settings");
        NBTTagCompound data=new NBTTagCompound(); data.setLong("outgoing",Long.MAX_VALUE);
        PacketSupplySnapshot snapshot=new PacketSupplySnapshot(17,91,data);
        buf=Unpooled.buffer(); snapshot.toBytes(buf); PacketSupplySnapshot copy=new PacketSupplySnapshot(); copy.fromBytes(buf); buf.release();
        check(copy.window==17 && copy.sequence==91 && copy.data.getLong("outgoing")==Long.MAX_VALUE,"response round trip");
    }
    private static void trendAndWarnings() {
        SupplyTracker.StockTrend trend=new SupplyTracker.StockTrend();
        trend.sample(0,10000);
        check(!trend.hasBaseline(0),"fresh trend does not invent a depletion forecast");
        trend.sample(60000,9000);
        check(trend.hasBaseline(60000) && trend.perMinute(60000,9000)==-1000,"stock trend samples the actual balance");
        trend.sample(120000,8000);
        check(trend.perMinute(120000,8000)==-1000,"multi-minute net rate");
        trend.sample(600000,5000);
        check(!trend.hasBaseline(600000),"old trend samples expire");
        SupplyTracker.Watch watch=new SupplyTracker.Watch(4000);
        check(!watch.shouldAlert(5000,0) && watch.shouldAlert(4000,0),"threshold crossing alerts at or below threshold");
        check(!watch.shouldAlert(3999,300000),"staying low does not repeatedly spam alerts");
        check(!watch.shouldAlert(4300,300000) && !watch.shouldAlert(3999,300000),"ten-percent recovery hysteresis");
        check(!watch.shouldAlert(4400,300000) && watch.shouldAlert(4000,300000),"sufficient recovery rearms the warning");
        check(!watch.shouldAlert(4400,300001) && !watch.shouldAlert(3999,300002),"five-minute alert cooldown");
    }
    private static void watchBudget() throws Exception {
        SupplyTracker.start((File)null);
        Field storage=SharedStorageManager.class.getDeclaredField("storage"); storage.setAccessible(true);
        Object previous=storage.get(null); CountingStorage counted=new CountingStorage(); storage.set(null,counted);
        try {
            for(int i=0;i<SupplyTracker.MAX_WATCHES;i++) check(SupplyTracker.watch(ItemKey.of(new ItemStack(Items.dye,1,i)),1),"bounded watch admitted");
            check(!SupplyTracker.watch(ItemKey.of(new ItemStack(Items.apple)),1),"watch count cap enforced");
            for(int i=0;i<20;i++) SupplyTracker.onServerTick();
            check(counted.lookups==16,"at most sixteen stock lookups per second");
            Config.enableSupplyTracking=false;
            for(int i=0;i<100;i++) SupplyTracker.onServerTick();
            check(counted.lookups==16,"disabled tracking performs no polling");
            Config.enableSupplyTracking=true;
        } finally { storage.set(null,previous); SupplyTracker.stop(); }
    }
    private static final class CountingStorage extends SharedStorage {
        int lookups;
        @Override public synchronized long getItemAmount(ItemKey key) { lookups++; return 100; }
    }
}
