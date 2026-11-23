package com.futa_gtnh.bees;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.Loader;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import li.cil.oc.api.Driver;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.driver.SidedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.network.Visibility;

/** Fixed machine components, also visible through a PH wireless peripheral station. */
public final class BeeOcDrivers {

    private BeeOcDrivers() {}

    public static void register() {
        if (Loader.isModLoaded("gendustry") && Loader.isModLoaded("Forestry")) {
            try {
                MutatronAccess access = new MutatronAccess();
                Driver.add(new MachineDriver(access));
            } catch (ReflectiveOperationException | LinkageError e) {
                FutaGtnhMod.LOG.warn("OC Mutatron integration unavailable", e);
            }
        }
        if (Loader.isModLoaded("TwistSpaceTechnology")) {
            Driver.add(new MachineDriver(null));
        }
    }

    private static final class MachineDriver implements SidedBlock {

        private final MutatronAccess access;

        private MachineDriver(MutatronAccess access) {
            this.access = access;
        }

        @Override
        public boolean worksWith(World world, int x, int y, int z, ForgeDirection side) {
            TileEntity tile = world.getTileEntity(x, y, z);
            if (access != null) return access.isAdvanced(tile);
            if (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileEntity() == null) return false;
            return gt.getMetaTileEntity()
                .getClass()
                .getName()
                .equals("com.Nxer.TwistSpaceTechnology.common.machine.TST_BeeEngineer");
        }

        @Override
        public ManagedEnvironment createEnvironment(World world, int x, int y, int z, ForgeDirection side) {
            if (!worksWith(world, x, y, z, side)) return null;
            TileEntity tile = world.getTileEntity(x, y, z);
            return access != null ? new Mutatron(tile, access) : new Engineer(tile);
        }
    }

    public abstract static class Machine extends li.cil.oc.api.prefab.ManagedEnvironment implements NamedBlock {

        private final TileEntity tile;
        private final String name;

        Machine(TileEntity tile, String name) {
            this.tile = tile;
            this.name = name;
            setNode(
                Network.newNode(this, Visibility.Network)
                    .withComponent(name, Visibility.Network)
                    .create());
        }

        final TileEntity liveTile() {
            World world = tile.getWorldObj();
            if (tile.isInvalid() || world == null
                || world.isRemote
                || !world.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
                || world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) != tile) {
                throw new IllegalStateException("Bee machine is no longer attached");
            }
            return tile;
        }

        @Override
        public String preferredName() {
            return name;
        }

        @Override
        public int priority() {
            return 10;
        }

        // OC's compound adapter dispatches callbacks by the concrete environment class.
        final Object[] position() {
            TileEntity live = liveTile();
            return new Object[] { live.xCoord, live.yCoord, live.zCoord, live.getWorldObj().provider.dimensionId };
        }
    }

    public static final class Mutatron extends Machine {

        private final MutatronAccess access;

        Mutatron(TileEntity tile, MutatronAccess access) {
            super(tile, "futa_mutatron");
            this.access = access;
        }

        @Callback(doc = "function():number,number,number,number -- World x, y, z and dimension")
        public Object[] beePosition(Context context, Arguments args) {
            return position();
        }

        @Callback(doc = "function():string -- Fingerprint of the active bee mutation catalog")
        public Object[] catalogVersion(Context context, Arguments args) throws ReflectiveOperationException {
            return new Object[] { access.catalog(liveTile())
                .version() };
        }

        @Callback(doc = "function(uid:string):string,string -- Catalog fingerprint and JSON recipes for the UID")
        public Object[] recipes(Context context, Arguments args) throws ReflectiveOperationException {
            var catalog = access.catalog(liveTile());
            return new Object[] { catalog.version(), catalog.recipes(args.checkString(0)) };
        }

        @Callback(doc = "function():boolean,number -- Whether an order is working and its progress")
        public Object[] beeMachineStatus(Context context, Arguments args) throws ReflectiveOperationException {
            TileEntity live = liveTile();
            return new Object[] { access.working(live), access.progress(live) };
        }

        @Callback(doc = "function():string -- JSON list of current candidate UIDs")
        public Object[] candidates(Context context, Arguments args) throws ReflectiveOperationException {
            return new Object[] { MutatronAccess.JSON.toJson(candidateSlots(liveTile()).keySet()) };
        }

        @Callback(
            doc = "function(target:string, princess:string, drone:string):boolean -- Preview a recipe at this machine")
        public Object[] eligible(Context context, Arguments args) throws ReflectiveOperationException {
            return new Object[] {
                access.eligible(liveTile(), args.checkString(0), args.checkString(1), args.checkString(2)) };
        }

        @Callback(
            doc = "function(target:string, princess:string, drone:string):boolean[,string] -- Select a current candidate")
        public Object[] selectSpecies(Context context, Arguments args) throws ReflectiveOperationException {
            String target = args.checkString(0);
            String princess = args.checkString(1);
            String drone = args.checkString(2);
            TileEntity live = liveTile();
            if (access.working(live)) return new Object[] { false, "Machine is already processing" };
            ItemStack p = access.parent(live, true);
            ItemStack d = access.parent(live, false);
            if (!princess.equals(access.primary(p)) || !drone.equals(access.primary(d))) {
                return new Object[] { false, "Parents in the machine differ from the request" };
            }
            List<Integer> matches = candidateSlots(live).get(target);
            if (matches == null || !access.hasCandidate(live, target, p, d)) {
                return new Object[] { false, "Target UID is not in the current candidate list" };
            }
            access.select(live, matches.get(0));
            return new Object[] { true };
        }

        private Map<String, List<Integer>> candidateSlots(TileEntity live) throws ReflectiveOperationException {
            Map<String, List<Integer>> result = new TreeMap<>();
            for (Map.Entry<?, ?> entry : access.candidates(live)
                .entrySet()) {
                if (entry.getKey() instanceof Integer slot && entry.getValue() instanceof ItemStack stack) {
                    String uid = access.primary(stack);
                    if (uid != null) result.computeIfAbsent(uid, key -> new ArrayList<>())
                        .add(slot);
                }
            }
            return result;
        }
    }

    public static final class Engineer extends Machine {

        Engineer(TileEntity tile) {
            super(tile, "futa_bee_engineer");
        }

        @Callback(doc = "function():number,number,number,number -- World x, y, z and dimension")
        public Object[] beePosition(Context context, Arguments args) {
            return position();
        }

        @Callback(
            doc = "function():boolean,number,number,boolean,boolean -- Running, elapsed ticks, total ticks, allowed, shutdown")
        public Object[] beeMachineStatus(Context context, Arguments args) {
            IGregTechTileEntity machine = (IGregTechTileEntity) liveTile();
            return new Object[] { machine.isActive(), machine.getProgress(), machine.getMaxProgress(),
                machine.isAllowedToWork(), machine.wasShutdown() };
        }
    }
}
