package com.futa_gtnh.supply;

import java.lang.reflect.Field;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import com.futa_gtnh.network.PacketPublicMeRequest;
import com.futa_gtnh.network.PacketPublicMeSnapshot;
import com.futa_gtnh.shared.ItemKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class PublicMeProtocolRegression {
    private static int checks;
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;}
    private static PacketPublicMeRequest copy(PacketPublicMeRequest request){ByteBuf buf=Unpooled.buffer();request.toBytes(buf);PacketPublicMeRequest result=new PacketPublicMeRequest();result.fromBytes(buf);buf.release();return result;}
    public static int run()throws Exception{
        Field valid=PacketPublicMeRequest.class.getDeclaredField("valid");valid.setAccessible(true);
        PacketPublicMeRequest request=new PacketPublicMeRequest();request.window=5;request.session=1200;request.sequence=8;
        request.operation=PacketPublicMeRequest.PLAN;request.amount=1_000_000_000L;request.catalogPage=2;request.planPage=3;
        request.key=SupplyTracker.writeKey(ItemKey.of(new ItemStack(Items.iron_ingot)));request.search="钢";
        PacketPublicMeRequest decoded=copy(request);
        check(valid.getBoolean(decoded) && decoded.window==5 && decoded.session==1200 && decoded.sequence==8
            && decoded.amount==1_000_000_000L && decoded.planPage==3 && decoded.search.equals("钢"),"AE plan request round trip preserves exact output and amount");
        request.amount=1_000_000_001L;check(!valid.getBoolean(copy(request)),"oversized AE crafting amount rejected before planner allocation");
        request.operation=PacketPublicMeRequest.CONFIRM;request.planId="";check(!valid.getBoolean(copy(request)),"confirmation without a viewed plan id rejected");
        request.planId="stable-plan-id";check(valid.getBoolean(copy(request)),"explicit confirmation carries viewed plan identity");
        request.operation=4;check(!valid.getBoolean(copy(request)),"unknown crafting operation rejected");
        ByteBuf buf=Unpooled.buffer().writeZero(8193);decoded=new PacketPublicMeRequest();decoded.fromBytes(buf);buf.release();
        check(!valid.getBoolean(decoded),"large AE request rejected before parsing NBT");
        buf=Unpooled.buffer().writeZero(33);decoded=new PacketPublicMeRequest();decoded.fromBytes(buf);buf.release();
        check(!valid.getBoolean(decoded),"truncated AE payload fails closed");
        NBTTagCompound data=new NBTTagCompound();data.setLong("bytes",Long.MAX_VALUE);
        PacketPublicMeSnapshot response=new PacketPublicMeSnapshot(5,1200,8,data);
        buf=Unpooled.buffer();response.toBytes(buf);PacketPublicMeSnapshot result=new PacketPublicMeSnapshot();result.fromBytes(buf);buf.release();
        check(result.session==1200 && result.sequence==8 && result.data.getLong("bytes")==Long.MAX_VALUE,"AE preview response preserves long CPU byte count and session");
        System.out.println("Public ME protocol: "+checks+" checks passed");return checks;
    }
}
