package com.futa_gtnh.spacecenter;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.transpose;
import static gregtech.api.enums.HatchElement.Energy;
import static gregtech.api.enums.HatchElement.ExoticEnergy;
import static gregtech.api.enums.HatchElement.InputBus;
import static gregtech.api.enums.HatchElement.InputHatch;
import static gregtech.api.enums.HatchElement.Maintenance;
import static gregtech.api.enums.HatchElement.OutputBus;
import static gregtech.api.enums.HatchElement.OutputHatch;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;

import com.futa_gtnh.Config;
import com.futa_gtnh.block.BlockSpaceCenterPart;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import gregtech.api.casing.Casings;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.enums.SoundResource;
import gregtech.api.interfaces.IOreMaterial;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.objects.XSTR;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.check.SimpleCheckRecipeResult;
import gregtech.api.render.TextureFactory;
import gregtech.api.structure.error.StructureError;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.gui.modularui.multiblock.base.MTEMultiBlockBaseGui;
import gregtech.common.misc.GTStructureChannels;

/**
 * 「深空采矿指挥中心」：7x7xN（2..9 层）多方块。塞入 X 级火箭后，按星球远程配置虚拟的
 * 1-4 级采矿场与流体钻机：矿机用 {@link VirtualVeinSim} 确定性复现真实矿脉、钻机用
 * {@link VirtualOilField} 复现油田上界产量（×配置系数固定产出）。不可超频，总功率 = 全部
 * 启用虚拟机之和；运行时每台每秒消耗润滑油（默认 1000mB）。
 *
 * <p>
 * 结构（7x7 底座 + 可叠加层 + 顶盖，模板 {@code MTEMegaDistillationTower}）：
 * <ul>
 * <li>底座：采掘桁架地板，控制器在前中，边缘可贴输入总线/输入仓/输出总线/输出仓/维护/能源仓；</li>
 * <li>中间层：桁架边框 + 中央全息投影核心柱；</li>
 * <li>顶盖：桁架边框 + 全息核心环 + 中央天线阵列。</li>
 * </ul>
 * 机位容量 = {@code baseCapacity + (height-2) * capacityPerLayer}（默认 8/层，9 层 64 台）。
 * 控制器槽（GUI 槽位 1）放火箭物品（Galacticraft {@code ItemTier{N}Rocket} 系）。
 */
public class MTESpaceMiningCenter extends MTEEnhancedMultiBlockBase<MTESpaceMiningCenter>
    implements ISurvivalConstructable {

    private static final String PIECE_BASE = "base";
    private static final String PIECE_LAYER = "layer";
    private static final String PIECE_TOP = "top";
    private static final int HORIZONTAL_OFFSET = 3;
    private static final int DEPTH_OFFSET = 0;

    private static IStructureDefinition<MTESpaceMiningCenter> structureDefinition;

    private static final gregtech.api.interfaces.IIconContainer FRONT_OVERLAY = gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer
        .create("futa_gtnh", "space_center_front");
    private static final gregtech.api.interfaces.IIconContainer FRONT_OVERLAY_ACTIVE = gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer
        .create("futa_gtnh", "space_center_front_active");
    private static final gregtech.api.interfaces.IIconContainer CASING_ICON = gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer
        .create("futa_gtnh", "space_center_truss");

    /** 采矿管道（IC2 管道，与真实钻机同款判定）。 */
    private static final ItemStack MINING_PIPE = GTModHandler.getIC2Item("miningPipe", 0);

    /** 每个推进批次长度（tick）＝1 秒，也是润滑油扣款周期。 */
    private static final int BATCH_TICKS = 20;

    /** 每挖一个矿方块的钻井液消耗（mB，对齐真实采矿场）。 */
    private static final int DRILLING_FLUID_PER_ORE = 2000;

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 结构高度（层数，2..9）。 */
    private int height = 2;

    /** 每星球配置：8 个计数（矿机1-4、钻机1-4）+ 钻机所选油田。 */
    public static final class PlanetConfig {

        public int[] miners = new int[4];
        public int[] drillers = new int[4];
        /** 选中油田的流体注册名；null = 未选（产出按该星球权重最大的流体）。 */
        public String fluidKey;

        public int total() {
            int sum = 0;
            for (int n : miners) sum += n;
            for (int n : drillers) sum += n;
            return sum;
        }
    }

    /** planet dimName -> config。 */
    private final Map<String, PlanetConfig> planetConfigs = new HashMap<>();
    /** 运行中的虚拟机器（与配置保持同步）。 */
    private final List<SpaceCenterMachine> machines = new ArrayList<>();
    /** 批次产物缓冲（物品）。 */
    private final List<ItemStack> itemBuffer = new ArrayList<>();
    /** 批次产物缓冲（流体注册名 -> mB）。 */
    private final Map<String, Integer> fluidBuffer = new HashMap<>();
    /** 虚拟机自己的随机源（打粉产出 1~4 倍）。 */
    private final XSTR machineRandom = new XSTR();

    public MTESpaceMiningCenter(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTESpaceMiningCenter(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTESpaceMiningCenter(this.mName);
    }

    // ------------------------------------------------------------------
    // 结构
    // ------------------------------------------------------------------

    @Override
    public IStructureDefinition<MTESpaceMiningCenter> getStructureDefinition() {
        if (structureDefinition == null) {
            structureDefinition = StructureDefinition.<MTESpaceMiningCenter>builder()
                // spotless:off
                // 轴向约定与 MTEDrillerBase 相同：String[height][depth][width]，层从上到下、行从前到后
                // '~' 必须落在 (HORIZONTAL_OFFSET, 0, DEPTH_OFFSET) 上（结构 lib 用它认控制器）
                .addShape(PIECE_BASE, transpose(new String[][] {
                    { "HHH~HHH",
                      "HCCCCCH",
                      "HCCCCCH",
                      "HCCCCCH",
                      "HCCCCCH",
                      "HCCCCCH",
                      "HHHHHHH" } }))
                .addShape(PIECE_LAYER, transpose(new String[][] {
                    { "CCCCCCC",
                      "C.....C",
                      "C.....C",
                      "C..K..C",
                      "C.....C",
                      "C.....C",
                      "CCCCCCC" } }))
                .addShape(PIECE_TOP, transpose(new String[][] {
                    { "CCCCCCC",
                      "CAAAAAC",
                      "CAKKKAC",
                      "CAKAKAC",
                      "CAKKKAC",
                      "CAAAAAC",
                      "CCCCCCC" } }))
                // spotless:on
                .addElement('C', lazyOfTruss())
                .addElement('K', lazyOfHolo())
                .addElement('A', lazyOfAntenna())
                .addElement(
                    'H',
                    buildHatchAdder(MTESpaceMiningCenter.class)
                        .atLeast(Maintenance, InputBus, InputHatch, OutputBus, OutputHatch, Energy.or(ExoticEnergy))
                        .casingIndex(Casings.SolidSteelMachineCasing.textureId)
                        .hint(1)
                        .buildAndChain(lazyOfTruss()))
                .build();
        }
        return structureDefinition;
    }

    private static com.gtnewhorizon.structurelib.structure.IStructureElement<MTESpaceMiningCenter> lazyOfTruss() {
        return com.gtnewhorizon.structurelib.structure.StructureUtility
            .lazy(t -> ofBlock(SpaceCenterRegistration.partBlock(), BlockSpaceCenterPart.META_TRUSS));
    }

    private static com.gtnewhorizon.structurelib.structure.IStructureElement<MTESpaceMiningCenter> lazyOfHolo() {
        return com.gtnewhorizon.structurelib.structure.StructureUtility
            .lazy(t -> ofBlock(SpaceCenterRegistration.partBlock(), BlockSpaceCenterPart.META_HOLO_CORE));
    }

    private static com.gtnewhorizon.structurelib.structure.IStructureElement<MTESpaceMiningCenter> lazyOfAntenna() {
        return com.gtnewhorizon.structurelib.structure.StructureUtility
            .lazy(t -> ofBlock(SpaceCenterRegistration.partBlock(), BlockSpaceCenterPart.META_ANTENNA));
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        int total = GTStructureChannels.STRUCTURE_HEIGHT.getValueClamped(stackSize, minHeight(), maxHeight());
        buildPiece(PIECE_BASE, stackSize, hintsOnly, HORIZONTAL_OFFSET, 0, DEPTH_OFFSET);
        for (int layer = 1; layer <= total - 2; layer++) {
            buildPiece(PIECE_LAYER, stackSize, hintsOnly, HORIZONTAL_OFFSET, layer, DEPTH_OFFSET);
        }
        buildPiece(PIECE_TOP, stackSize, hintsOnly, HORIZONTAL_OFFSET, total - 1, DEPTH_OFFSET);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, ISurvivalBuildEnvironment env) {
        if (this.mMachine) return -1;
        int realBudget = Math.max(elementBudget, 200);
        int total = GTStructureChannels.STRUCTURE_HEIGHT.getValueClamped(stackSize, minHeight(), maxHeight());
        int built = survivalBuildPiece(
            PIECE_BASE,
            stackSize,
            HORIZONTAL_OFFSET,
            0,
            DEPTH_OFFSET,
            realBudget,
            env,
            false);
        if (built >= 0) return built;
        for (int layer = 1; layer <= total - 2; layer++) {
            built = survivalBuildPiece(
                PIECE_LAYER,
                stackSize,
                HORIZONTAL_OFFSET,
                layer,
                DEPTH_OFFSET,
                realBudget,
                env,
                false);
            if (built >= 0) return built;
        }
        return survivalBuildPiece(
            PIECE_TOP,
            stackSize,
            HORIZONTAL_OFFSET,
            total - 1,
            DEPTH_OFFSET,
            realBudget,
            env,
            false);
    }

    @Override
    public void checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack, List<StructureError> errors) {
        height = minHeight();
        if (!checkPiece(PIECE_BASE, HORIZONTAL_OFFSET, 0, DEPTH_OFFSET, errors)) return;
        while (height < maxHeight()) {
            if (checkPiece(PIECE_TOP, HORIZONTAL_OFFSET, height - 1, DEPTH_OFFSET, null)) {
                height++;
                break;
            }
            if (!checkPiece(PIECE_LAYER, HORIZONTAL_OFFSET, height - 1, DEPTH_OFFSET, errors)) return;
            height++;
        }
        checkHasAnyEnergy(errors);
        checkOneMaintenanceHatch(errors);
    }

    private int minHeight() {
        return Math.max(2, Config.spaceCenterMinHeight);
    }

    private int maxHeight() {
        return GTUtility.clamp(Config.spaceCenterMaxHeight, minHeight(), 9);
    }

    /** 机位容量 = 基础 + 每层加成。 */
    public int capacity() {
        return Math.max(0, Config.spaceCenterBaseCapacity + (height - 2) * Config.spaceCenterCapacityPerLayer);
    }

    // ------------------------------------------------------------------
    // 配置与虚拟机管理
    // ------------------------------------------------------------------

    /** 配置读取（GUI 用）。 */
    public PlanetConfig configFor(String dimName, boolean create) {
        PlanetConfig config = planetConfigs.get(dimName);
        if (config == null && create) {
            config = new PlanetConfig();
            planetConfigs.put(dimName, config);
        }
        return config;
    }

    /** 按配置重建虚拟机列表；同（类型/等级/星球）的旧机器保留运行状态。 */
    public void reconcileMachines() {
        Map<String, List<SpaceCenterMachine>> pool = new HashMap<>();
        for (SpaceCenterMachine machine : machines) {
            pool.computeIfAbsent(machineKey(machine.type(), machine.tier, machine.planet), k -> new ArrayList<>())
                .add(machine);
        }
        List<SpaceCenterMachine> next = new ArrayList<>();
        for (PlanetRegistry.PlanetInfo planet : PlanetRegistry.planets()) {
            PlanetConfig config = planetConfigs.get(planet.dimName);
            if (config == null) continue;
            for (int tier = 1; tier <= 4; tier++) {
                for (int i = 0; i < config.miners[tier - 1]; i++) {
                    next.add(popOrCreate(pool, SpaceCenterMachine.Type.MINER, tier, planet.dimName));
                }
                for (int i = 0; i < config.drillers[tier - 1]; i++) {
                    next.add(popOrCreate(pool, SpaceCenterMachine.Type.DRILLER, tier, planet.dimName));
                }
            }
        }
        machines.clear();
        machines.addAll(next);
    }

    private SpaceCenterMachine popOrCreate(Map<String, List<SpaceCenterMachine>> pool, SpaceCenterMachine.Type type,
        int tier, String planet) {
        List<SpaceCenterMachine> bucket = pool.get(machineKey(type, tier, planet));
        if (bucket != null && !bucket.isEmpty()) return bucket.remove(0);
        return SpaceCenterMachine.create(type, tier, planet);
    }

    private static String machineKey(SpaceCenterMachine.Type type, int tier, String planet) {
        return type.ordinal() + "/" + tier + "/" + planet;
    }

    /** 火箭等级（未放/非火箭 = -1）。 */
    public int rocketTier() {
        if (!GcCompat.isAvailable()) return -1;
        return GcCompat.rocketTierOf(getControllerSlot());
    }

    public boolean autoResetVein() {
        return Config.spaceCenterAutoResetVein;
    }

    public double oilYieldFactor() {
        return Config.spaceCenterOilYieldFactor;
    }

    /** 该星球选中油田的流体注册名（未选 = null → 视为该星球权重最大的流体）。 */
    public String selectedFluidKey(String dimName) {
        PlanetConfig config = configFor(dimName, false);
        return config == null ? null : config.fluidKey;
    }

    /** 星球 seed（未勘查 = null）。 */
    public Long planetSeed(String dimName) {
        PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(dimName);
        if (info == null) return null;
        return PlanetSeeds.seedOf(info.dimensionId);
    }

    // ------------------------------------------------------------------
    // 资源口（虚拟机调用）
    // ------------------------------------------------------------------

    boolean consumeMiningPipe() {
        if (MINING_PIPE == null) return false;
        return depleteInput(GTUtility.copyAmount(1, MINING_PIPE));
    }

    boolean consumeDrillingFluid() {
        if (gregtech.api.enums.ItemList.sDrillingFluid == null) return false;
        FluidStack fluid = new FluidStack(gregtech.api.enums.ItemList.sDrillingFluid, DRILLING_FLUID_PER_ORE);
        if (!depleteInput(fluid, true)) return false;
        return depleteInput(fluid, false);
    }

    void emitOre(IOreMaterial material, boolean allowMultiplier) {
        if (!(material instanceof Materials gtMaterial)) return;
        ItemStack oreStack = GTOreDictUnificator.get(OrePrefixes.ore, gtMaterial, 1);
        if (oreStack == null) return;
        // 精准采集 → 打粉机配方 → 粉碎矿（对齐 MTEOreDrillingPlantBase.getOutputByDrops）
        GTRecipe recipe = RecipeMaps.maceratorRecipes.findRecipeQuery()
            .caching(true)
            .items(oreStack)
            .voltage(32)
            .find();
        if (recipe == null) {
            itemBuffer.add(oreStack.copy());
            return;
        }
        for (int i = 0; i < recipe.mOutputs.length; i++) {
            ItemStack out = recipe.mOutputs[i].copy();
            if (machineRandom.nextInt(10000) < recipe.getOutputChance(i)) {
                if (allowMultiplier) out.stackSize *= 1 + machineRandom.nextInt(4);
            }
            if (out.stackSize > 0) itemBuffer.add(out);
        }
    }

    void emitOil(String planet, int amount) {
        if (amount <= 0) return;
        String registry = selectedFluidKey(planet);
        if (registry == null) {
            PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(planet);
            if (info == null || info.fluids.isEmpty()) return;
            registry = info.fluids.get(0).Registry;
        }
        Integer current = fluidBuffer.get(registry);
        fluidBuffer.put(registry, (current == null ? 0 : current) + amount);
    }

    /** 把缓冲推到输出总线/输出仓（批次结束或手动按钮时调用）。 */
    public void flushBuffers() {
        for (ItemStack stack : itemBuffer) {
            addOutputPartial(stack);
        }
        itemBuffer.clear();
        for (Map.Entry<String, Integer> entry : fluidBuffer.entrySet()) {
            net.minecraftforge.fluids.Fluid fluid = FluidRegistry.getFluid(entry.getKey());
            if (fluid != null && entry.getValue() > 0) {
                addOutputPartial(new FluidStack(fluid, entry.getValue()));
            }
        }
        fluidBuffer.clear();
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    @Override
    @NotNull
    public CheckRecipeResult checkProcessing() {
        mEfficiencyIncrease = 10000;
        mEfficiency = 10000 - (getIdealStatus() - getRepairStatus()) * 1000;

        reconcileMachines();
        int rocketTier = rocketTier();
        if (rocketTier <= 0) return SimpleCheckRecipeResult.ofFailure("futa_sc_no_rocket");

        List<SpaceCenterMachine> runnable = new ArrayList<>();
        long totalEu = 0;
        for (SpaceCenterMachine machine : machines) {
            PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(machine.planet);
            if (info == null) continue;
            if (info.tierRequirement > rocketTier) continue; // 火箭等级不够
            if (planetSeed(machine.planet) == null) continue; // 星球未勘查
            runnable.add(machine);
            totalEu += machine.euPerTick();
        }
        if (runnable.isEmpty()) return SimpleCheckRecipeResult.ofFailure("futa_sc_no_machine");

        // 润滑油：每台每秒 1000mB（本批次正好 1 秒）
        int lubricantMachines = 0;
        for (SpaceCenterMachine machine : runnable) {
            if (machine.pauseReason == null) lubricantMachines++;
        }
        if (lubricantMachines > 0) {
            FluidStack lubricant = Materials.Lubricant
                .getFluid((long) lubricantMachines * Config.spaceCenterLubricantPerMachine);
            if (lubricant == null || !depleteInput(lubricant, true)) {
                return SimpleCheckRecipeResult.ofFailure("futa_sc_no_lubricant");
            }
            depleteInput(lubricant, false);
        }

        // 新批次清一遍软暂停（缺料的会再次置位；区域采完等硬停保留）
        for (SpaceCenterMachine machine : runnable) {
            if (machine.pauseReason != null && !"area_exhausted".equals(machine.pauseReason)) {
                machine.pauseReason = null;
            }
        }

        for (SpaceCenterMachine machine : runnable) {
            machine.advance(BATCH_TICKS, this);
        }
        flushBuffers();

        mMaxProgresstime = BATCH_TICKS;
        mEUt = (int) -totalEu;
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    // ------------------------------------------------------------------
    // 槽位（火箭）与 GUI
    // ------------------------------------------------------------------

    @Override
    protected @NotNull MTEMultiBlockBaseGui<?> getGui() {
        return new SpaceCenterGui(this);
    }

    @Override
    protected boolean canUseControllerSlotForRecipe() {
        return false;
    }

    @Override
    public boolean isItemValidForSlot(int aSlot, ItemStack aStack) {
        return aSlot == 1 && GcCompat.isAvailable() && GcCompat.rocketTierOf(aStack) > 0;
    }

    @Override
    public boolean isValidSlot(int aSlot) {
        return aSlot == 1;
    }

    // ------------------------------------------------------------------
    // 贴图 / 音效 / 提示
    // ------------------------------------------------------------------

    @Override
    public ITexture[] getTexture(IGregTechTileEntity baseMetaTileEntity, ForgeDirection side, ForgeDirection facing,
        int colorIndex, boolean active, boolean redstoneLevel) {
        ITexture casing = TextureFactory.of(CASING_ICON);
        if (side == facing) {
            gregtech.api.interfaces.IIconContainer overlay = active ? FRONT_OVERLAY_ACTIVE : FRONT_OVERLAY;
            return new ITexture[] { casing, TextureFactory.builder()
                .addIcon(overlay)
                .extFacing()
                .build(),
                TextureFactory.builder()
                    .addIcon(overlay)
                    .extFacing()
                    .glow()
                    .build() };
        }
        return new ITexture[] { casing };
    }

    @Override
    protected SoundResource getActivitySoundLoop() {
        return SoundResource.GT_MACHINES_OIL_DRILL_LOOP;
    }

    @Override
    protected MultiblockTooltipBuilder createTooltip() {
        String truss = StatCollector.translateToLocal("futa_gtnh.space_center.tt.truss");
        String holo = StatCollector.translateToLocal("futa_gtnh.space_center.tt.holo");
        String antenna = StatCollector.translateToLocal("futa_gtnh.space_center.tt.antenna");
        String centerColumn = StatCollector.translateToLocal("futa_gtnh.space_center.tt.center_column");
        String topCenter = StatCollector.translateToLocal("futa_gtnh.space_center.tt.top_center");
        String baseEdge = StatCollector.translateToLocal("futa_gtnh.space_center.tt.base_edge");
        String front = StatCollector.translateToLocal("futa_gtnh.space_center.tt.front");
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        tt.addMachineType(StatCollector.translateToLocal("futa_gtnh.space_center.machine_type"))
            .addInfo(StatCollector.translateToLocal("futa_gtnh.space_center.desc.0"))
            .addInfo(StatCollector.translateToLocal("futa_gtnh.space_center.desc.1"))
            .addInfo(StatCollector.translateToLocal("futa_gtnh.space_center.desc.2"))
            .addSeparator()
            .beginStructureBlock(7, 2, 7, false)
            .addController(front)
            .addCasingInfoMin(truss, 36, false)
            .addOtherStructurePart(holo, centerColumn)
            .addOtherStructurePart(antenna, topCenter)
            .addInputBus(baseEdge, 1)
            .addInputHatch(baseEdge, 1)
            .addOutputBus(baseEdge, 1)
            .addOutputHatch(baseEdge, 1)
            .addEnergyHatch(baseEdge, 1)
            .addMaintenanceHatch(baseEdge, 1)
            .toolTipFinisher("GregTech");
        return tt;
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
        aNBT.setInteger("height", height);
        NBTTagList configList = new NBTTagList();
        for (Map.Entry<String, PlanetConfig> entry : planetConfigs.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("planet", entry.getKey());
            PlanetConfig config = entry.getValue();
            tag.setIntArray("miners", config.miners);
            tag.setIntArray("drillers", config.drillers);
            if (config.fluidKey != null) tag.setString("fluid", config.fluidKey);
            configList.appendTag(tag);
        }
        aNBT.setTag("planetConfigs", configList);
        NBTTagList machineList = new NBTTagList();
        for (SpaceCenterMachine machine : machines) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger(
                "type",
                machine.type()
                    .ordinal());
            SpaceCenterMachine.saveCommon(tag, machine);
            machine.save(tag);
            machineList.appendTag(tag);
        }
        aNBT.setTag("machines", machineList);
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        height = Math.max(minHeight(), aNBT.getInteger("height"));
        planetConfigs.clear();
        NBTTagList configList = aNBT.getTagList("planetConfigs", 10);
        for (int i = 0; i < configList.tagCount(); i++) {
            NBTTagCompound tag = configList.getCompoundTagAt(i);
            PlanetConfig config = new PlanetConfig();
            int[] miners = tag.getIntArray("miners");
            int[] drillers = tag.getIntArray("drillers");
            for (int t = 0; t < 4 && t < miners.length; t++) config.miners[t] = Math.max(0, miners[t]);
            for (int t = 0; t < 4 && t < drillers.length; t++) config.drillers[t] = Math.max(0, drillers[t]);
            config.fluidKey = tag.hasKey("fluid") ? tag.getString("fluid") : null;
            planetConfigs.put(tag.getString("planet"), config);
        }
        machines.clear();
        NBTTagList machineList = aNBT.getTagList("machines", 10);
        for (int i = 0; i < machineList.tagCount(); i++) {
            NBTTagCompound tag = machineList.getCompoundTagAt(i);
            SpaceCenterMachine.Type type = tag.getInteger("type") == 1 ? SpaceCenterMachine.Type.DRILLER
                : SpaceCenterMachine.Type.MINER;
            SpaceCenterMachine machine = SpaceCenterMachine
                .create(type, Math.max(1, tag.getInteger("tier")), tag.getString("planet"));
            machine.progress = tag.getInteger("progress");
            if (tag.hasKey("pause")) machine.pauseReason = tag.getString("pause");
            machine.load(tag);
            machines.add(machine);
        }
    }

    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
        reconcileMachines();
    }

    // ------------------------------------------------------------------
    // GUI 数据访问
    // ------------------------------------------------------------------

    public List<SpaceCenterMachine> machines() {
        return machines;
    }

    public int structureHeight() {
        return height;
    }

    /** 顶盖天线格上表面中心的世界坐标（运行光柱与粒子的锚点）。 */
    public double[] beamAnchor() {
        IGregTechTileEntity base = getBaseMetaTileEntity();
        // 结构 7×7：宽向 x-3..x+3（控制器居中），深向 z..z+6（前缘贴控制器），天线列在 (x, z+3)
        return new double[] { base.getXCoord() + 0.5, base.getYCoord() + height, base.getZCoord() + 3.5 };
    }

    // ------------------------------------------------------------------
    // 客户端氛围（粒子）
    // ------------------------------------------------------------------

    @Override
    public void onPreTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        super.onPreTick(aBaseMetaTileEntity, aTick);
        // 运行中：天线顶偶发火花 + 传送门涡旋（对齐 MTEIndustrialMacerator 的客户端粒子范式）
        if (!aBaseMetaTileEntity.isClientSide() || !aBaseMetaTileEntity.isActive()) return;
        if (aTick % 10 != 0) return;
        net.minecraft.world.World world = aBaseMetaTileEntity.getWorld();
        java.util.Random random = world.rand;
        double[] anchor = beamAnchor();
        for (int i = 0; i < 2; i++) {
            world.spawnParticle(
                "magicCrit",
                anchor[0] + (random.nextDouble() - 0.5D) * 5.0D,
                anchor[1] + random.nextDouble() * 1.5D,
                anchor[2] + (random.nextDouble() - 0.5D) * 5.0D,
                0.0D,
                0.05D + random.nextDouble() * 0.10D,
                0.0D);
        }
        if (aTick % 40 == 0) {
            world.spawnParticle("portal", anchor[0], anchor[1], anchor[2], 0.0D, 0.3D, 0.0D);
        }
    }
}
