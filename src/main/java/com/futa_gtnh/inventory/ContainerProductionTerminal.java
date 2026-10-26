package com.futa_gtnh.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.futa_gtnh.item.ItemProductionTerminal;
import com.futa_gtnh.supply.ProductionTarget;

/** A metadata-only handheld window. Target identities are held and rechecked by the server. */
public final class ContainerProductionTerminal extends Container {

    public final boolean bindingMode;
    public final ProductionTarget target;
    private final World world;
    private long clientSession;

    public ContainerProductionTerminal(EntityPlayer player, World world, int x, int y, int z, int face,
        boolean bindingMode) {
        this.world = world;
        this.bindingMode = bindingMode;
        target = bindingMode ? ProductionTarget.atFace(world, x, y, z, face) : null;
        // Vanilla held-item reconciliation expects hotbar slots even in a metadata-only GUI.
        for (int index = 0; index < 36; index++) addSlotToContainer(new Slot(player.inventory, index, -3000, -3000));
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (player == null || player.worldObj != world) return false;
        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || !(held.getItem() instanceof ItemProductionTerminal)) return false;
        return world.isRemote || !bindingMode
            || target != null && target.matches(world)
                && player.getDistanceSq(target.source.x + 0.5D, target.source.y + 0.5D, target.source.z + 0.5D) <= 64D;
    }

    public boolean accepts(long session, boolean query) {
        if (clientSession == 0 && query) clientSession = session;
        return clientSession != 0 && clientSession == session;
    }

    /** Client coordinates/IDs cannot redirect a physical binding window to another device. */
    public NBTTagCompound constrain(NBTTagCompound mutation) {
        if (!bindingMode || mutation == null) return mutation;
        String action = mutation.getString("action");
        if ("create".equals(action)) return mutation;
        if (target == null || !("bind".equals(action) || "unbind".equals(action))) return null;
        NBTTagCompound safe = (NBTTagCompound) mutation.copy();
        safe.setTag("source", target.source.write());
        safe.setString("sourceId", target.source.id());
        return safe;
    }

    @Override
    public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        return null;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slot) {
        return null;
    }
}
