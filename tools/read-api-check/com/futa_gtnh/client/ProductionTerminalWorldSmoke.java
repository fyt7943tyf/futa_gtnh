package com.futa_gtnh.client;

import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.inventory.ContainerProductionTerminal;
import com.futa_gtnh.me.PublicMeWorldSmoke;
import com.futa_gtnh.supply.SupplyTracker;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Real client item use -> C2S -> FML window -> query -> row click -> saved binding, in the named smoke save only. */
public final class ProductionTerminalWorldSmoke {
    private static volatile boolean complete;
    private static volatile String failure;
    private volatile int stage;
    private volatile int x,y,z;
    private volatile String lineId;
    private int ticks,clientSent=-1;
    private ItemStack previous;
    private EntityPlayerMP player;
    private WorldServer world;
    public static void register(){complete=false;failure=null;FMLCommonHandler.instance().bus().register(new ProductionTerminalWorldSmoke());}
    public static boolean isComplete(){return complete;}
    public static String failure(){return failure;}
    @SubscribeEvent
    public void server(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END || complete || failure!=null || !PublicMeWorldSmoke.isComplete())return;
        MinecraftServer server=MinecraftServer.getServer();if(server==null)return;
        WorldServer candidate=server.worldServerForDimension(0);if(candidate==null || candidate.playerEntities.isEmpty())return;
        if(!Boolean.getBoolean("futa.uiSmoke") || !PublicMeWorldSmoke.saveName().equals(candidate.getSaveHandler().getWorldDirectory().getName())
            || !PublicMeWorldSmoke.worldName().equals(candidate.getWorldInfo().getWorldName())){failure="PRODUCTION_TERMINAL_SMOKE_FAILED wrong save";return;}
        try {
            if(++ticks>900)throw new AssertionError("timeout at stage "+stage+", client action "+clientSent+", server window "+player.openContainer.getClass().getSimpleName());
            if(stage==0){
                world=candidate;player=(EntityPlayerMP)world.playerEntities.get(0);
                if(!com.futa_gtnh.network.SupplyClients.supports(player.getUniqueID(),player.playerNetServerHandler))return;
                server.getConfigurationManager().setCommandsAllowedForAll(true);
                require(player.canCommandSenderUseCommand(4,"futalines"),"singleplayer admin permission");
                x=MathHelper.floor_double(player.posX)+2;y=MathHelper.floor_double(player.posY);z=MathHelper.floor_double(player.posZ);
                require(world.isAirBlock(x,y,z),"fixture position is empty");
                require(world.setBlock(x,y,z,CommonProxy.blockSharedTerminal,0,3),"place real shared terminal IO");
                previous=player.getCurrentEquippedItem();player.inventory.setInventorySlotContents(player.inventory.currentItem,new ItemStack(CommonProxy.productionTerminal));
                player.inventoryContainer.detectAndSendChanges();
                NBTTagCompound create=new NBTTagCompound();create.setString("action","create");create.setString("name","终端烟测-"+UUID.randomUUID());
                NBTTagCompound result=SupplyTracker.productionMutation(player,create);require(result.getBoolean("ok"),"create test production line");lineId=result.getString("lineId");
                stage=1;
            }else if(stage==1 && player.openContainer instanceof ContainerProductionTerminal){
                ContainerProductionTerminal c=(ContainerProductionTerminal)player.openContainer;
                require(c.bindingMode && c.target!=null && c.target.source.x==x && c.target.source.side==2,"server pins clicked physical face");stage=2;
            }else if(stage==2){
                ContainerProductionTerminal c=(ContainerProductionTerminal)player.openContainer;
                if(!lineId.equals(SupplyTracker.productionBindingSnapshot(c.target,10,0).getCompoundTag("currentBinding").getString("lineId")))return;
                stage=3;
            }else if(stage==3 && player.openContainer==player.inventoryContainer){stage=4;
            }else if(stage==4 && player.openContainer instanceof ContainerProductionTerminal){
                require(!((ContainerProductionTerminal)player.openContainer).bindingMode,"air use opens full manager");stage=5;
            }else if(stage==5 && player.openContainer==player.inventoryContainer){
                NBTTagCompound delete=new NBTTagCompound();delete.setString("action","delete");delete.setString("lineId",lineId);
                require(SupplyTracker.productionMutation(player,delete).getBoolean("ok"),"clean smoke metadata");
                player.inventory.setInventorySlotContents(player.inventory.currentItem,previous);player.inventoryContainer.detectAndSendChanges();
                world.setBlockToAir(x,y,z);complete=true;
                System.out.println("PRODUCTION_TERMINAL_SMOKE_SUCCESS real item click, physical face, GUI query, row binding, close and air manager");
            }
        }catch(Throwable error){failure="PRODUCTION_TERMINAL_SMOKE_FAILED "+error;error.printStackTrace();}
    }
    @SubscribeEvent
    public void client(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END || complete || failure!=null || clientSent==stage)return;
        Minecraft mc=Minecraft.getMinecraft();if(mc.thePlayer==null || mc.theWorld==null || !ClientProductionState.terminalSupported(mc.getNetHandler()))return;
        try{
            ItemStack held=mc.thePlayer.getCurrentEquippedItem();
            if(stage==1){
                if(held==null || held.getItem()!=CommonProxy.productionTerminal || mc.theWorld.getTileEntity(x,y,z)==null)return;
                net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(),true);
                mc.thePlayer.movementInput.sneak=true;
                mc.thePlayer.setSneaking(true);mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer,1));
                require(mc.thePlayer.isSneaking(),"native client movement reports sneak");
                require(mc.playerController.onPlayerRightClick(mc.thePlayer,mc.theWorld,held,x,y,z,2,Vec3.createVectorHelper(x+.5,y+.5,z)),"client consumes physical IO click");clientSent=1;
            }else if(stage==2 && mc.currentScreen instanceof GuiProductionBinding){
                GuiProductionBinding gui=(GuiProductionBinding)mc.currentScreen;NBTTagCompound data=(NBTTagCompound)get(gui,"data");
                if(!data.getBoolean("canEdit"))return;
                NBTTagList lines=data.getTagList("lines",10);int row=-1;
                for(int i=0;i<lines.tagCount();i++)if(lineId.equals(lines.getCompoundTagAt(i).getString("id")))row=i;
                require(row>=0 && row<(Integer)get(gui,"rows"),"test line is visible");
                gui.mouseClicked((Integer)get(gui,"left")+16,(Integer)get(gui,"top")+84+row*22+10,0);clientSent=2;
            }else if(stage==3 && mc.currentScreen instanceof GuiProductionBinding){
                ((GuiProductionBinding)mc.currentScreen).keyTyped('\0',1);
                net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(),false);
                mc.thePlayer.movementInput.sneak=false;
                mc.thePlayer.setSneaking(false);mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer,2));clientSent=3;
            }else if(stage==4){
                // Give the server's open-window rate limit ample time after the previous close.
                if(ticks%20!=0)return;
                CommonProxy.productionTerminal.onItemRightClick(held,mc.theWorld,mc.thePlayer);clientSent=4;
            }else if(stage==5 && mc.currentScreen instanceof GuiProductionLines){
                NBTTagCompound data=(NBTTagCompound)get(mc.currentScreen,"data");if(!data.getBoolean("canEdit"))return;
                ((GuiProductionLines)mc.currentScreen).keyTyped('\0',1);clientSent=5;
            }
        }catch(Throwable error){failure="PRODUCTION_TERMINAL_SMOKE_FAILED client "+error;error.printStackTrace();}
    }
    private static Object get(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
