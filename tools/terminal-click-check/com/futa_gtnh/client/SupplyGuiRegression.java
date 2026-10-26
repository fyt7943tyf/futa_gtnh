package com.futa_gtnh.client;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;
import cpw.mods.fml.common.Loader;
import sun.misc.Unsafe;

/** Real GUI initialization/click/editor paths at minimum and large GUI sizes; no native display or network. */
public final class SupplyGuiRegression {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Loader.injectData(new Object[]{"7","99","40","1614","1.7.10","9.05",new File("."),Collections.emptyList()});
        Bootstrap.func_151354_b();
        Field field=Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); Unsafe unsafe=(Unsafe)field.get(null);
        Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);
        field=Minecraft.class.getDeclaredField("theMinecraft"); field.setAccessible(true); field.set(null,mc);
        mc.gameSettings=new GameSettings();
        mc.fontRenderer=(FontRenderer)unsafe.allocateInstance(TestFont.class); mc.fontRenderer.FONT_HEIGHT=9;
        mc.thePlayer=(EntityClientPlayerMP)unsafe.allocateInstance(EntityClientPlayerMP.class);
        mc.thePlayer.inventory=new InventoryPlayer(mc.thePlayer);
        mc.thePlayer.worldObj=(net.minecraft.world.World)unsafe.allocateInstance(net.minecraft.client.multiplayer.WorldClient.class);
        field(net.minecraft.world.World.class,"isRemote").setBoolean(mc.thePlayer.worldObj,true);
        connectionIsolation();
        ProductionGuiRegression.run(mc);
        PublicMeGuiRegression.run(mc);
        // No open window means request dispatch is intentionally disabled; all actual widget methods still run.
        for(int[] size:new int[][]{{320,240},{854,480}}) runSize(mc,size[0],size[1]);
        System.out.println("Supply GUI: "+checks+" checks passed (layout, selection, paged zero-stock history and modal editors)");
    }
    private static void connectionIsolation() throws Exception {
        net.minecraft.network.INetHandler active=(net.minecraft.network.INetHandler)java.lang.reflect.Proxy.newProxyInstance(
            SupplyGuiRegression.class.getClassLoader(),new Class<?>[]{net.minecraft.network.INetHandler.class},(proxy,method,args)->null);
        net.minecraft.network.INetHandler stale=(net.minecraft.network.INetHandler)java.lang.reflect.Proxy.newProxyInstance(
            SupplyGuiRegression.class.getClassLoader(),new Class<?>[]{net.minecraft.network.INetHandler.class},(proxy,method,args)->null);
        field(ClientSupplyState.class,"connection").set(null,active);
        SupplySource marker=new SupplySource(SupplySource.AE_MACHINE,-1,-1,64,16,3,"");
        SupplyHighlight.show(marker);
        check(SupplyHighlight.selected(marker) && SupplyHighlight.label().contains("-1, 64, 16"),"highlight retains the exact dimension and fallback-device position");
        SupplyHighlight.show(SupplySource.UNIDENTIFIED);
        check(SupplyHighlight.selected(marker),"unknown coordinates cannot replace a valid world marker");
        field(SupplyHighlight.class,"expires").setLong(null,System.nanoTime()-1);
        check(SupplyHighlight.target()==null,"world marker expires without any world polling");
        SupplyHighlight.show(marker);
        NBTTagCompound ready=new NBTTagCompound(); ready.setBoolean("ready",true);
        ClientSupplyState.receive(stale,new com.futa_gtnh.network.PacketSupplySnapshot(-2,0,ready));
        check(!ClientSupplyState.supported(),"old connection cannot acknowledge a new server's capability");
        ClientSupplyState.receive(active,new com.futa_gtnh.network.PacketSupplySnapshot(-2,0,ready));
        check(ClientSupplyState.supported(),"matching server acknowledgment enables the new request discriminator");
        ClientSupplyState.receive(stale,new com.futa_gtnh.network.PacketSupplySnapshot(1,1,new NBTTagCompound()));
        check(ClientSupplyState.response()==null,"old connection response cannot leak into the current GUI");
        ClientSupplyState.receive(active,new com.futa_gtnh.network.PacketSupplySnapshot(1,2,new NBTTagCompound()));
        check(ClientSupplyState.response().sequence==2,"current connection response reaches the GUI mailbox");
        NBTTagCompound travel=new NBTTagCompound();travel.setTag("navigate",new NBTTagCompound());
        ClientSupplyState.receive(stale,new com.futa_gtnh.network.PacketSupplySnapshot(1,3,travel));
        check(ClientSupplyState.navigation()==null,"old connection cannot install a navigation result");
        ClientSupplyState.receive(active,new com.futa_gtnh.network.PacketSupplySnapshot(1,4,travel));
        check(ClientSupplyState.navigation().sequence==4,"navigation uses a separate mailbox even if normal snapshots arrive");
        java.lang.reflect.Constructor<ClientSupplyState> constructor=ClientSupplyState.class.getDeclaredConstructor();
        constructor.setAccessible(true); constructor.newInstance().disconnected(null);
        check(!ClientSupplyState.supported() && ClientSupplyState.response()==null && ClientSupplyState.navigation()==null
            && SupplyHighlight.target()==null,"disconnect clears capability, responses and world marker");
    }
    private static void runSize(Minecraft mc,int width,int height) throws Exception {
        ContainerSharedTerminal container=new ContainerSharedTerminal(mc.thePlayer.inventory,null);
        GuiSupplyMonitor gui=new GuiSupplyMonitor(container); gui.mc=mc; gui.width=width; gui.height=height;
        Field font=GuiScreen.class.getDeclaredField("fontRendererObj"); font.setAccessible(true); font.set(gui,mc.fontRenderer);
        gui.initGui();
        check(!gui.doesGuiPauseGame(),"tracking view must not pause the integrated server");
        container.getCraftMatrix().setInventorySlotContents(0,new ItemStack(Items.iron_ingot,7));
        container.withClientPageSwitch(()->container.onContainerClosed(mc.thePlayer));
        check(container.getCraftMatrix().getStackInSlot(0).stackSize==7,"opening a local tracking page preserves the current crafting matrix");
        container.onContainerClosed(mc.thePlayer);
        check(container.getCraftMatrix().getStackInSlot(0)==null,"an actual window close still performs normal client cleanup");
        int left=(Integer)get(gui,"left"),top=(Integer)get(gui,"top"),panelWidth=(Integer)get(gui,"panelWidth"),panelHeight=(Integer)get(gui,"panelHeight");
        @SuppressWarnings("unchecked") List<GuiButton> buttons=(List<GuiButton>)field(GuiScreen.class,"buttonList").get(gui);
        for(GuiButton b:buttons) check(b.xPosition>=left && b.yPosition>=top && b.xPosition+b.width<=left+panelWidth
            && b.yPosition+b.height<=top+panelHeight,"all controls fit panel at "+width+"x"+height);
        for(int i=0;i<buttons.size();i++) for(int j=i+1;j<buttons.size();j++) {
            GuiButton a=buttons.get(i),b=buttons.get(j);
            check(a.xPosition+a.width<=b.xPosition || b.xPosition+b.width<=a.xPosition || a.yPosition+a.height<=b.yPosition
                || b.yPosition+b.height<=a.yPosition,"buttons do not overlap");
        }
        check((Integer)get(gui,"mode")==3,"zero-stock history is available immediately");
        NBTTagCompound data=data(); apply(gui,data); invoke(gui,"refreshList"); invoke(gui,"updateButtons");
        @SuppressWarnings("unchecked") List<StorageViewEntry> shown=(List<StorageViewEntry>)get(gui,"shown");
        check(shown.size()==8,"history page renders all issued keys including absent stock");
        Object expected=shown.get(0).getItemKey();
        gui.mouseClicked(left+10,top+68,0);
        check(expected.equals(get(gui,"target")),"real history row click selects exact item key");
        apply(gui,data); invoke(gui,"updateButtons");
        int listWidth=(Integer)get(gui,"listWidth");
        gui.mouseClicked(left+listWidth+10,top+102,0);
        check(get(gui,"selectedSource")!=null,"real source row click selects inlet");
        invoke(gui,"updateButtons");
        check(button(buttons,13).enabled && button(buttons,14).enabled,"located sources allow highlight and server-supported teleport");
        gui.actionPerformed(button(buttons,13));invoke(gui,"updateButtons");
        check(SupplyHighlight.target()!=null,"highlight action selects a world marker without sending a packet");
        gui.actionPerformed(button(buttons,13));
        check(SupplyHighlight.target()==null,"highlight action toggles off the selected marker");
        set(gui,"selectedSource",SupplySource.UNIDENTIFIED.write());invoke(gui,"updateButtons");
        check(!button(buttons,13).enabled && !button(buttons,14).enabled,"unidentified sources never offer fake-coordinate navigation");
        set(gui,"selectedSource",data.getTagList("sources",10).getCompoundTagAt(0));
        data.setBoolean("teleportEnabled",false);invoke(gui,"updateButtons");
        check(button(buttons,13).enabled && !button(buttons,14).enabled,"old servers and server opt-out keep client highlighting available");
        data.setBoolean("teleportEnabled",true);
        invoke(gui,"updateButtons"); gui.actionPerformed(button(buttons,9));
        check((Integer)get(gui,"editMode")==2,"rename opens a modal editor");
        NBTTagCompound empty=data(); empty.setTag("sources",new NBTTagList()); apply(gui,empty);
        check(get(gui,"selectedSource")!=null,"an in-flight snapshot cannot invalidate a modal rename target");
        FutaSearchField editor=(FutaSearchField)get(gui,"editor"); editor.setText("产线 A",false);
        gui.keyTyped('\n',28);
        check((Integer)get(gui,"editMode")==0,"rename save closes cleanly even without live transport");
        apply(gui,data); invoke(gui,"updateButtons"); gui.actionPerformed(button(buttons,8));
        editor=(FutaSearchField)get(gui,"editor"); editor.setText("-1",false);
        gui.keyTyped('\n',28);
        check((Integer)get(gui,"editMode")==1 && get(gui,"error")!=null,"invalid negative threshold stays in editor");
        editor.setText("",false); gui.keyTyped('\n',28);
        check((Integer)get(gui,"editMode")==0,"blank threshold removes watch");
        apply(gui,data); invoke(gui,"refreshList"); invoke(gui,"updateButtons");
        int rows=(Integer)get(gui,"rows");
        if(rows<8) {
            gui.actionPerformed(button(buttons,4));
            check((Integer)get(gui,"itemPage")==1 && (Integer)get(gui,"watchPage")==0,"short GUI visits the rest of a server page before skipping to next page");
        }
        gui.onGuiClosed();
    }
    private static NBTTagCompound data() {
        NBTTagCompound data=new NBTTagCompound(); data.setBoolean("enabled",true); data.setBoolean("writable",true); data.setBoolean("okay",true);
        data.setInteger("watchPages",2); data.setInteger("sourcePages",1); data.setLong("threshold",-1);
        data.setBoolean("teleportEnabled",true);
        NBTTagList watched=new NBTTagList();
        for(int i=0;i<8;i++) watched.appendTag(SupplyTracker.writeKey(ItemKey.of(new ItemStack(Items.dye,1,i))));
        data.setTag("watches",watched);
        NBTTagList sources=new NBTTagList();
        SupplySource source=new SupplySource(SupplySource.AE_BUS,0,123,64,456,5,"");
        NBTTagCompound row=source.write();row.setString("id",source.id());row.setString("name","line A");row.setLong("out",64);
        sources.appendTag(row);data.setTag("sources",sources);return data;
    }
    private static Field field(Class<?> type,String name) throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
    private static Object get(Object object,String name) throws Exception { return field(GuiSupplyMonitor.class,name).get(object); }
    private static void set(Object object,String name,Object value) throws Exception { field(GuiSupplyMonitor.class,name).set(object,value); }
    private static void invoke(Object object,String name) throws Exception { Method m=GuiSupplyMonitor.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(object); }
    private static void apply(GuiSupplyMonitor gui,NBTTagCompound data) throws Exception {
        Method m=GuiSupplyMonitor.class.getDeclaredMethod("apply",NBTTagCompound.class);m.setAccessible(true);m.invoke(gui,data);
    }
    private static GuiButton button(List<GuiButton> buttons,int id) { for(GuiButton b:buttons)if(b.id==id)return b;throw new AssertionError(id); }
    private static void check(boolean okay,String text){if(!okay)throw new AssertionError(text);checks++;}
    private static final class TestFont extends FontRenderer {
        TestFont(){super(new GameSettings(),new ResourceLocation("textures/font/ascii.png"),null,false);}
        @Override public int getStringWidth(String text){return text.length()*6;}
        @Override public String trimStringToWidth(String text,int width){return text.substring(0,Math.min(text.length(),Math.max(0,width/6)));}
        @Override public int getCharWidth(char character){return 6;}
    }
}
