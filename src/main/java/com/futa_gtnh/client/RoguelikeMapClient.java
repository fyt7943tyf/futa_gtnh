package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 地牢地图客户端总控：快捷键、扫描生命周期和小地图开关都从这里进入。 */
public final class RoguelikeMapClient {

    private static final RoguelikeMapState STATE = new RoguelikeMapState();
    private static final RoguelikeMapScanner SCANNER = new RoguelikeMapScanner();
    private static boolean miniMapEnabled;
    private static boolean registered;

    private RoguelikeMapClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        FMLCommonHandler.instance()
            .bus()
            .register(new TickListener());
        MinecraftForge.EVENT_BUS.register(new RoguelikeMapOverlay());
    }

    public static RoguelikeMapState state() {
        return STATE;
    }

    public static boolean isMiniMapEnabled() {
        return miniMapEnabled;
    }

    public static boolean isAvailable() {
        return Config.enableRoguelikeMap && (Loader.isModLoaded("Roguelike") || Loader.isModLoaded("roguelike"));
    }

    public static void toggleFullScreen() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) return;
        if (!isAvailable()) {
            notifyPlayer("未检测到 Roguelike Dungeons，地牢地图未启用");
            return;
        }

        if (minecraft.currentScreen instanceof GuiRoguelikeMap) {
            minecraft.displayGuiScreen(null);
        } else if (minecraft.currentScreen == null) {
            minecraft.displayGuiScreen(new GuiRoguelikeMap());
        }
    }

    public static void toggleMiniMap() {
        if (!isAvailable()) {
            notifyPlayer("未检测到 Roguelike Dungeons，地牢地图未启用");
            return;
        }
        miniMapEnabled = !miniMapEnabled;
        if (miniMapEnabled) {
            notifyPlayer("地牢悬浮小地图：已开启");
        } else {
            notifyPlayer("地牢悬浮小地图：已关闭");
        }
    }

    public static void resetScan() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) return;
        STATE.reset(
            minecraft.theWorld.provider.dimensionId,
            (int) Math.floor(minecraft.thePlayer.posX),
            (int) Math.floor(minecraft.thePlayer.posZ));
        SCANNER.clear();
    }

    private static void notifyPlayer(String message) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) minecraft.thePlayer.addChatMessage(new ChatComponentText(message));
    }

    public static final class TickListener {

        private World lastWorld;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft minecraft = Minecraft.getMinecraft();
            World world = minecraft.theWorld;
            EntityPlayer player = minecraft.thePlayer;
            if (world == null || player == null || !isAvailable()) {
                lastWorld = null;
                return;
            }

            if (world != lastWorld) {
                STATE.reset(world.provider.dimensionId, (int) Math.floor(player.posX), (int) Math.floor(player.posZ));
                SCANNER.clear();
                lastWorld = world;
            }

            // 没有打开地图时也保留扫描，但只在小地图或全屏地图真正使用时开始，
            // 避免普通探险状态白白读取大量区块方块。
            if (miniMapEnabled || minecraft.currentScreen instanceof GuiRoguelikeMap) {
                try {
                    SCANNER.tick(STATE, world, player);
                } catch (Throwable throwable) {
                    FutaGtnhMod.LOG.warn("Roguelike 地牢地图扫描失败，本次扫描已跳过", throwable);
                }
            }
        }
    }
}
