package com.futa_gtnh.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.PacketPublicMeSnapshot;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;

/** Real layout and preview gating using the existing headless GUI fixtures. */
public final class PublicMeGuiRegression {
    private static int checks;
    public static int run(Minecraft mc)throws Exception{
        sessions();for(int[] size:new int[][]{{320,240},{854,480}})layout(mc,size[0],size[1]);
        System.out.println("Public ME GUI: "+checks+" checks passed (layout, amount validation, explicit preview confirmation and sessions)");return checks;
    }
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object get(Object target,String name)throws Exception{return field(GuiPublicMeCrafting.class,name).get(target);}
    private static void set(Object target,String name,Object value)throws Exception{field(GuiPublicMeCrafting.class,name).set(target,value);}
    private static void buttons(GuiPublicMeCrafting target)throws Exception{Method m=GuiPublicMeCrafting.class.getDeclaredMethod("updateButtons");m.setAccessible(true);m.invoke(target);}
    private static GuiButton button(List<GuiButton> list,int id){for(GuiButton b:list)if(b.id==id)return b;throw new AssertionError(id);}
    private static INetHandler connection(){return (INetHandler)Proxy.newProxyInstance(PublicMeGuiRegression.class.getClassLoader(),new Class<?>[]{INetHandler.class},(p,m,a)->null);}
    private static void sessions(){
        INetHandler active=connection(),stale=connection();ClientPublicMeState.ready(active,true);long session=ClientPublicMeState.begin(active);
        ClientPublicMeState.receive(stale,new PacketPublicMeSnapshot(1,session,1,new NBTTagCompound()));
        check(ClientPublicMeState.response()==null,"foreign connection cannot install AE preview");
        ClientPublicMeState.receive(active,new PacketPublicMeSnapshot(1,session,2,new NBTTagCompound()));
        ClientPublicMeState.receive(active,new PacketPublicMeSnapshot(1,session,1,new NBTTagCompound()));
        check(ClientPublicMeState.response().sequence==2,"old AE preview cannot replace latest response");
        long next=ClientPublicMeState.begin(active);ClientPublicMeState.receive(active,new PacketPublicMeSnapshot(1,session,999,new NBTTagCompound()));
        check(ClientPublicMeState.response()==null && next!=session,"reopened AE screen rejects old session even with higher packet sequence");
        ClientPublicMeState.end(next);ClientPublicMeState.ready(active,false);
    }
    private static void layout(Minecraft mc,int width,int height)throws Exception{
        ContainerSharedTerminal container=new ContainerSharedTerminal(mc.thePlayer.inventory,null);
        GuiPublicMeCrafting gui=new GuiPublicMeCrafting(container);gui.mc=mc;gui.width=width;gui.height=height;
        field(GuiScreen.class,"fontRendererObj").set(gui,mc.fontRenderer);gui.initGui();
        @SuppressWarnings("unchecked")List<GuiButton> list=(List<GuiButton>)field(GuiScreen.class,"buttonList").get(gui);
        int left=(Integer)get(gui,"left"),top=(Integer)get(gui,"top"),panelWidth=(Integer)get(gui,"panelWidth"),panelHeight=(Integer)get(gui,"panelHeight");
        for(GuiButton b:list)check(b.xPosition>=left && b.yPosition>=top && b.xPosition+b.width<=left+panelWidth && b.yPosition+b.height<=top+panelHeight,"crafting controls fit "+width+"x"+height);
        for(int i=0;i<list.size();i++)for(int j=i+1;j<list.size();j++){
            GuiButton a=list.get(i),b=list.get(j);check(a.xPosition+a.width<=b.xPosition || b.xPosition+b.width<=a.xPosition || a.yPosition+a.height<=b.yPosition || b.yPosition+b.height<=a.yPosition,"crafting controls never overlap");
        }
        NBTTagCompound data=new NBTTagCompound();data.setBoolean("online",true);data.setBoolean("canCraft",true);
        NBTTagCompound output=SupplyTracker.writeKey(ItemKey.of(new ItemStack(Items.iron_ingot)));
        NBTTagList catalog=new NBTTagList();catalog.appendTag(output);data.setTag("catalog",catalog);
        NBTTagCompound job=new NBTTagCompound();job.setInteger("state",2);job.setString("planId","plan-a");job.setLong("amount",1);job.setTag("output",output);data.setTag("job",job);
        set(gui,"data",data);buttons(gui);
        check(!button(list,5).enabled,"ready plan alone cannot submit before selecting matching output");
        gui.mouseClicked(left+10,top+54,0);buttons(gui);
        check(get(gui,"target")!=null && button(list,2).enabled && button(list,5).enabled,"selection enables plan and explicit confirm for the matching preview");
        FutaSearchField amount=(FutaSearchField)get(gui,"amount");amount.setText("2",true);buttons(gui);
        check(!button(list,5).enabled && button(list,2).enabled,"changing amount invalidates old confirmation without submitting a new job");
        amount.setText("1000000001",true);buttons(gui);check(!button(list,2).enabled,"GUI refuses out-of-range amount");
        amount.setText("1",true);job.setInteger("state",3);buttons(gui);check(!button(list,5).enabled,"missing-material simulation cannot be confirmed");
        job.setInteger("state",4);buttons(gui);check(!button(list,2).enabled && button(list,6).enabled,"running native job offers cancel and blocks replacing its plan");
        job.setInteger("state",2);data.setBoolean("canCraft",false);buttons(gui);check(!button(list,2).enabled && !button(list,5).enabled,"revoked network crafting permission disables plan and submit");
        check(!gui.doesGuiPauseGame(),"crafting screen leaves integrated server running");gui.onGuiClosed();
    }
}
