package com.futa_gtnh.cosmetic.client;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.Session;

import com.futa_gtnh.network.PacketCosmeticArmorIdentity;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lain.mods.cos.CosmeticArmorReworked;
import lain.mods.cos.client.InventoryManagerClient;
import lain.mods.cos.inventory.InventoryCosArmor;
import lain.mods.cos.network.packet.PacketSyncCosArmor;

/** Tests real transformed CAR classes, persisted flags and original packet codecs. */
public final class CosmeticArmorRegression {

    private static final UUID LOCAL = UUID.fromString("e6c9859f-e557-4856-a866-52314305cf56");
    private static final UUID SERVER = UUID.fromString("2573d9c8-8ad3-3601-80a8-c255c7fbf837");
    private static final UUID OTHER = UUID.fromString("14be3067-dac8-46b3-86ca-502559ec4686");

    public static void main(String[] args) throws Exception {
        Launch.blackboard.put("fml.deobfuscatedEnvironment", true);
        Field home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        home.set(null, new File("build/cosmetic-check"));
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field namedMods = Loader.class.getDeclaredField("namedMods");
        namedMods.setAccessible(true);
        namedMods.set(Loader.instance(), new HashMap<>());
        Field side = cpw.mods.fml.relauncher.FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.CLIENT);
        Bootstrap.func_151354_b();

        // Exercise the actual FML event callbacks and packet receiver, including early login
        // when the player's entity has not been created. No native display is constructed.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        singleton.set(null, minecraft);
        Field session = Minecraft.class.getDeclaredField("session");
        session.setAccessible(true);
        session.set(minecraft, new Session("CosmeticCheck", LOCAL.toString(), "test-token", "mojang"));
        NetworkManager first = connection();
        CosmeticArmorClient events = new CosmeticArmorClient();
        events.connected(new ClientConnectedToServerEvent(first, "FML"));
        InventoryManagerClient manager = new InventoryManagerClient();
        CosmeticArmorReworked.invMan = manager;
        InventoryCosArmor defaultLocal = manager.getCosArmorInventoryClient(LOCAL);
        check(defaultLocal != manager.getCosArmorInventoryClient(SERVER), "reproduce mismatched UUID caches before identity");

        // Login sync may precede our identity packet. Reuse its authoritative instance, not a copy
        // of the already-created local defaults. Include a real cosmetic item with custom NBT.
        ItemStack helmet = new ItemStack(Items.diamond_helmet, 1, 7);
        NBTTagCompound custom = new NBTTagCompound();
        custom.setString("owner", "cosmetic-check");
        helmet.setTagCompound(custom);
        sync(SERVER, 3, true, helmet);
        sync(SERVER, 2, false, null);
        sync(SERVER, 1, true, null);
        sync(SERVER, 0, true, null);
        InventoryCosArmor authoritative = manager.getCosArmorInventoryClient(SERVER);
        CosmeticArmorClient.receiveIdentity(first.getNetHandler(), SERVER);
        check(manager.getCosArmorInventoryClient(LOCAL) == authoritative, "production Mixin aliases GUI and renderer lookups");
        check(authoritative.isSkinArmor(0) && authoritative.isSkinArmor(1) && !authoritative.isSkinArmor(2)
            && authoritative.isSkinArmor(3), "all four saved flags restored including empty slots");
        check(ItemStack.areItemStacksEqual(helmet, authoritative.getStackInSlot(3)), "cosmetic item, damage and NBT retained");

        InventoryCosArmor other = manager.getCosArmorInventoryClient(OTHER);
        sync(OTHER, 3, false, null);
        check(other != authoritative && !other.isSkinArmor(3), "other player keeps independent data");
        sync(SERVER, 2, true, null);
        check(manager.getCosArmorInventoryClient(LOCAL).isSkinArmor(2), "later native sync updates local view");
        manager.getCosArmorInventoryClient(LOCAL).setSkinArmor(1, false);
        check(!manager.getCosArmorInventoryClient(SERVER).isSkinArmor(1), "local GUI changes share the same cache");

        NBTTagCompound persisted = new NBTTagCompound();
        authoritative.writeToNBT(persisted);
        InventoryCosArmor restored = new InventoryCosArmor();
        restored.readFromNBT(persisted);
        check(restored.isSkinArmor(2) && !restored.isSkinArmor(1), "unchanged CAR NBT round trip");

        events.disconnected(new ClientDisconnectionFromServerEvent(first));
        NetworkManager next = connection();
        events.connected(new ClientConnectedToServerEvent(next, "FML"));
        manager = new InventoryManagerClient();
        CosmeticArmorReworked.invMan = manager;
        check(manager.getCosArmorInventoryClient(LOCAL) != manager.getCosArmorInventoryClient(SERVER), "connection reset clears alias");
        CosmeticArmorClient.receiveIdentity(first.getNetHandler(), SERVER);
        check(CosmeticArmorClient.resolve(LOCAL).equals(LOCAL), "stale packet cannot bind a new connection");
        CosmeticArmorClient.receiveIdentity(next.getNetHandler(), SERVER);
        sync(SERVER, 0, restored.isSkinArmor(0), null);
        check(manager.getCosArmorInventoryClient(LOCAL).isSkinArmor(0), "relogin with identity before native sync");
        events.disconnected(new ClientDisconnectionFromServerEvent(first));
        check(CosmeticArmorClient.resolve(LOCAL).equals(SERVER), "late old disconnect cannot clear current mapping");

        events.connected(new ClientConnectedToServerEvent(connection(), "FML"));
        check(CosmeticArmorClient.resolve(LOCAL).equals(LOCAL), "joining an unpatched server cannot reuse previous alias");
        events.connected(new ClientConnectedToServerEvent(next, "FML"));
        CosmeticArmorClient.receiveIdentity(next.getNetHandler(), OTHER);
        check(CosmeticArmorClient.resolve(LOCAL).equals(OTHER), "arbitrary server-assigned UUID supported without guessing offline ID");
        CosmeticArmorClient.receiveIdentity(next.getNetHandler(), LOCAL);
        check(CosmeticArmorClient.resolve(LOCAL).equals(LOCAL), "online-mode matching UUID is unchanged");

        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketCosmeticArmorIdentity(SERVER).toBytes(bytes);
            check(bytes.readableBytes() == 16, "identity wire payload only contains actual UUID");
            PacketCosmeticArmorIdentity decoded = new PacketCosmeticArmorIdentity();
            decoded.fromBytes(bytes);
            decoded.toBytes(bytes.clear());
            check(new UUID(bytes.readLong(), bytes.readLong()).equals(SERVER), "identity packet wire round trip");
        } finally {
            bytes.release();
        }
        session.set(minecraft, new Session("CosmeticCheck", "", "test-token", "legacy"));
        UUID offlineLocal = UUID.nameUUIDFromBytes("OfflinePlayer:CosmeticCheck".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        events.connected(new ClientConnectedToServerEvent(next, "FML"));
        CosmeticArmorClient.receiveIdentity(next.getNetHandler(), SERVER);
        check(CosmeticArmorClient.resolve(offlineLocal).equals(SERVER), "offline launcher with no profile ID uses EntityPlayer's UUID fallback");
        System.out.println("Cosmetic armor: real client Mixin, login ordering, NBT/items, remote players, relogin and connection isolation passed.");
    }

    private static void sync(UUID id, int slot, boolean hidden, ItemStack stack) throws Exception {
        ByteBuf bytes = Unpooled.buffer();
        try {
            PacketBuffer wire = new PacketBuffer(bytes);
            wire.writeLong(id.getMostSignificantBits());
            wire.writeLong(id.getLeastSignificantBits());
            wire.writeByte(slot);
            wire.writeBoolean(hidden);
            wire.writeItemStackToBuffer(stack);
            PacketSyncCosArmor packet = new PacketSyncCosArmor();
            packet.readFromBuffer(bytes);
            packet.handlePacketClient();
        } finally {
            bytes.release();
        }
    }

    private static NetworkManager connection() {
        NetworkManager manager = new NetworkManager(true);
        manager.setNetHandler(new NetHandlerPlayClient(null, null, manager));
        return manager;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
