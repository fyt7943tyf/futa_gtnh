package com.futa_gtnh.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import com.futa_gtnh.inventory.ContainerProductionTerminal;
import com.futa_gtnh.client.widget.FutaSearchField;

/** Real handheld GUI widgets, visibility and pagination at minimum and normal dimensions. */
public final class ProductionBindingGuiRegression {
    private static int checks;
    public static void run(Minecraft mc)throws Exception {
        INetHandler active=connection(),old=connection();
        ClientProductionState.ready(active,true);
        check(!ClientProductionState.terminalSupported(active),"older production capability alone never enables new packet 49");
        ClientProductionState.terminalReady(active,true);
        check(ClientProductionState.terminalSupported(active) && !ClientProductionState.terminalSupported(old),"handheld capability belongs to exact connection");
        ClientProductionState.ready(null,false);
        check(!ClientProductionState.terminalSupported(active),"disconnect revokes handheld capability");
        for(int[] size:new int[][]{{320,240},{854,480}})layout(mc,size[0],size[1]);
        System.out.println("Production binding GUI: "+checks+" checks passed (minimum layout, read-only controls, binding and create modal)");
    }
    private static void layout(Minecraft mc,int width,int height)throws Exception {
        ContainerProductionTerminal container=new ContainerProductionTerminal(mc.thePlayer,mc.thePlayer.worldObj,0,0,0,0,false);
        GuiProductionBinding gui=new GuiProductionBinding(container);gui.mc=mc;gui.width=width;gui.height=height;
        field(GuiScreen.class,"fontRendererObj").set(gui,mc.fontRenderer);gui.initGui();
        check((Boolean)get(gui,"queryPending") && (Integer)get(gui,"requested")==0,"new FML window waits for its authoritative window ID before querying");
        container.windowId=7;
        @SuppressWarnings("unchecked")List<GuiButton> buttons=(List<GuiButton>)field(GuiScreen.class,"buttonList").get(gui);
        NBTTagCompound data=new NBTTagCompound();data.setInteger("linePages",2);
        NBTTagList lines=new NBTTagList();for(int i=0;i<8;i++){
            NBTTagCompound line=new NBTTagCompound();line.setString("id","line-"+i);line.setString("name","产线 "+i);lines.appendTag(line);
        }data.setTag("lines",lines);apply(gui,data);call(gui,"updateButtons");bounds(gui,buttons);
        check(!button(buttons,1).enabled && !button(buttons,2).enabled,"read-only viewers cannot create or unbind");
        int left=(Integer)get(gui,"left"),top=(Integer)get(gui,"top"),rows=(Integer)get(gui,"rows");
        gui.mouseClicked(left+15,top+90,0);
        check(get(gui,"pendingAction").equals(""),"read-only row clicks cannot issue mutations");
        check(rows<8 && (Integer)call(gui,"maxScroll")==8-rows,"minimum view can reach every server-page row by local scrolling");
        data.setBoolean("canEdit",true);call(gui,"updateButtons");
        NBTTagCompound inherited=new NBTTagCompound();inherited.setString("lineId","line-0");inherited.setBoolean("autoBound",true);
        data.setTag("currentBinding",inherited);call(gui,"updateButtons");
        check(!button(buttons,2).enabled,"inherited bus unbinding is directed to its parent interface");
        check(button(buttons,1).enabled,"inherited bus still permits creating a line for a manual override");
        gui.mouseClicked(left+15,top+90,0);check(get(gui,"pendingAction").equals("bind"),"admin row click enters binding request path");
        gui.actionPerformed(button(buttons,1));check((Boolean)get(gui,"editing"),"create opens name editor");bounds(gui,buttons);
        ((FutaSearchField)get(gui,"editor")).setText("",false);gui.keyTyped('\n',28);
        check((Boolean)get(gui,"editing"),"blank line name does not submit or dismiss editor");
        gui.keyTyped('\0',1);check(!(Boolean)get(gui,"editing"),"escape cancels editor while retaining binding window");
        check(!gui.doesGuiPauseGame(),"handheld binding view continues server ticks");gui.onGuiClosed();
    }
    private static void bounds(Object gui,List<GuiButton> buttons)throws Exception {
        int left=(Integer)get(gui,"left"),top=(Integer)get(gui,"top"),w=(Integer)get(gui,"panelWidth"),h=(Integer)get(gui,"panelHeight");
        for(GuiButton a:buttons)if(a.visible){
            check(a.xPosition>=left && a.yPosition>=top && a.xPosition+a.width<=left+w && a.yPosition+a.height<=top+h,"visible binding control fits panel");
            for(GuiButton b:buttons)if(b.visible && a.id<b.id)check(a.xPosition+a.width<=b.xPosition || b.xPosition+b.width<=a.xPosition
                || a.yPosition+a.height<=b.yPosition || b.yPosition+b.height<=a.yPosition,"visible binding controls do not overlap");
        }
    }
    private static void apply(Object gui,NBTTagCompound data)throws Exception{Method m=GuiProductionBinding.class.getDeclaredMethod("apply",NBTTagCompound.class);m.setAccessible(true);m.invoke(gui,data);}
    private static Object call(Object gui,String name)throws Exception{Method m=GuiProductionBinding.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(gui);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object get(Object gui,String name)throws Exception{return field(GuiProductionBinding.class,name).get(gui);}
    private static GuiButton button(List<GuiButton> buttons,int id){return buttons.stream().filter(b->b.id==id).findFirst().get();}
    private static INetHandler connection(){return (INetHandler)Proxy.newProxyInstance(ProductionBindingGuiRegression.class.getClassLoader(),new Class<?>[]{INetHandler.class},(p,m,a)->null);}
    private static void check(boolean okay,String reason){if(!okay)throw new AssertionError(reason);checks++;}
}
