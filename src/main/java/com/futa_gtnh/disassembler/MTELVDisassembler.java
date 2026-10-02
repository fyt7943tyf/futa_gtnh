package com.futa_gtnh.disassembler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.IntStream;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.item.IItemHandlerModifiable;
import com.cleanroommc.modularui.utils.item.ItemStackHandler;
import com.cleanroommc.modularui.value.sync.FluidSlotSyncHandler;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.slot.FluidSlot;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.slot.SlotGroup;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;
import gregtech.api.render.TextureFactory;
import io.netty.buffer.ByteBuf;

/** LV-only machine with independent real output tanks and an atomic, persistent processing buffer. */
public final class MTELVDisassembler extends MTETieredMachineBlock {

    private DisassemblyBuffer buffer = new DisassemblyBuffer(
        DisassemblyCatalog.itemSlots,
        DisassemblyCatalog.fluidSlots,
        DisassemblyCatalog.tankCapacity);
    private ItemStackHandler handler;
    private int drainCursor;

    public MTELVDisassembler(int id) {
        super(
            id,
            "futa_gtnh.disassembler.lv",
            "LV Disassembler",
            1,
            0,
            new String[] { "One production step per batch; 32 EU/t, 1 A, 2 seconds",
                "Crafting, assembler and assembly line; conflicting routes are skipped",
                "Paged item outputs and independent output tanks; rear extraction" });
        rebuildHandler();
    }

    private MTELVDisassembler(String name, String[] description, ITexture[][][] textures) {
        super(name, 1, 0, description, textures);
        rebuildHandler();
    }

    private void rebuildHandler() {
        handler = new ItemStackHandler(buffer.inventory) {

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot == 0;
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return slot == 1 ? null : super.extractItem(slot, amount, simulate);
            }

            @Override
            protected void onContentsChanged(int slot) {
                MTELVDisassembler.this.markDirty();
            }
        };
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
        return new MTELVDisassembler(mName, mDescriptionArray, mTextures);
    }

    @Override
    public IItemHandlerModifiable getInventoryHandler() {
        return handler;
    }

    @Override
    public ItemStack[] getRealInventory() {
        return buffer.inventory;
    }

    @Override
    public int getSizeInventory() {
        return buffer.inventory.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return slot >= 0 && slot < getSizeInventory() ? buffer.inventory[slot] : null;
    }

    @Override
    public ItemStack getFirstStack() {
        return getStackInSlot(0);
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (slot >= 0 && slot < getSizeInventory()) handler.setStackInSlot(slot, stack);
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        return handler.extractItem(slot, amount, false);
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        return null;
    }

    @Override
    public boolean isValidSlot(int slot) {
        return slot >= 0 && slot < getSizeInventory();
    }

    @Override
    public boolean isIOSlot(int slot) {
        return isValidSlot(slot) && slot != 1;
    }

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return slot == 0;
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) {
        return allowPutStack(getBaseMetaTileEntity(), slot, ForgeDirection.getOrientation(side), stack);
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) {
        return allowPullStack(getBaseMetaTileEntity(), slot, ForgeDirection.getOrientation(side), stack);
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return ForgeDirection.getOrientation(side) == rear() ? IntStream.range(2, getSizeInventory())
            .toArray() : new int[] { 0 };
    }

    @Override
    public boolean allowPullStack(IGregTechTileEntity tile, int slot, ForgeDirection side, ItemStack stack) {
        return slot >= 2 && slot < getSizeInventory() && side == rear();
    }

    @Override
    public boolean allowPutStack(IGregTechTileEntity tile, int slot, ForgeDirection side, ItemStack stack) {
        return slot == 0 && side != rear();
    }

    @Override
    public boolean connectsToItemPipe(ForgeDirection side) {
        return true;
    }

    @Override
    public ArrayList<ItemStack> getDroppedItem() {
        return null;
    }

    private ForgeDirection rear() {
        return getBaseMetaTileEntity().getBackFacing();
    }

    @Override
    public boolean isEnetInput() {
        return true;
    }

    @Override
    public long maxEUInput() {
        return 32;
    }

    @Override
    public long maxAmperesIn() {
        return 1;
    }

    @Override
    public long maxEUStore() {
        return 32768;
    }

    @Override
    public long getMinimumStoredEU() {
        return 32;
    }

    @Override
    public boolean isInputFacing(ForgeDirection side) {
        return side != getBaseMetaTileEntity().getFrontFacing();
    }

    @Override
    public boolean isFacingValid(ForgeDirection facing) {
        return facing != ForgeDirection.UNKNOWN;
    }

    @Override
    public boolean onRightclick(IGregTechTileEntity tile, EntityPlayer player) {
        if (tile.isServerSide()) openGui(player);
        return true;
    }

    @Override
    public void onPostTick(IGregTechTileEntity tile, long tick) {
        if (!tile.isServerSide()) return;
        boolean active = false;
        if (tile.isAllowedToWork()) {
            if (!buffer.hasTask() && tick % 5 == 0 && tile.getStoredEU() >= 32)
                buffer.start(DisassemblyCatalog.find(buffer.inventory[0]));
            if (buffer.canAdvance() && tile.getStoredEU() >= 32 && tile.decreaseStoredEnergyUnits(32, false))
                active = buffer.advance(true);
        }
        tile.setActive(active);
        // Also captures container clicks on the real output tanks.
        markDirty();
    }

    @Override
    public int getProgresstime() {
        return buffer.progress;
    }

    @Override
    public int maxProgresstime() {
        return DisassemblyRecipe.DURATION;
    }

    @Override
    public boolean isLiquidInput(ForgeDirection side) {
        return false;
    }

    @Override
    public boolean isLiquidOutput(ForgeDirection side) {
        return side == rear();
    }

    @Override
    public boolean canFill(ForgeDirection side, Fluid fluid) {
        return false;
    }

    @Override
    public int fill(ForgeDirection side, FluidStack resource, boolean doFill) {
        return 0;
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        return 0;
    }

    @Override
    public FluidStack getFluid() {
        for (int offset = 0; offset < buffer.tanks.length; offset++) {
            FluidStack fluid = buffer.tanks[(drainCursor + offset) % buffer.tanks.length].getFluid();
            if (fluid != null && fluid.amount > 0) return fluid;
        }
        return null;
    }

    @Override
    public int getCapacity() {
        return buffer.tankCapacity;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection side) {
        return side != ForgeDirection.UNKNOWN && side != rear() ? new FluidTankInfo[0]
            : Arrays.stream(buffer.tanks)
                .map(tank -> tank.getInfo())
                .toArray(FluidTankInfo[]::new);
    }

    @Override
    public boolean canDrain(ForgeDirection side, Fluid fluid) {
        if (side != ForgeDirection.UNKNOWN && side != rear()) return false;
        return Arrays.stream(buffer.tanks)
            .anyMatch(
                tank -> tank.getFluid() != null && tank.getFluid()
                    .getFluid() == fluid);
    }

    @Override
    public FluidStack drain(ForgeDirection side, int amount, boolean doDrain) {
        return side == rear() || side == ForgeDirection.UNKNOWN ? drain(amount, doDrain) : null;
    }

    @Override
    public FluidStack drain(int amount, boolean doDrain) {
        for (int offset = 0; offset < buffer.tanks.length; offset++) {
            int index = (drainCursor + offset) % buffer.tanks.length;
            FluidStack result = buffer.tanks[index].drain(amount, doDrain);
            if (result == null) continue;
            if (doDrain) {
                drainCursor = (index + 1) % buffer.tanks.length;
                markDirty();
            }
            return result;
        }
        return null;
    }

    @Override
    public FluidStack drain(ForgeDirection side, FluidStack fluid, boolean doDrain) {
        return drain(side, fluid, fluid == null ? 0 : fluid.amount, doDrain);
    }

    @Override
    public FluidStack drain(ForgeDirection side, FluidStack fluid, int amount, boolean doDrain) {
        if (fluid == null || amount <= 0 || (side != rear() && side != ForgeDirection.UNKNOWN)) return null;
        FluidStack result = fluid.copy();
        result.amount = 0;
        for (int i = 0; i < buffer.tanks.length && result.amount < amount; i++) {
            FluidStack current = buffer.tanks[i].getFluid();
            if (current == null || !current.isFluidEqual(fluid)) continue;
            FluidStack drained = buffer.tanks[i].drain(amount - result.amount, doDrain);
            if (drained != null) result.amount += drained.amount;
        }
        if (doDrain && result.amount > 0) markDirty();
        return result.amount == 0 ? null : result;
    }

    @Override
    public void saveNBTData(NBTTagCompound tag) {
        tag.setTag("futaDisassembly", buffer.writeToNbt());
        tag.setInteger("drainCursor", drainCursor);
    }

    @Override
    public void loadNBTData(NBTTagCompound tag) {
        if (tag.hasKey("futaDisassembly")) buffer.readFromNbt(tag.getCompoundTag("futaDisassembly"));
        drainCursor = Math.floorMod(tag.getInteger("drainCursor"), buffer.tanks.length);
        rebuildHandler();
    }

    @Override
    public void setItemNBT(NBTTagCompound tag) {
        // Loose inventory (including escrow) is dropped by GT. Fluids travel in the machine item.
        NBTTagCompound fluids = new NBTTagCompound();
        buffer.writeFluids(fluids);
        if (Arrays.stream(buffer.tanks)
            .anyMatch(tank -> tank.getFluid() != null)) tag.setTag("futaDisassembly", fluids);
    }

    @Override
    public void writeToStream(ByteBuf data) {
        data.writeInt(getSizeInventory())
            .writeInt(buffer.tanks.length)
            .writeInt(buffer.tankCapacity);
    }

    @Override
    public void readFromStream(ByteBuf data) {
        int items = data.readInt(), fluids = data.readInt(), capacity = data.readInt();
        if (items != getSizeInventory() || fluids != buffer.tanks.length || capacity != buffer.tankCapacity) {
            buffer = new DisassemblyBuffer(items - 2, fluids, capacity);
            rebuildHandler();
        }
    }

    @Override
    public ITexture[][][] getTextureSet(ITexture[] textures) {
        return new ITexture[0][][];
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
        boolean active, boolean redstone) {
        ITexture casing = Textures.BlockIcons.MACHINE_CASINGS[1][color + 1];
        if (side == facing) return new ITexture[] { casing,
            TextureFactory.of(
                active ? Textures.BlockIcons.OVERLAY_FRONT_DISASSEMBLER_ACTIVE
                    : Textures.BlockIcons.OVERLAY_FRONT_DISASSEMBLER) };
        return new ITexture[] { casing };
    }

    @Override
    public ModularPanel buildUI(PosGuiData data, PanelSyncManager sync, UISettings settings) {
        sync.registerSlotGroup(new SlotGroup("input", 1, true));
        ModularPanel panel = ModularPanel.defaultPanel("futa_disassembler", 194, 238)
            .bindPlayerInventory();
        panel.child(
            IKey.lang("futa_gtnh.disassembler.name")
                .asWidget()
                .pos(8, 6));
        panel.child(
            new ItemSlot().slot(new ModularSlot(handler, 0).slotGroup("input"))
                .pos(8, 24));
        IntSyncValue progress = new IntSyncValue(() -> buffer.progress);
        sync.syncValue("progress", progress);
        panel.child(
            IKey.dynamic(() -> "32 EU/t · 1A · " + progress.getIntValue() + "/40 t")
                .asWidget()
                .pos(32, 29));
        PagedWidget<?> items = new PagedWidget<>().pos(16, 58)
            .size(162, 36);
        for (int start = 2; start < getSizeInventory(); start += 18) {
            ParentWidget<?> page = new ParentWidget<>().size(162, 36);
            for (int offset = 0; offset < 18 && start + offset < getSizeInventory(); offset++) page.child(
                new ItemSlot().slot(new ModularSlot(handler, start + offset).accessibility(false, true))
                    .pos(offset % 9 * 18, offset / 9 * 18));
            items.addPage(page);
        }
        panel.child(items);
        pageButtons(panel, sync, items, 42, "futa_gtnh.disassembler.items");
        PagedWidget<?> fluids = new PagedWidget<>().pos(25, 114)
            .size(144, 18);
        for (int start = 0; start < buffer.tanks.length; start += 8) {
            ParentWidget<?> page = new ParentWidget<>().size(144, 18);
            for (int offset = 0; offset < 8 && start + offset < buffer.tanks.length; offset++)
                page.child(
                    new FluidSlot()
                        .syncHandler(
                            new FluidSlotSyncHandler(buffer.tanks[start + offset]).canFillSlot(false)
                                .canDrainSlot(true))
                        .pos(offset * 18, 0));
            fluids.addPage(page);
        }
        panel.child(fluids);
        pageButtons(panel, sync, fluids, 96, "futa_gtnh.disassembler.fluids");
        panel.child(
            IKey.lang("futa_gtnh.disassembler.hint")
                .asWidget()
                .scale(0.7f)
                .pos(8, 136));
        return panel;
    }

    private void pageButtons(ModularPanel panel, PanelSyncManager sync, PagedWidget<?> pages, int y, String key) {
        IntSyncValue pageValue = new IntSyncValue(
            pages::getCurrentPageIndex,
            page -> {
                if (page >= 0 && page < pages.getPages()
                    .size()) pages.setPage(page);
            });
        sync.syncValue(key, pageValue);
        panel.child(
            new ButtonWidget<>().size(16)
                .pos(8, y)
                .overlay(IKey.str("<"))
                .onMousePressed(button -> {
                    pageValue.setIntValue(
                        Math.floorMod(
                            pages.getCurrentPageIndex() - 1,
                            pages.getPages()
                                .size()));
                    return true;
                }));
        panel.child(
            IKey.dynamic(
                () -> IKey.lang(key)
                    .get() + " "
                    + (pages.getCurrentPageIndex() + 1)
                    + "/"
                    + pages.getPages()
                        .size())
                .asWidget()
                .pos(30, y + 3));
        panel.child(
            new ButtonWidget<>().size(16)
                .pos(170, y)
                .overlay(IKey.str(">"))
                .onMousePressed(button -> {
                    pageValue.setIntValue(
                        (pages.getCurrentPageIndex() + 1) % pages.getPages()
                            .size());
                    return true;
                }));
    }
}
