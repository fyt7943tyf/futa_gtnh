package com.futa_gtnh.disassembler;

import java.io.File;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.util.GTModHandler;

public final class DisassemblerRegistration {

    private static MTELVDisassembler prototype;
    private static File report;

    private DisassemblerRegistration() {}

    public static void register(File configDirectory) {
        if (!Config.enableDisassembler) return;
        int id = Config.disassemblerMetaTileId;
        if (id < 1 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] != null)
            throw new IllegalStateException(
                "LV disassembler MTE ID conflict: " + id
                    + "; change disassemblerMetaTileId in futa_gtnh.cfg before starting the world");
        prototype = new MTELVDisassembler(id);
        report = new File(configDirectory, "futa_gtnh-disassembler-report.txt");
    }

    public static ItemStack stack() {
        return prototype == null ? null : prototype.getStackForm(1);
    }

    public static void addCraftingRecipe() {
        if (prototype == null || !Config.enableRecipe) return;
        GTModHandler.addCraftingRecipe(
            stack(),
            new Object[] { "PMP", "CHC", "PEI", 'P', "plateSteel", 'M', ItemList.Electric_Motor_LV.get(1), 'C',
                "cableGt01Tin", 'H', ItemList.Hull_LV.get(1), 'E', "circuitBasic", 'I',
                ItemList.Electric_Piston_LV.get(1) });
    }

    public static void loadComplete() {
        if (prototype == null) return;
        if (Loader.isModLoaded("sciencenotleisure")) {
            // GTNL loads Shimmer on its first tick, after all load-complete handlers.
            FMLCommonHandler.instance()
                .bus()
                .register(new AfterShimmer());
        } else DisassemblyCatalog.build(report);
    }

    public static final class AfterShimmer {

        private boolean built;

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void client(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) build();
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void server(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) build();
        }

        private void build() {
            if (built) return;
            built = true;
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            DisassemblyCatalog.build(report);
        }
    }
}
