package com.futa_gtnh.disassembler;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.futa_gtnh.client.nei.DisassemblerRecipeHandler;

import cpw.mods.fml.common.Loader;
import gregtech.api.interfaces.ITexture;
import io.netty.buffer.Unpooled;
import sun.misc.Unsafe;

/** Real MUI page sync and NEI foreground, with rendering/device transport replaced by test fixtures. */
public final class DisassemblerUiRegression {

    private static int assertions;

    public static void main(String[] args) throws Exception {
        Loader.injectData(
            new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Bootstrap.func_151354_b();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        singleton.set(null, mc);
        mc.gameSettings = new GameSettings();
        RecordingTextures textures = new RecordingTextures();
        mc.renderEngine = textures;
        RecordingFont font = (RecordingFont) unsafe.allocateInstance(RecordingFont.class);
        mc.fontRenderer = font;
        pageSync();
        neiForeground(textures, font);
        neiOrdering();
        System.out.println("Disassembler UI regression: " + assertions + " assertions passed");
    }

    private static void neiOrdering() {
        java.util.Map<String, Integer> saved = new java.util.HashMap<>(codechicken.nei.NEIClientConfig.handlerOrdering);
        try {
            codechicken.nei.NEIClientConfig.handlerOrdering.clear();
            DisassemblerRecipeHandler handler = new DisassemblerRecipeHandler();
            DisassemblerRecipeHandler.applyDefaultOrder();
            check(codechicken.nei.NEIClientConfig.HANDLER_COMPARATOR.compare(new codechicken.nei.recipe.ShapedRecipeHandler(), handler) < 0,
                "ordinary crafting sorted before disassembly by real NEI comparator");
            codechicken.nei.NEIClientConfig.handlerOrdering.put(DisassemblerRecipeHandler.ID, 0);
            handler.onNeiConfigsLoaded(null);
            check(codechicken.nei.NEIClientConfig.getHandlerOrder(handler) == 10000, "generated CSV zero cannot restore first position");
            codechicken.nei.NEIClientConfig.handlerOrdering.put(DisassemblerRecipeHandler.ID, 37);
            handler.onNeiConfigsLoaded(null);
            check(codechicken.nei.NEIClientConfig.getHandlerOrder(handler) == 37, "explicit overlay ordering preserved");
            codechicken.nei.NEIClientConfig.handlerOrdering.put(DisassemblerRecipeHandler.ID, 0);
            codechicken.nei.NEIClientConfig.handlerOrdering.put(handler.getHandlerId(), 52);
            handler.onNeiConfigsLoaded(null);
            check(codechicken.nei.NEIClientConfig.getHandlerOrder(handler) == 52, "explicit class ordering preserved over generated overlay zero");
        } finally {
            codechicken.nei.NEIClientConfig.handlerOrdering.clear();
            codechicken.nei.NEIClientConfig.handlerOrdering.putAll(saved);
        }
    }

    private static MTELVDisassembler machine() throws Exception {
        Constructor<MTELVDisassembler> constructor = MTELVDisassembler.class
            .getDeclaredConstructor(String.class, String[].class, ITexture[][][].class);
        constructor.setAccessible(true);
        MTELVDisassembler machine = constructor.newInstance("ui-check", new String[0], null);
        DisassemblyBuffer buffer = new DisassemblyBuffer(50, 17, 64000);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("futaDisassembly", buffer.writeToNbt());
        machine.loadNBTData(tag);
        return machine;
    }

    private static void pageSync() throws Exception {
        PanelSyncManager server = new PanelSyncManager(new ModularSyncManager(false), true);
        PanelSyncManager client = new PanelSyncManager(new ModularSyncManager(true), true);
        ModularPanel serverPanel = machine().buildUI(null, server, new UISettings());
        ModularPanel clientPanel = machine().buildUI(null, client, new UISettings());
        List<PagedWidget<?>> serverPages = pages(serverPanel), clientPages = pages(clientPanel);
        check(serverPages.size() == 2 && clientPages.size() == 2, "item and fluid page groups built on both sides");
        check(
            !server.findSyncHandlerNullable("progress", 0)
                .isAllowC2S(),
            "progress remains server-owned");
        String[] keys = { "futa_gtnh.disassembler.items", "futa_gtnh.disassembler.fluids" };
        for (int group = 0; group < 2; group++) {
            IntSyncValue request = (IntSyncValue) client.findSyncHandlerNullable(keys[group], 0);
            IntSyncValue receiver = (IntSyncValue) server.findSyncHandlerNullable(keys[group], 0);
            check(
                request.isAllowC2S() && receiver.isAllowC2S(),
                "page selection permits client requests on both sides");
            check(
                serverPages.get(group)
                    .getPages()
                    .size() == 3,
                "real multi-page outputs built");
            for (int selected : new int[] { 1, 2, 0, 2, 1, 0 }) {
                request.setIntValue(selected, true, false);
                PacketBuffer packet = new PacketBuffer(Unpooled.buffer());
                try {
                    request.write(packet);
                    receiver.readOnServer(0, packet);
                } finally {
                    packet.release();
                }
                check(
                    clientPages.get(group)
                        .getCurrentPageIndex() == selected
                        && serverPages.get(group)
                            .getCurrentPageIndex() == selected,
                    "serialized page request applies to client and server");
                for (int i = 0; i < 3; i++) check(
                    serverPages.get(group)
                        .getPages()
                        .get(i)
                        .isEnabled() == (i == selected),
                    "only selected server page accessible");
            }
            for (int invalid : new int[] { -1, 3, Integer.MAX_VALUE }) {
                PacketBuffer packet = new PacketBuffer(Unpooled.buffer());
                try {
                    packet.writeVarIntToBuffer(invalid);
                    receiver.readOnServer(0, packet);
                } finally {
                    packet.release();
                }
                check(
                    serverPages.get(group)
                        .getCurrentPageIndex() == 0,
                    "invalid page cannot change accessible outputs");
            }
        }
    }

    private static List<PagedWidget<?>> pages(ModularPanel panel) {
        return panel.getChildren()
            .stream()
            .filter(widget -> widget instanceof PagedWidget)
            .map(widget -> (PagedWidget<?>) widget)
            .collect(Collectors.toList());
    }

    private static void neiForeground(RecordingTextures textures, RecordingFont font) throws Exception {
        DisassemblerRecipeHandler handler = new DisassemblerRecipeHandler();
        check(!handler.transferRects.isEmpty(), "machine catalyst has a recipe group entry");
        Field outputId = handler.transferRects.getFirst()
            .getClass()
            .getDeclaredField("outputId");
        outputId.setAccessible(true);
        check(
            outputId.get(handler.transferRects.getFirst())
                .equals(DisassemblerRecipeHandler.ID),
            "machine catalyst resolves recipe group");
        Method add = DisassemblerRecipeHandler.class.getDeclaredMethod("add", DisassemblyRecipe.class);
        add.setAccessible(true);
        add.invoke(
            handler,
            new DisassemblyRecipe(
                new ItemStack(Items.diamond),
                Arrays.asList(new ItemStack(Items.iron_ingot, 2)),
                Collections.<FluidStack>emptyList(),
                "ui-check"));
        handler.drawForeground(0);
        check(
            textures.bindings == 1 && font.draws == 2,
            "inherited foreground binds a decoded texture and draws recipe details");
        check(
            textures.last != null && !textures.last.getResourcePath()
                .isEmpty(),
            "foreground never binds empty minecraft resource");
    }

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        assertions++;
    }

    private static final class RecordingTextures extends TextureManager {

        int bindings;
        ResourceLocation last;

        RecordingTextures() {
            super(null);
        }

        @Override
        public void bindTexture(ResourceLocation resource) {
            check(
                !resource.getResourcePath()
                    .isEmpty(),
                "texture path is nonempty");
            String path = "/assets/" + resource.getResourceDomain() + "/" + resource.getResourcePath();
            try (InputStream stream = DisassemblerUiRegression.class.getResourceAsStream(path)) {
                check(stream != null && ImageIO.read(stream) != null, "bound resource decodes as a real image");
            } catch (Exception error) {
                throw new AssertionError(path, error);
            }
            last = resource;
            bindings++;
        }
    }

    private static final class RecordingFont extends FontRenderer {

        int draws;

        RecordingFont() {
            super(new GameSettings(), new ResourceLocation("textures/font/ascii.png"), null, false);
        }

        @Override
        public int drawString(String text, int x, int y, int color) {
            draws++;
            return 0;
        }
    }
}
