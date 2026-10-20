package com.futa_gtnh.client;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
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

    public static void register() {
        FMLCommonHandler.instance().bus().register(new UiSmokeCheck());
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
            mc.launchIntegratedServer("FutaUiSmoke", "Futa UI smoke", new WorldSettings(12345L,
                WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) return;
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
            System.out.println("UI_SMOKE_SUCCESS " + screens.size() + " screens");
            mc.shutdown(); return;
        }
        current = screens.get(index).get();
        mc.displayGuiScreen(current);
    }
}
