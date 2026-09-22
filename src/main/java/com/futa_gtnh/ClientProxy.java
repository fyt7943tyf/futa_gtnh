package com.futa_gtnh;

import com.futa_gtnh.client.KeyHandler;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 客户端专属逻辑。继承 {@link CommonProxy}，只在需要覆写客户端行为时才写方法。
 * 注意：如果覆写了父类方法，记得按需调用 {@code super.xxx(event)}。
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        KeyHandler.register();
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }
}
