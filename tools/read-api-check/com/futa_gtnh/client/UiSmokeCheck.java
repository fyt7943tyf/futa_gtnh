package com.futa_gtnh.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.aewireless.TileAeSignalTransmitter;
import com.futa_gtnh.block.TileEntityIoNode;
import com.futa_gtnh.block.TileEntityLootMachine;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.inventory.ContainerAeSignalTransmitter;
import com.futa_gtnh.inventory.ContainerIoNode;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.inventory.ContainerStorageBus;
import com.futa_gtnh.rts.client.GuiRtsOverlay;
import com.futa_gtnh.storagebus.StorageBusConfig;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in real-client smoke capture. Only the isolated development world is used. */
public final class UiSmokeCheck {
    private final List<Supplier<GuiScreen>> screens = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private boolean launched;
    private int ticks;
    private int index = -1;
    private GuiScreen current;
    private ContainerSharedTerminal sharedContainer;
    private boolean capturedAll;
    private long smokeWaitSince;

    public static void register() {
        FMLCommonHandler.instance().bus().register(new UiSmokeCheck());
        com.futa_gtnh.me.PublicMeWorldSmoke.register();
        com.futa_gtnh.me.GtswnQuantumWorldSmoke.register();
        ProductionTerminalWorldSmoke.register();
        com.futa_gtnh.me.PublicMeSubnetFlowWorldSmoke.register();
    }

    private void add(String name, Supplier<GuiScreen> factory) {
        names.add(name); screens.add(factory);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!launched) {
            if (!(mc.currentScreen instanceof GuiMainMenu) || ++ticks < 30) return;
            launched = true; ticks = 0;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.launchIntegratedServer(com.futa_gtnh.me.PublicMeWorldSmoke.saveName(), com.futa_gtnh.me.PublicMeWorldSmoke.worldName(), new WorldSettings(12345L,
                WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (smokeWaitSince==0) smokeWaitSince=System.currentTimeMillis();
        if (!com.futa_gtnh.me.PublicMeWorldSmoke.isComplete() && System.currentTimeMillis()-smokeWaitSince>180000L) {
            throw new AssertionError("PUBLIC_ME_SMOKE_FAILED wall-clock timeout waiting for real server ticks");
        }
        if (com.futa_gtnh.me.PublicMeWorldSmoke.failure()!=null) {
            throw new AssertionError(com.futa_gtnh.me.PublicMeWorldSmoke.failure());
        }
        if (com.futa_gtnh.me.GtswnQuantumWorldSmoke.failure()!=null) {
            throw new AssertionError(com.futa_gtnh.me.GtswnQuantumWorldSmoke.failure());
        }
        if (!com.futa_gtnh.me.GtswnQuantumWorldSmoke.isComplete() && System.currentTimeMillis()-smokeWaitSince>240000L) {
            throw new AssertionError("GTSWN_QUANTUM_SMOKE_FAILED wall-clock timeout");
        }
        if (ProductionTerminalWorldSmoke.failure()!=null) throw new AssertionError(ProductionTerminalWorldSmoke.failure());
        if (!ProductionTerminalWorldSmoke.isComplete()) return;
        if (com.futa_gtnh.me.PublicMeSubnetFlowWorldSmoke.failure()!=null) throw new AssertionError(com.futa_gtnh.me.PublicMeSubnetFlowWorldSmoke.failure());
        if (!com.futa_gtnh.me.PublicMeSubnetFlowWorldSmoke.isComplete()) {
            if(System.currentTimeMillis()-smokeWaitSince>300000L)throw new AssertionError("PUBLIC_ME_SUBNET_FLOW_SMOKE_FAILED wall-clock timeout");
            return;
        }
        if (capturedAll) {
            if(com.futa_gtnh.me.PublicMeWorldSmoke.isComplete() && com.futa_gtnh.me.GtswnQuantumWorldSmoke.isComplete()) {
                System.out.println("UI_SMOKE_SUCCESS " + screens.size() + " screens");mc.shutdown();
            }
            return;
        }
        if (screens.isEmpty()) {
            // The vanilla development window is 854x480; scale 1 keeps the inventory canvas visible.
            mc.gameSettings.guiScale = 1;
            mc.gameSettings.language = "zh_CN";
            for (net.minecraft.client.resources.Language language : mc.getLanguageManager().getLanguages()) {
                if (language.getLanguageCode().equals("zh_CN")) mc.getLanguageManager().setCurrentLanguage(language);
            }
            mc.refreshResources();
            TileEntitySharedTerminal terminal = new TileEntitySharedTerminal(); terminal.setWorldObj(mc.theWorld);
            ContainerSharedTerminal storage = new ContainerSharedTerminal(mc.thePlayer.inventory, terminal);
            sharedContainer = storage;
            TileEntityIoNode node = new TileEntityIoNode(); node.setWorldObj(mc.theWorld);
            TileAeSignalTransmitter transmitter = new TileAeSignalTransmitter(); transmitter.setWorldObj(mc.theWorld);
            TileEntityLootMachine loot = new TileEntityLootMachine(); loot.setWorldObj(mc.theWorld);
            TileEntityStorageBus bus = new TileEntityStorageBus(); bus.setWorldObj(mc.theWorld);
            bus.addFace(net.minecraftforge.common.util.ForgeDirection.EAST);
            ClientStorageBus.setConfig(mc.theWorld.provider.dimensionId, bus.xCoord, bus.yCoord, bus.zCoord,
                net.minecraftforge.common.util.ForgeDirection.EAST.ordinal(),
                1 << net.minecraftforge.common.util.ForgeDirection.EAST.ordinal(),
                new StorageBusConfig().writeToNbt(), "", false, false);
            add("settings", GuiClientConfig::new);
            add("terminal", () -> new GuiSharedTerminal(storage));
            add("supply_monitor", () -> new GuiSupplyMonitor(storage));
            add("supply_monitor_small", () -> new GuiSupplyMonitor(storage));
            add("supply_threshold", () -> new GuiSupplyMonitor(storage));
            add("supply_threshold_small", () -> new GuiSupplyMonitor(storage));
            add("production_lines", () -> new GuiProductionLines(null, storage));
            add("public_me_crafting", () -> new GuiPublicMeCrafting(storage));
            add("production_lines_small", () -> new GuiProductionLines(null, storage));
            for (String state : new String[] { "normal", "empty", "editor", "readonly", "small", "automatic_small" }) {
                add("production_binding_" + state, () -> new GuiProductionBinding(
                    new com.futa_gtnh.inventory.ContainerProductionTerminal(mc.thePlayer, mc.theWorld, 0, 0, 0, 5, false)));
            }
            add("public_me_crafting_small", () -> new GuiPublicMeCrafting(storage));
            add("limits", () -> new GuiStorageLimits(storage));
            add("terminal_io", () -> new GuiTerminalIo(storage));
            add("node", () -> new GuiIoNode(new ContainerIoNode(node, mc.thePlayer), 2));
            add("storage_bus", () -> new GuiStorageBus(new ContainerStorageBus(bus, mc.thePlayer,
                net.minecraftforge.common.util.ForgeDirection.EAST.ordinal())));
            add("transmitter", () -> new GuiAeSignalTransmitter(new ContainerAeSignalTransmitter(transmitter, mc.thePlayer), mc.thePlayer));
            if (CommonProxy.swiftStep != null) add("swift_step", () -> new GuiSwiftStep(new ItemStack(CommonProxy.swiftStep)));
            add("locator", GuiLocatorWand::new);
            add("minigame", GuiMinigameHelper::new);
            add("rts", GuiRtsOverlay::new);
            if (RoguelikeMapClient.isAvailable()) add("dungeon_map", GuiRoguelikeMap::new);
            if (CommonProxy.blockLootMachine != null) add("loot", () -> new GuiLootMachine(mc.thePlayer.inventory, loot));
            next(mc); return;
        }
        if (index == 0 && ticks == 15) {
            try {
                java.lang.reflect.Field field = GuiScreen.class.getDeclaredField("buttonList"); field.setAccessible(true);
                @SuppressWarnings("unchecked") List<net.minecraft.client.gui.GuiButton> buttons = (List<net.minecraft.client.gui.GuiButton>) field.get(current);
                net.minecraft.client.gui.GuiButton toggle = buttons.stream().filter(button -> button.id == 1).findFirst().get();
                String before = toggle.displayString;
                java.lang.reflect.Method click = FutaGuiScreen.class.getDeclaredMethod("mouseClicked", int.class, int.class, int.class); click.setAccessible(true);
                click.invoke(current, toggle.xPosition + 2, toggle.yPosition + 2, 0);
                if (before.equals(toggle.displayString)) throw new AssertionError("MUI2 click did not toggle setting");
                System.out.println("UI_SMOKE_CLICK_SUCCESS");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        if (++ticks < 35) return;
        if (mc.currentScreen != current) throw new AssertionError("UI closed unexpectedly: " + names.get(index));
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) throw new AssertionError("GL error " + error + " in " + names.get(index));
        File output = new File("../build/ui-smoke"); output.mkdirs();
        ScreenShotHelper.saveScreenshot(output, names.get(index) + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        System.out.println("UI_SMOKE_CAPTURE " + names.get(index));
        // Reinitialization exercises resize/tree disposal without altering the actual window.
        current.setWorldAndResolution(mc, Math.max(320, current.width - 20), Math.max(240, current.height - 10));
        current.setWorldAndResolution(mc, current.width + 20, current.height + 10);
        next(mc);
    }

    private void next(Minecraft mc) {
        ticks = 0;
        if (++index == screens.size()) {
            capturedAll=true;mc.displayGuiScreen(null);return;
        }
        current = screens.get(index).get();
        mc.gameSettings.guiScale = names.get(index).endsWith("_small") ? 2 : 1;
        if (current instanceof GuiProductionLines || current instanceof GuiPublicMeCrafting || current instanceof GuiSupplyMonitor || current instanceof GuiProductionBinding) {
            // These are explicit client-only display fixtures. No plan, metadata edit or gameplay packet is sent.
            // Preserve the real local container lifecycle so updateScreen cannot immediately close the preview.
            ClientProductionState.ready(null, false);
            ClientPublicMeState.ready(null, false);
            if(current instanceof GuiSupplyMonitor){
                try {
                    java.lang.reflect.Field supported=ClientSupplyState.class.getDeclaredField("supported");
                    supported.setAccessible(true);supported.setBoolean(null,false);
                    java.lang.reflect.Field response=ClientSupplyState.class.getDeclaredField("response");
                    response.setAccessible(true);response.set(null,null);
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            }
            sharedContainer.withClientPageSwitch(() -> {
                mc.displayGuiScreen(current);
                mc.thePlayer.openContainer = sharedContainer;
            });
            if (current instanceof GuiProductionBinding) bindingFixture(current, mc, names.get(index));
            else if (current instanceof GuiProductionLines) productionFixture(current);
            else if(current instanceof GuiSupplyMonitor) supplyFixture(current,names.get(index).startsWith("supply_threshold"));
            else craftingFixture(current);
        } else mc.displayGuiScreen(current);
    }

    private static void set(Object target,String field,Object value) {
        try {
            java.lang.reflect.Field member=target.getClass().getDeclaredField(field);
            member.setAccessible(true);member.set(target,value);
        } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void bindingFixture(GuiScreen screen, Minecraft mc, String name) {
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("canEdit", !name.endsWith("readonly"));data.setInteger("linePages", 2);
        data.setString("targetName", name.contains("automatic") ? "ME 流体存储总线" : "ME 流体输出总线");
        data.setTag("bindingTarget", new com.futa_gtnh.supply.SupplySource(
            com.futa_gtnh.supply.SupplySource.AE_MACHINE, 0, 120, 64, 220, 5, "").write());
        NBTTagCompound binding = new NBTTagCompound();binding.setString("lineId", "line-1");binding.setString("lineName", "聚乙烯产线");
        binding.setBoolean("autoBound",name.contains("automatic"));
        data.setTag("currentBinding", binding);
        NBTTagList lines = new NBTTagList();
        String[] labels = { "电路装配线", "聚乙烯产线", "基础金属处理", "氯碱化工", "矿石处理", "电解水", "燃料供应", "备用产线" };
        if (!name.endsWith("empty")) for (int i = 0; i < labels.length; i++) {
            NBTTagCompound row = new NBTTagCompound();row.setString("id", "line-" + i);row.setString("name", labels[i]);lines.appendTag(row);
        }
        data.setTag("lines", lines);
        try {
            java.lang.reflect.Field field=GuiProductionBinding.class.getDeclaredField("container");field.setAccessible(true);
            mc.thePlayer.openContainer=(net.minecraft.inventory.Container)field.get(screen);
            java.lang.reflect.Method apply=GuiProductionBinding.class.getDeclaredMethod("apply",NBTTagCompound.class);apply.setAccessible(true);apply.invoke(screen,data);
            java.lang.reflect.Method buttons=GuiProductionBinding.class.getDeclaredMethod("updateButtons");buttons.setAccessible(true);buttons.invoke(screen);
            if (name.endsWith("editor")) {
                set(screen,"editing",true);
                java.lang.reflect.Method editor=GuiProductionBinding.class.getDeclaredMethod("openEditor");editor.setAccessible(true);editor.invoke(screen);buttons.invoke(screen);
            }
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        set(screen,"lastQuery",System.currentTimeMillis()+60000L);set(screen,"status","");
    }

    private static NBTTagCompound resource(ItemStack stack,long stored,long craft,long missing) {
        NBTTagCompound row=com.futa_gtnh.supply.SupplyTracker.writeKey(com.futa_gtnh.shared.ItemKey.of(stack));
        row.setLong("stored",stored);row.setLong("craft",craft);row.setLong("missing",missing);return row;
    }

    private static void supplyFixture(GuiScreen screen,boolean watched) {
        com.futa_gtnh.shared.FluidKey key=com.futa_gtnh.shared.FluidKey.of(net.minecraftforge.fluids.FluidRegistry.WATER,null);
        NBTTagCompound data=new NBTTagCompound();
        data.setBoolean("enabled",true);data.setBoolean("writable",true);data.setBoolean("okay",true);
        data.setBoolean("teleportEnabled",true);data.setLong("threshold",watched?Long.MAX_VALUE:-1);
        data.setLong("incoming",24000);data.setLong("outgoing",24000);data.setLong("stock",0);
        data.setInteger("sourcePages",1);data.setInteger("watchPages",1);
        NBTTagList history=new NBTTagList();history.appendTag(com.futa_gtnh.supply.SupplyTracker.writeKey(key));data.setTag("watches",history);
        com.futa_gtnh.supply.SupplySource source=new com.futa_gtnh.supply.SupplySource(
            com.futa_gtnh.supply.SupplySource.IO,0,120,64,220,5,"");
        NBTTagCompound row=source.write();row.setString("id",source.id());row.setString("name","电解水产线输入 IO");
        row.setLong("out",24000);row.setDouble("outPerMinute",2400);
        NBTTagList sources=new NBTTagList();sources.appendTag(row);data.setTag("sources",sources);
        try {
            java.lang.reflect.Method apply=GuiSupplyMonitor.class.getDeclaredMethod("apply",NBTTagCompound.class);
            apply.setAccessible(true);apply.invoke(screen,data);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        set(screen,"target",key);set(screen,"targetName",key.prototype().getLocalizedName());
        set(screen,"selectedSource",row);set(screen,"error",null);
        set(screen,"lastRequest",System.currentTimeMillis()+60000L);
    }

    private static void productionFixture(GuiScreen screen) {
        String lineId="00000000-0000-0000-0000-000000000001";
        NBTTagCompound data=new NBTTagCompound();
        data.setBoolean("enabled",true);data.setBoolean("writable",true);data.setBoolean("canEdit",true);
        data.setBoolean("hasRate",true);data.setBoolean("bucketed",true);data.setBoolean("lineFound",true);
        data.setBoolean("sourceFound",true);data.setLong("observedMillis",600000);data.setInteger("minutes",10);
        data.setInteger("linePages",1);data.setInteger("sourcePages",1);data.setInteger("resourcePages",1);
        data.setString("lineId",lineId);data.setString("lineName","电路装配线");
        NBTTagList lines=new NBTTagList();
        String[] labels={"电路装配线","聚乙烯产线","基础金属处理"};
        for(int i=0;i<labels.length;i++){
            NBTTagCompound line=new NBTTagCompound();line.setString("id",String.format("00000000-0000-0000-0000-%012d",i+1));
            line.setString("name",labels[i]);line.setInteger("sourceCount",3+i);lines.appendTag(line);
        }
        data.setTag("lines",lines);
        NBTTagList sources=new NBTTagList();
        String[] deviceNames={"组装机输入接口","成品回收接口","水冷回路"};
        for(int i=0;i<deviceNames.length;i++) {
            com.futa_gtnh.supply.SupplySource source=new com.futa_gtnh.supply.SupplySource(
                i==0?com.futa_gtnh.supply.SupplySource.AE_BUS:com.futa_gtnh.supply.SupplySource.IO,0,120+i,64,220,5,"");
            NBTTagCompound row=source.write();row.setString("id",source.id());row.setString("name",deviceNames[i]);
            row.setString("lineId",lineId);row.setString("lineName","电路装配线");row.setBoolean("active",true);
            row.setDouble("itemOutPerMinute",i==0?1200:0);row.setDouble("itemInPerMinute",i==1?300:0);
            row.setDouble("fluidOutPerMinute",i==2?24000:0);row.setDouble("fluidInPerMinute",i==2?12000:0);
            sources.appendTag(row);
        }
        data.setTag("sources",sources);
        NBTTagCompound totals=new NBTTagCompound();totals.setDouble("itemOutPerMinute",1200);totals.setDouble("itemInPerMinute",300);
        totals.setDouble("fluidOutPerMinute",24000);totals.setDouble("fluidInPerMinute",12000);data.setTag("totals",totals);
        set(screen,"data",data);set(screen,"lineId",lineId);set(screen,"lineName","电路装配线");
        set(screen,"allSources",false);set(screen,"status","");set(screen,"queryPending",false);
        set(screen,"lastRequest",System.currentTimeMillis());
    }

    private static void craftingFixture(GuiScreen screen) {
        NBTTagCompound output=resource(new ItemStack(Blocks.piston),0,0,0);
        NBTTagCompound data=new NBTTagCompound();data.setBoolean("online",true);data.setBoolean("canCraft",true);
        data.setInteger("catalogPages",1);data.setInteger("catalogCount",4);
        NBTTagList catalog=new NBTTagList();catalog.appendTag(output);
        catalog.appendTag(resource(new ItemStack(Items.repeater),0,0,0));
        catalog.appendTag(resource(new ItemStack(Items.comparator),0,0,0));
        catalog.appendTag(resource(new ItemStack(Blocks.hopper),0,0,0));data.setTag("catalog",catalog);
        NBTTagCompound job=new NBTTagCompound();job.setInteger("state",2);job.setLong("bytes",4096);job.setLong("amount",64);
        job.setString("planId","00000000-0000-0000-0000-000000000099");job.setTag("output",output);
        job.setString("message","计划就绪，需要 4096 字节 CPU 存储；确认后提交");job.setInteger("rowPages",1);
        NBTTagList rows=new NBTTagList();rows.appendTag(resource(new ItemStack(Items.iron_ingot),64,0,0));
        rows.appendTag(resource(new ItemStack(Items.redstone),64,0,0));
        rows.appendTag(resource(new ItemStack(Blocks.cobblestone),256,0,0));
        rows.appendTag(resource(new ItemStack(Blocks.planks),0,192,0));job.setTag("rows",rows);data.setTag("job",job);
        set(screen,"data",data);set(screen,"target",output);set(screen,"amountText","64");set(screen,"notice","");
        set(screen,"queryPending",false);set(screen,"lastRequest",System.currentTimeMillis());
        try {
            java.lang.reflect.Field field=screen.getClass().getDeclaredField("amount");field.setAccessible(true);
            ((com.futa_gtnh.client.widget.FutaSearchField)field.get(screen)).setText("64",false);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
}
