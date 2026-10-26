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
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.PacketProductionSnapshot;
import com.futa_gtnh.supply.SupplySource;

/** Invoked after SupplyGuiRegression initializes its headless client and font fixtures. */
public final class ProductionGuiRegression {
    private static int checks;
    public static int run(Minecraft mc) throws Exception {
        sessions();
        for(int[] size:new int[][]{{320,240},{854,480}})layout(mc,size[0],size[1]);
        System.out.println("Production GUI: "+checks+" checks passed (session isolation, small layout, selection and admin controls)");
        return checks;
    }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    private static Field field(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static Object get(Object target,String name)throws Exception{return field(GuiProductionLines.class,name).get(target);}
    private static void set(Object target,String name,Object value)throws Exception{field(GuiProductionLines.class,name).set(target,value);}
    private static void call(Object target,String name)throws Exception{Method method=GuiProductionLines.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}
    private static GuiButton button(List<GuiButton> buttons,int id){for(GuiButton button:buttons)if(button.id==id)return button;throw new AssertionError(id);}
    private static INetHandler connection(){return (INetHandler)Proxy.newProxyInstance(ProductionGuiRegression.class.getClassLoader(),new Class<?>[]{INetHandler.class},(proxy,method,args)->null);}
    private static void sessions(){
        INetHandler active=connection(),old=connection();
        ClientProductionState.ready(active,true);
        check(ClientProductionState.supported(active) && !ClientProductionState.supported(old),"production capability belongs to the exact connection");
        long first=ClientProductionState.begin(active);
        ClientProductionState.receive(old,new PacketProductionSnapshot(1,first,1,new NBTTagCompound()));
        check(ClientProductionState.response()==null,"old connection cannot supply production statistics");
        ClientProductionState.receive(active,new PacketProductionSnapshot(1,first,2,new NBTTagCompound()));
        ClientProductionState.receive(active,new PacketProductionSnapshot(1,first,1,new NBTTagCompound()));
        check(ClientProductionState.response().sequence==2,"older response cannot replace newer production snapshot");
        long next=ClientProductionState.begin(active);
        ClientProductionState.receive(active,new PacketProductionSnapshot(1,first,999,new NBTTagCompound()));
        check(next!=first && ClientProductionState.response()==null,"reopened GUI rejects late response even when window id is reused");
        ClientProductionState.end(first);
        ClientProductionState.receive(active,new PacketProductionSnapshot(1,next,1,new NBTTagCompound()));
        check(ClientProductionState.response()!=null,"old screen cleanup cannot close a newer screen session");
        ClientProductionState.end(next);
        check(ClientProductionState.response()==null,"closing screen clears its response mailbox");
        ClientProductionState.ready(active,false);
    }
    private static void layout(Minecraft mc,int width,int height)throws Exception{
        ContainerSharedTerminal container=new ContainerSharedTerminal(mc.thePlayer.inventory,null);
        GuiProductionLines gui=new GuiProductionLines(null,container);gui.mc=mc;gui.width=width;gui.height=height;
        field(GuiScreen.class,"fontRendererObj").set(gui,mc.fontRenderer);gui.initGui();
        @SuppressWarnings("unchecked")List<GuiButton> buttons=(List<GuiButton>)field(GuiScreen.class,"buttonList").get(gui);
        int left=(Integer)get(gui,"left"),top=(Integer)get(gui,"top"),panelWidth=(Integer)get(gui,"panelWidth"),panelHeight=(Integer)get(gui,"panelHeight");
        for(GuiButton b:buttons)check(b.xPosition>=left && b.yPosition>=top && b.xPosition+b.width<=left+panelWidth && b.yPosition+b.height<=top+panelHeight,
            "production controls fit panel at "+width+"x"+height);
        for(int i=0;i<buttons.size();i++)for(int j=i+1;j<buttons.size();j++){
            GuiButton a=buttons.get(i),b=buttons.get(j);
            check(a.xPosition+a.width<=b.xPosition || b.xPosition+b.width<=a.xPosition || a.yPosition+a.height<=b.yPosition || b.yPosition+b.height<=a.yPosition,
                "production control hit regions never overlap");
        }
        NBTTagCompound data=new NBTTagCompound();data.setBoolean("canEdit",false);data.setInteger("linePages",1);data.setInteger("sourcePages",1);
        NBTTagList lines=new NBTTagList();NBTTagCompound line=new NBTTagCompound();line.setString("id","line-a");line.setString("name","铜箔线");line.setInteger("sourceCount",1);lines.appendTag(line);data.setTag("lines",lines);
        NBTTagList sources=new NBTTagList();SupplySource source=new SupplySource(SupplySource.IO,0,1,64,0,3,"");NBTTagCompound row=source.write();row.setString("id",source.id());sources.appendTag(row);data.setTag("sources",sources);
        set(gui,"data",data);call(gui,"updateButtons");
        check(!button(buttons,1).enabled && !button(buttons,2).enabled && !button(buttons,10).enabled,"read-only server snapshot disables every mutation entry point");
        gui.mouseClicked(left+10,top+53,0);
        check(get(gui,"lineId").equals("line-a") && !(Boolean)get(gui,"allSources"),"line click selects UUID and filters device list");
        int listWidth=(Integer)get(gui,"listWidth");gui.mouseClicked(left+listWidth+8,top+103,0);
        check(get(gui,"sourceId").equals(source.id()),"device selection uses stable source identity");
        data.setBoolean("canEdit",true);row.setBoolean("autoBound",true);call(gui,"updateButtons");
        check(!button(buttons,11).enabled && button(buttons,10).enabled,"automatic bus directs unbinding to interface while allowing manual override");
        row.setBoolean("autoBound",false);data.setBoolean("canEdit",false);call(gui,"updateButtons");
        gui.mouseClicked(left+listWidth+8,top+103,0);
        check(get(gui,"sourceId").equals(""),"clicking selected device restores group totals");
        data.setBoolean("canEdit",true);call(gui,"updateButtons");gui.actionPerformed(button(buttons,1));
        check((Integer)get(gui,"editMode")==1,"admin create opens modal editor");
        gui.keyTyped('\0',1);check((Integer)get(gui,"editMode")==0,"escape dismisses modal without closing parent window");
        call(gui,"updateButtons");gui.actionPerformed(button(buttons,3));
        check((Integer)get(gui,"editMode")==3 && get(gui,"editor")==null,"delete asks about the selected grouping without an unrelated text field");
        gui.keyTyped('\0',1);call(gui,"updateButtons");
        gui.actionPerformed(button(buttons,8));check((Integer)get(gui,"minutes")==60,"window cycles to sixty minutes");
        gui.actionPerformed(button(buttons,8));check((Integer)get(gui,"minutes")==1,"window cycles to one minute");
        check(!gui.doesGuiPauseGame(),"production monitor does not pause integrated server");
        gui.onGuiClosed();
    }
}
