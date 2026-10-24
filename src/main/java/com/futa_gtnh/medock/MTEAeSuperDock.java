package com.futa_gtnh.medock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.slot.SlotGroup;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import cpw.mods.fml.common.registry.GameData;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTUtility;
import gregtech.common.tileentities.storage.MTEDigitalChestBase;
import gregtech.common.tileentities.storage.MTEDigitalTankBase;

/**
 * 「ME 超级对接站」：把 GT 的超级储罐 / 超级箱（量子储罐 / 量子箱也收）作为
 * <b>物品</b>放进一个槽位，内容直接挂进 ME 网络存储 —— 不需要存储总线、不需要
 * 把容器真的搭在世界里。支持配置优先级（语义与 ME 存储总线一致：数值越高越优先
 * 作为写入目标）。
 *
 * <p>
 * 内容读写完全走物品 NBT（和 GT 机器被扳手拆掉后内容随物品走同一套键）：
 * 储罐 {@code mFluid}、箱 {@code mItemStack} + {@code mItemCount}。对接站本身
 * 不缓存内容 —— 拔下来的容器物品随时可以放回世界原样使用。
 *
 * <p>
 * ME 侧接入：{@link appeng.api.storage.ICellContainer}（照抄 GT ME 输出仓的
 * 姿势 —— {@code AENetworkProxy} + {@code REQUIRE_CHANNEL}，优先级套在
 * {@code MEInventoryHandler} 上，改动后 {@code MENetworkCellArrayUpdate} 重挂）。
 */
public class MTEAeSuperDock extends MTETieredMachineBlock
    implements appeng.api.storage.ICellContainer, appeng.me.helpers.IGridProxyable {

    /** 放容器物品的槽。 */
    public static final int SLOT_CONTAINER = 0;

    private static final gregtech.api.interfaces.IIconContainer DOCK_ICON = gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer
        .create("futa_gtnh", "me_super_dock");

    private AENetworkProxy proxy;
    private int priority = 0;
    private boolean handlersDirty = true;
    private IMEInventoryHandler<IAEItemStack> itemHandler;
    private IMEInventoryHandler<IAEFluidStack> fluidHandler;
    private boolean wasActive;

    public MTEAeSuperDock(int id) {
        super(
            id,
            "futa_gtnh.super_dock.lv",
            "ME Super Dock",
            1,
            1,
            new String[] { "Dock a GT super/quantum tank or chest item into the ME network",
                "No storage bus needed; direct cell provider with priority",
                "Contents live in the item NBT - pull it out anytime" });
    }

    private MTEAeSuperDock(String name, int tier, String[] description, ITexture[][][] textures) {
        super(name, tier, 1, description, textures);
    }

    @Override
    protected boolean forceUseMui2() {
        return true;
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
        return new MTEAeSuperDock(mName, mTier, mDescriptionArray, mTextures);
    }

    // ==================================================================
    // 容器槽：只收数字储罐/数字箱系物品
    // ==================================================================

    /** 槽里的容器物品（可能为空）。 */
    public ItemStack containerStack() {
        return mInventory[SLOT_CONTAINER];
    }

    /** 这个物品是不是「数字储罐系」（超级/量子储罐）。 */
    public static boolean isTankItem(ItemStack stack) {
        return mteOf(stack) instanceof MTEDigitalTankBase;
    }

    /** 这个物品是不是「数字箱系」（超级/量子箱）。 */
    public static boolean isChestItem(ItemStack stack) {
        return mteOf(stack) instanceof MTEDigitalChestBase;
    }

    private static IMetaTileEntity mteOf(ItemStack stack) {
        if (stack == null || stack.getItem() != Item.getItemFromBlock(GregTechAPI.sBlockMachines)) {
            return null;
        }
        int id = stack.getItemDamage();
        if (id < 0 || id >= GregTechAPI.METATILEENTITIES.length) return null;
        return GregTechAPI.METATILEENTITIES[id];
    }

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return slot == SLOT_CONTAINER && (isTankItem(stack) || isChestItem(stack));
    }

    @Override
    public boolean isValidSlot(int slot) {
        return slot == SLOT_CONTAINER;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    private boolean isDocked(ItemStack stack) {
        return stack != null && stack == containerStack() && stack.stackSize == 1;
    }

    /** 界面专用槽，不吃自动化（内容进出全部走 ME 网络）。 */
    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return new int[0];
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) {
        return false;
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) {
        return false;
    }

    // ==================================================================
    // AE2 接入
    // ==================================================================

    @Override
    public AENetworkProxy getProxy() {
        if (proxy == null) {
            proxy = new AENetworkProxy(this, "proxy", getStackForm(1), true);
            proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
            proxy.setValidSides(EnumSet.complementOf(EnumSet.of(ForgeDirection.UNKNOWN)));
        }
        return proxy;
    }

    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
        getProxy().onReady();
        handlersDirty = true;
    }

    /** 界面专用槽，不吃自动化：管道/总线都搬不动容器（内容进出只走 ME 网络）。 */
    @Override
    public boolean allowPutStack(IGregTechTileEntity tile, int slot, ForgeDirection side, ItemStack stack) {
        return false;
    }

    @Override
    public boolean allowPullStack(IGregTechTileEntity tile, int slot, ForgeDirection side, ItemStack stack) {
        return false;
    }

    @Override
    public appeng.api.networking.IGridNode getActionableNode() {
        return getProxy().getNode();
    }

    @Override
    public appeng.api.networking.IGridNode getGridNode(ForgeDirection dir) {
        return getProxy().getNode();
    }

    @Override
    public appeng.api.util.DimensionalCoord getLocation() {
        return new appeng.api.util.DimensionalCoord((net.minecraft.tileentity.TileEntity) getBaseMetaTileEntity());
    }

    @Override
    public void securityBreak() {}

    @MENetworkEventSubscribe
    public void onPowerStatusChange(MENetworkPowerStatusChange event) {
        handlersDirty = true;
        updateCellArray();
    }

    /** 槽里的容器换了 / 优先级改了之后调：让网络重挂存储。 */
    public void updateCellArray() {
        try {
            getProxy().getGrid()
                .postEvent(new MENetworkCellArrayUpdate());
        } catch (Exception ignored) {}
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
        if (slot == SLOT_CONTAINER) {
            handlersDirty = true;
            updateCellArray();
        }
    }

    @Override
    public void onPostTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        if (aBaseMetaTileEntity.isServerSide() && aTick % 20 == 0) {
            boolean active = getProxy().isActive();
            if (active != wasActive) {
                wasActive = active;
                handlersDirty = true;
                updateCellArray();
            }
            aBaseMetaTileEntity.setActive(active);
        }
    }

    // ------------------------------------------------------------------
    // ICellContainer
    // ------------------------------------------------------------------

    @Override
    public List<IMEInventoryHandler> getCellArray(IAEStackType<?> type) {
        if (!getProxy().isActive()) return Collections.emptyList();
        ensureHandlers();
        if (type == AEItemStackType.ITEM_STACK_TYPE && itemHandler != null) {
            return Collections.singletonList(itemHandler);
        }
        if (type == AEFluidStackType.FLUID_STACK_TYPE && fluidHandler != null) {
            return Collections.singletonList(fluidHandler);
        }
        return Collections.emptyList();
    }

    @Override
    public List<IMEInventoryHandler> getCellArray(StorageChannel channel) {
        return getCellArray(
            channel == StorageChannel.FLUIDS ? AEFluidStackType.FLUID_STACK_TYPE : AEItemStackType.ITEM_STACK_TYPE);
    }

    @Override
    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        handlersDirty = true;
        updateCellArray();
        markDirty();
    }

    /** ICellContainer.saveChanges：内容写穿后标脏（物品 NBT 在机器库存里）。 */
    @Override
    public void saveChanges(IMEInventory cellInventory) {
        markDirty();
    }

    private void ensureHandlers() {
        if (!handlersDirty) return;
        handlersDirty = false;
        itemHandler = null;
        fluidHandler = null;
        ItemStack stack = containerStack();
        if (stack == null) return;
        if (isChestItem(stack)) {
            itemHandler = wrap(new ChestContentInventory(stack), AEItemStackType.ITEM_STACK_TYPE);
        } else if (isTankItem(stack)) {
            fluidHandler = wrap(new TankContentInventory(stack), AEFluidStackType.FLUID_STACK_TYPE);
        }
    }

    private <T extends appeng.api.storage.data.IAEStack<T>> IMEInventoryHandler<T> wrap(IMEInventory<T> inventory,
        IAEStackType<T> type) {
        MEInventoryHandler<T> handler = new MEInventoryHandler<>(inventory, type);
        handler.setPriority(priority);
        return handler;
    }

    // ==================================================================
    // 内容库存：直接读写容器物品的 NBT
    // ==================================================================

    /** 数字箱（超级/量子箱）内容：单一物品 + 数量。 */
    private final class ChestContentInventory implements IMEInventory<IAEItemStack> {

        private final ItemStack docked;

        ChestContentInventory(ItemStack docked) {
            this.docked = docked;
        }

        private int capacity() {
            IMetaTileEntity mte = mteOf(docked);
            return mte instanceof MTEDigitalChestBase chest ? chest.getMaxItemCount() : 0;
        }

        private ItemStack prototype() {
            if (!docked.hasTagCompound()) return null;
            return ItemStack.loadItemStackFromNBT(docked.stackTagCompound.getCompoundTag("mItemStack"));
        }

        private long count() {
            if (!docked.hasTagCompound()) return 0;
            return docked.stackTagCompound.getInteger("mItemCount");
        }

        private void write(ItemStack prototype, long count) {
            if (!docked.hasTagCompound()) docked.stackTagCompound = new NBTTagCompound();
            if (prototype == null || count <= 0) {
                docked.stackTagCompound.removeTag("mItemStack");
                docked.stackTagCompound.setInteger("mItemCount", 0);
            } else {
                ItemStack bare = prototype.copy();
                bare.stackSize = 1;
                docked.stackTagCompound.setTag("mItemStack", bare.writeToNBT(new NBTTagCompound()));
                docked.stackTagCompound.setInteger("mItemCount", (int) Math.min(count, Integer.MAX_VALUE));
            }
            markDirty();
        }

        @Override
        public IAEItemStack injectItems(IAEItemStack input, Actionable mode, BaseActionSource src) {
            if (!isDocked(docked) || input == null || input.getStackSize() <= 0) return input;
            ItemStack prototype = prototype();
            long count = count();
            boolean empty = prototype == null || count <= 0;
            if (!empty && !GTUtility.areStacksEqual(prototype, input.getItemStack())) return input;
            long space = capacity() - count;
            long accepted = Math.min(Math.max(0, space), input.getStackSize());
            if (accepted <= 0) return input;
            if (mode == Actionable.MODULATE) write(empty ? input.getItemStack() : prototype, count + accepted);
            if (accepted >= input.getStackSize()) return null;
            IAEItemStack leftover = input.copy();
            leftover.setStackSize(input.getStackSize() - accepted);
            return leftover;
        }

        @Override
        public IAEItemStack extractItems(IAEItemStack request, Actionable mode, BaseActionSource src) {
            if (!isDocked(docked) || request == null || request.getStackSize() <= 0) return null;
            ItemStack prototype = prototype();
            long count = count();
            if (prototype == null || count <= 0) return null;
            if (!GTUtility.areStacksEqual(prototype, request.getItemStack())) return null;
            long taken = Math.min(count, request.getStackSize());
            if (taken <= 0) return null;
            if (mode == Actionable.MODULATE) {
                long rest = count - taken;
                write(rest > 0 ? prototype : null, rest);
            }
            IAEItemStack result = request.copy();
            result.setStackSize(taken);
            return result;
        }

        @Override
        public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out, int iteration) {
            if (!isDocked(docked)) return out;
            ItemStack prototype = prototype();
            long count = count();
            if (prototype != null && count > 0) {
                IAEItemStack stored = AEItemStack.create(prototype);
                if (stored != null) {
                    stored.setStackSize(count);
                    out.add(stored);
                }
            }
            return out;
        }
    }

    /** 数字储罐（超级/量子储罐）内容：单一流体 + 数量。 */
    private final class TankContentInventory implements IMEInventory<IAEFluidStack> {

        private final ItemStack docked;

        TankContentInventory(ItemStack docked) {
            this.docked = docked;
        }

        private int capacity() {
            IMetaTileEntity mte = mteOf(docked);
            return mte instanceof MTEDigitalTankBase tank ? tank.getRealCapacity() : 0;
        }

        private FluidStack contents() {
            if (!docked.hasTagCompound()) return null;
            FluidStack fluid = FluidStack.loadFluidStackFromNBT(docked.stackTagCompound.getCompoundTag("mFluid"));
            return fluid != null && fluid.amount > 0 ? fluid : null;
        }

        private Fluid lockedFluid() {
            if (!docked.hasTagCompound()) return null;
            String name = docked.stackTagCompound.getString("lockedFluidName");
            return name.isEmpty() ? null : FluidRegistry.getFluid(name);
        }

        private void write(FluidStack fluid) {
            if (!docked.hasTagCompound()) docked.stackTagCompound = new NBTTagCompound();
            if (fluid == null || fluid.amount <= 0) {
                docked.stackTagCompound.removeTag("mFluid");
            } else {
                FluidStack bare = fluid.copy();
                bare.amount = Math.min(bare.amount, capacity());
                docked.stackTagCompound.setTag("mFluid", bare.writeToNBT(new NBTTagCompound()));
            }
            markDirty();
        }

        @Override
        public IAEFluidStack injectItems(IAEFluidStack input, Actionable mode, BaseActionSource src) {
            if (!isDocked(docked) || input == null || input.getStackSize() <= 0) return input;
            FluidStack contents = contents();
            Fluid inputFluid = input.getFluid();
            if (inputFluid == null) return input;
            Fluid locked = lockedFluid();
            if (locked != null && locked != inputFluid) return input;
            if (contents != null && !contents.isFluidEqual(input.getFluidStack())) return input;
            long current = contents == null ? 0 : contents.amount;
            long space = capacity() - current;
            long accepted = Math.min(Math.max(0, space), input.getStackSize());
            if (accepted <= 0) return input;
            if (mode == Actionable.MODULATE) {
                FluidStack next = new FluidStack(inputFluid, (int) Math.min(current + accepted, Integer.MAX_VALUE));
                FluidStack source = contents == null ? input.getFluidStack() : contents;
                if (source.tag != null) next.tag = (NBTTagCompound) source.tag.copy();
                if (docked.stackTagCompound != null && docked.stackTagCompound.getBoolean("mLockFluid")
                    && locked == null) {
                    docked.stackTagCompound.setString("lockedFluidName", inputFluid.getName());
                }
                write(next);
            }
            if (accepted >= input.getStackSize()) return null;
            IAEFluidStack leftover = input.copy();
            leftover.setStackSize(input.getStackSize() - accepted);
            return leftover;
        }

        @Override
        public IAEFluidStack extractItems(IAEFluidStack request, Actionable mode, BaseActionSource src) {
            if (!isDocked(docked) || request == null || request.getStackSize() <= 0) return null;
            FluidStack contents = contents();
            if (contents == null || !contents.isFluidEqual(request.getFluidStack())) return null;
            long taken = Math.min(contents.amount, request.getStackSize());
            if (taken <= 0) return null;
            if (mode == Actionable.MODULATE) {
                long rest = contents.amount - taken;
                FluidStack next = rest > 0 ? new FluidStack(contents.getFluid(), (int) rest) : null;
                if (next != null && contents.tag != null) next.tag = contents.tag;
                write(next);
            }
            IAEFluidStack result = request.copy();
            result.setStackSize(taken);
            return result;
        }

        @Override
        public IItemList<IAEFluidStack> getAvailableItems(IItemList<IAEFluidStack> out, int iteration) {
            if (!isDocked(docked)) return out;
            FluidStack contents = contents();
            if (contents != null) {
                IAEFluidStack stored = AEFluidStack.create(contents);
                if (stored != null) out.add(stored);
            }
            return out;
        }
    }

    // ==================================================================
    // 电力：不需要
    // ==================================================================

    @Override
    public boolean isEnetInput() {
        return false;
    }

    @Override
    public boolean isEnetOutput() {
        return false;
    }

    @Override
    public boolean isElectric() {
        return false;
    }

    @Override
    public boolean isInputFacing(ForgeDirection side) {
        return false;
    }

    @Override
    public boolean isOutputFacing(ForgeDirection side) {
        return false;
    }

    @Override
    public boolean isLiquidInput(ForgeDirection side) {
        return false;
    }

    @Override
    public boolean isLiquidOutput(ForgeDirection side) {
        return false;
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

    // ==================================================================
    // NBT
    // ==================================================================

    @Override
    public void saveNBTData(NBTTagCompound tag) {
        tag.setInteger("futaDockPriority", priority);
        getProxy().writeToNBT(tag);
    }

    @Override
    public void loadNBTData(NBTTagCompound tag) {
        priority = tag.getInteger("futaDockPriority");
        if (tag.hasKey("proxy")) getProxy().readFromNBT(tag);
        handlersDirty = true;
    }

    /** 机器被拆成物品时：容器在库存里，GT 自己会把库存还给玩家 / 掉落。 */
    @Override
    public @NotNull ArrayList<ItemStack> getDroppedItem() {
        return null;
    }

    // ==================================================================
    // 外观 / 界面
    // ==================================================================

    @Override
    public ITexture[][][] getTextureSet(ITexture[] textures) {
        return new ITexture[0][][];
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
        boolean active, boolean redstone) {
        ITexture casing = Textures.BlockIcons.MACHINE_CASINGS[1][color + 1];
        if (side == facing) {
            return new ITexture[] { casing, TextureFactory.of(DOCK_ICON) };
        }
        return new ITexture[] { casing };
    }

    @Override
    public ModularPanel buildUI(PosGuiData data, PanelSyncManager sync, UISettings settings) {
        sync.registerSlotGroup(new SlotGroup("dock", 1, true));
        ModularPanel panel = ModularPanel.defaultPanel("futa_super_dock", 194, 166)
            .bindPlayerInventory();
        panel.child(
            IKey.lang("futa_gtnh.medock.name")
                .asWidget()
                .pos(8, 6));
        panel.child(
            new ItemSlot().slot(
                new ModularSlot(getInventoryHandlerForUi(), SLOT_CONTAINER).slotGroup("dock")
                    .filter(stack -> isTankItem(stack) || isChestItem(stack)))
                .pos(8, 24));
        IntSyncValue priorityValue = new IntSyncValue(this::getPriority, this::setPriority).allowC2S();
        sync.syncValue("priority", priorityValue);
        panel.child(
            new com.cleanroommc.modularui.widgets.textfield.TextFieldWidget().pos(100, 24)
                .width(70)
                .height(14)
                .setFormatAsInteger(true)
                .value(priorityValue)
                .numbersInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
        panel.child(
            IKey.lang("futa_gtnh.medock.priority")
                .asWidget()
                .pos(100, 44));
        StringSyncValue info = new StringSyncValue(this::encodeInfoLine);
        sync.syncValue("info", info);
        panel.child(
            IKey.dynamic(() -> localizeInfoLine(info.getStringValue()))
                .asWidget()
                .pos(8, 60));
        panel.child(
            IKey.lang("futa_gtnh.medock.hint")
                .asWidget()
                .pos(8, 76)
                .scale(0.8f));
        return panel;
    }

    /** 信息行编码的字段分隔符（不可见控制字符，不撞数据）。 */
    private static final char INFO_SEP = '\u001F';

    /**
     * 服务端：把信息行要显示的<b>原始数据</b>编码同步——本地化留给客户端，
     * 专用服务端只加载 en_US，服务端拼好的句子同步给中文客户端就是英文。
     * 格式：模式 + 变长字段（数量/容量/物品注册名/元数据/自定义名/流体名）。
     */
    private String encodeInfoLine() {
        ItemStack stack = containerStack();
        if (stack == null) return "0";
        if (isChestItem(stack)) {
            ChestContentInventory view = new ChestContentInventory(stack);
            ItemStack prototype = view.prototype();
            long count = view.count();
            if (prototype == null || count <= 0) return "1" + INFO_SEP + view.capacity();
            String id = GameData.getItemRegistry()
                .getNameForObject(prototype.getItem());
            String customName = customDisplayNameOf(prototype);
            return "2" + INFO_SEP
                + count
                + INFO_SEP
                + view.capacity()
                + INFO_SEP
                + id
                + INFO_SEP
                + prototype.getItemDamage()
                + INFO_SEP
                + (customName == null ? "" : customName);
        }
        if (isTankItem(stack)) {
            TankContentInventory view = new TankContentInventory(stack);
            FluidStack contents = view.contents();
            if (contents == null) return "3" + INFO_SEP + view.capacity();
            return "4" + INFO_SEP
                + contents.amount
                + INFO_SEP
                + view.capacity()
                + INFO_SEP
                + FluidRegistry.getFluidName(contents);
        }
        return "0";
    }

    /** 物品 NBT 里玩家自定义的显示名——语言无关的字面量，可以原样跨端传；没有则 null。 */
    private static String customDisplayNameOf(ItemStack stack) {
        if (!stack.hasTagCompound()) return null;
        String name = stack.stackTagCompound.getCompoundTag("display")
            .getString("Name");
        return name == null || name.isEmpty() ? null : name;
    }

    /** 客户端：解码 {@link #encodeInfoLine} 的原始数据并本地化成信息行文本。 */
    private static String localizeInfoLine(String encoded) {
        if (encoded == null || encoded.isEmpty()) return StatCollector.translateToLocal("futa_gtnh.medock.empty");
        String[] parts = encoded.split(String.valueOf(INFO_SEP));
        int mode;
        long a, b;
        try {
            mode = Integer.parseInt(parts[0]);
            switch (mode) {
                case 1 -> {
                    return StatCollector
                        .translateToLocalFormatted("futa_gtnh.medock.chest_empty", Long.parseLong(parts[1]));
                }
                case 2 -> {
                    a = Long.parseLong(parts[1]);
                    b = Long.parseLong(parts[2]);
                    ItemStack prototype = decodePrototype(parts[3], Integer.parseInt(parts[4]), parts[5]);
                    String displayName = prototype != null ? prototype.getDisplayName() : parts[3];
                    return StatCollector.translateToLocalFormatted("futa_gtnh.medock.chest_info", displayName, a, b);
                }
                case 3 -> {
                    return StatCollector
                        .translateToLocalFormatted("futa_gtnh.medock.tank_empty", Long.parseLong(parts[1]));
                }
                case 4 -> {
                    a = Long.parseLong(parts[1]);
                    b = Long.parseLong(parts[2]);
                    Fluid fluid = FluidRegistry.getFluid(parts[3]);
                    String name = fluid == null ? parts[3] : new FluidStack(fluid, 0).getLocalizedName();
                    return StatCollector.translateToLocalFormatted("futa_gtnh.medock.tank_info", name, a, b);
                }
                default -> {
                    return StatCollector.translateToLocal("futa_gtnh.medock.empty");
                }
            }
        } catch (RuntimeException ignored) {
            // 编码对不上（跨版本等）：退回「空」，不崩界面
            return StatCollector.translateToLocal("futa_gtnh.medock.empty");
        }
    }

    /** 客户端：按注册名/元数据/自定义名重建物品原型（只用于取显示名；取不到返回 null）。 */
    private static ItemStack decodePrototype(String registryName, int meta, String customName) {
        int split = registryName.indexOf(':');
        if (split <= 0) return null;
        Item item = GameRegistry.findItem(registryName.substring(0, split), registryName.substring(split + 1));
        if (item == null) return null;
        ItemStack stack = new ItemStack(item, 1, meta);
        if (customName != null && !customName.isEmpty()) stack.setStackDisplayName(customName);
        return stack;
    }

    private com.cleanroommc.modularui.utils.item.IItemHandlerModifiable getInventoryHandlerForUi() {
        // GUI 槽直接挂机器库存：放/取容器物品都经 setInventorySlotContents 触发重挂
        return new com.cleanroommc.modularui.utils.item.InvWrapper(this);
    }
}
