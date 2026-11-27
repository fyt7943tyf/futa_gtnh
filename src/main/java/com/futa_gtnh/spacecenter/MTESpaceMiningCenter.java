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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
import com.futa_gtnh.spacecenter.SpaceCenterMachine.OreChunk;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import gregtech.api.casing.Casings;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.enums.SoundResource;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.IOreMaterial;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.GregTechTileClientEvents;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.objects.GTUOFluid;
import gregtech.api.objects.XSTR;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.check.SimpleCheckRecipeResult;
import gregtech.api.render.TextureFactory;
import gregtech.api.structure.error.StructureError;
import gregtech.api.util.GTModHandler;
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
 * <li>底座：采掘桁架地板，控制器在前缘正中，边缘可贴输入总线/输入仓/输出总线/输出仓/维护/能源仓；</li>
 * <li>中间层：桁架边框 + 中央全息投影核心柱；</li>
 * <li>顶盖：桁架边框 + 全息核心十字 + 外围一圈信号天线阵列。</li>
 * </ul>
 * 整体随控制器朝向旋转：{@code '~'} 在深度第 0 行（最前排），结构朝控制器背面延伸。
 * 形状数组的约定是 {@code String[层(上→下)][深度行(前→后)][水平列]}，
 * 偏移量直接按这个输入数组的下标给——这是 StructureLib {@code transpose} 的输入约定，
 * 不是转置之后的。
 *
 * <p>
 * 机位容量 = {@code baseCapacity + (height-2) * capacityPerLayer}（默认 8/层，9 层 64 台）。
 * 控制器槽（GUI 槽位 1）放火箭物品（Galacticraft {@code ItemTier{N}Rocket} 系）。
 */
public class MTESpaceMiningCenter extends MTEEnhancedMultiBlockBase<MTESpaceMiningCenter>
    implements ISurvivalConstructable, MachineHost {

    private static final String PIECE_BASE = "base";
    private static final String PIECE_LAYER = "layer";
    private static final String PIECE_TOP = "top";
    private static final int HORIZONTAL_OFFSET = 3;
    private static final int DEPTH_OFFSET = 0;
    /** 层数硬上限（形状与 tooltip 都按它写）。 */
    private static final int MAX_HEIGHT = 9;
    /** 最矮结构（底座 + 顶盖）的桁架数：底座 25 + 顶盖 24；每多一层再 +24。 */
    private static final int TRUSS_MIN = 49;
    private static final int TRUSS_PER_LAYER = 24;

    private static IStructureDefinition<MTESpaceMiningCenter> structureDefinition;

    private static final IIconContainer FRONT_OVERLAY = gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer
        .create("futa_gtnh", "space_center_front");
    private static final IIconContainer FRONT_OVERLAY_ACTIVE = gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer
        .create("futa_gtnh", "space_center_front_active");
    private static final IIconContainer CASING_ICON = gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer
        .create("futa_gtnh", "space_center_truss");

    /** 采矿管道（IC2 管道，与真实钻机同款判定）。 */
    private static final ItemStack MINING_PIPE = GTModHandler.getIC2Item("miningPipe", 0);

    /** 每个推进批次长度（tick）＝1 秒，也是润滑油扣款周期。 */
    private static final int BATCH_TICKS = 20;

    /** 每挖一个矿方块的钻井液消耗（mB，对齐真实采矿场）。 */
    private static final int DRILLING_FLUID_PER_ORE = 2000;

    /** 全息投影每换一颗星球隔多少个批次（30 批 = 30 秒）。 */
    private static final int HOLO_SWITCH_BATCHES = 30;

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 结构高度（总层数，2..9）。 */
    private int height = 2;

    /** 每星球配置：8 个计数（矿机1-4、钻机1-4）+ 钻机所选油田。 */
    public static final class PlanetConfig {

        public int[] miners = new int[4];
        public int[] drillers = new int[4];
        /** 选中油田的流体注册名；null = 未选（取该星球权重最大的流体）。 */
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

    /** 全息投影轮播计数（每批次 +1）。 */
    private int holoBatch;
    /** 同步给客户端的全息图标包：0 = 不显示，其余 = 图标槽位 + 1。 */
    private byte holoPacket;
    /** 客户端收到的全息图标槽位（-1 = 不显示）。 */
    private byte clientHoloIconSlot = -1;

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
                // String[层(上→下)][深度行(前→后)][水平列]；'~' 必须落在 (HORIZONTAL_OFFSET, 0, DEPTH_OFFSET) 上
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
        // total = 总层数：1 层底座 + (total-2) 层中间层 + 1 层顶盖
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

    /**
     * 结构 = 底座（1 层）+ 若干中间层 + 顶盖（1 层），{@link #height} 是<b>总层数</b>。
     * 逐层的判定逻辑在 {@link SpaceCenterMachine#resolveHeight}（纯逻辑，有回归检查）。
     */
    @Override
    public void checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack, List<StructureError> errors) {
        height = minHeight();
        if (!checkPiece(PIECE_BASE, HORIZONTAL_OFFSET, 0, DEPTH_OFFSET, errors)) return;
        int resolved = SpaceCenterMachine.resolveHeight(
            minHeight(),
            maxHeight(),
            // 没到上限时这一格还有可能是中间层，顶盖探测失败不算错；最上面一格的顶盖是硬性要求，要报错
            n -> checkPiece(PIECE_TOP, HORIZONTAL_OFFSET, n, DEPTH_OFFSET, n == maxHeight() - 1 ? errors : null),
            n -> checkPiece(PIECE_LAYER, HORIZONTAL_OFFSET, n, DEPTH_OFFSET, errors));
        if (resolved < 0) return;
        height = resolved;
        checkHasAnyEnergy(errors);
        checkOneMaintenanceHatch(errors);
    }

    private int minHeight() {
        return SpaceCenterMachine.clamp(Config.spaceCenterMinHeight, 2, MAX_HEIGHT);
    }

    private int maxHeight() {
        return SpaceCenterMachine.clamp(Config.spaceCenterMaxHeight, minHeight(), MAX_HEIGHT);
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
        return GcCompat.rocketTierOf(getControllerSlot());
    }

    /** 该星球上的虚拟机现在能不能推进：在星球表里、火箭等级够、种子已勘查。 */
    private boolean isRunnable(SpaceCenterMachine machine, int rocketTier) {
        return canOperate(PlanetRegistry.byName(machine.planet), rocketTier);
    }

    /**
     * GUI 状态色用：给定火箭等级下这颗星球是否可以开工。
     * 没放火箭（rocketTier ≤ 0）一律 false。
     */
    public boolean canOperate(PlanetRegistry.PlanetInfo info, int rocketTier) {
        return info != null && rocketTier > 0 && info.tierRequirement <= rocketTier && planetSeed(info) != null;
    }

    /** 该星球是否勘查过（维度加载过、种子有记录）。 */
    public boolean isSurveyed(PlanetRegistry.PlanetInfo info) {
        return info != null && planetSeed(info) != null;
    }

    /** 星球 seed（未勘查 = null）。 */
    private Long planetSeed(PlanetRegistry.PlanetInfo info) {
        return PlanetSeeds.seedOf(info.dimensionId);
    }

    /** GUI 里存的所选油田流体注册名（未选 = null；不校验是否仍在星球的流体表里）。 */
    public String selectedFluidKey(String dimName) {
        PlanetConfig config = configFor(dimName, false);
        return config == null ? null : config.fluidKey;
    }

    /**
     * 钻机在该星球实际要抽的流体：GUI 选中的那一个；没选、或选中的流体已经不在该星球的
     * 油田表里（配置改过）时，退回权重最大的那一个。
     *
     * @return 流体注册名；星球没有油田或已不在星球表里时 null
     */
    @Override
    public String drillingFluid(String planet) {
        PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(planet);
        if (info == null || info.fluids.isEmpty()) return null;
        String selected = selectedFluidKey(planet);
        if (selected != null) {
            for (GTUOFluid fluid : info.fluids) {
                if (fluid.Registry.equals(selected)) return selected;
            }
        }
        // fluids 已按权重降序排好
        return info.fluids.get(0).Registry;
    }

    // ------------------------------------------------------------------
    // MachineHost：虚拟机的资源口
    // ------------------------------------------------------------------

    @Override
    public OreChunk simulateChunk(String planet, int era, int chunkX, int chunkZ) {
        PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(planet);
        if (info == null) return null;
        Long seed = planetSeed(info);
        if (seed == null) return null;
        return toOreChunk(VirtualVeinSim.simulateChunk(planet, seed, info.dimensionId, era, chunkX, chunkZ));
    }

    /**
     * 把模拟结果摊成状态机消费的列表：先矿脉、后小矿，各自按材料 id（再按内部名）升序。
     * 顺序必须是确定的——HashMap 的迭代顺序在两次启动之间并不保证一样，而读档恢复靠
     * 「整块重建 + 跳过已挖数量」，顺序一变跳过的就不是同一批矿。
     */
    private static OreChunk toOreChunk(VirtualVeinSim.ChunkOre ore) {
        List<OreChunk.Entry> entries = new ArrayList<>();
        addEntries(entries, ore.veinBlocks, false);
        addEntries(entries, ore.smallOres, true);
        return new OreChunk(ore.minVeinY, entries);
    }

    private static void addEntries(List<OreChunk.Entry> entries, Map<IOreMaterial, Integer> ores, boolean small) {
        List<Map.Entry<IOreMaterial, Integer>> sorted = new ArrayList<>(ores.entrySet());
        sorted.sort(
            Comparator.comparingInt(
                (Map.Entry<IOreMaterial, Integer> entry) -> entry.getKey()
                    .getId())
                .thenComparing(
                    entry -> entry.getKey()
                        .getInternalName()));
        for (Map.Entry<IOreMaterial, Integer> entry : sorted) {
            IOreMaterial material = entry.getKey();
            entries.add(new OreChunk.Entry(material, material.getInternalName(), entry.getValue(), small));
        }
    }

    @Override
    public int oilUpperBound(String planet, String fluidKey, int chunkX, int chunkZ) {
        PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(planet);
        if (info == null || fluidKey == null) return -1;
        Long seed = planetSeed(info);
        if (seed == null) return -1;
        VirtualOilField.PristineField field = VirtualOilField.pristine(info.dimensionId, seed, chunkX, chunkZ);
        if (field == null || field.fluid == null || !fluidKey.equals(field.fluid.Registry)) return 0;
        return field.upperBoundPerCycle();
    }

    @Override
    public boolean autoResetVein() {
        return Config.spaceCenterAutoResetVein;
    }

    @Override
    public double oilYieldFactor() {
        return Config.spaceCenterOilYieldFactor;
    }

    @Override
    public boolean consumeMiningPipe() {
        if (MINING_PIPE == null) return false;
        return depleteInput(GTUtility.copyAmount(1, MINING_PIPE));
    }

    @Override
    public boolean consumeDrillingFluid() {
        if (gregtech.api.enums.ItemList.sDrillingFluid == null) return false;
        FluidStack fluid = new FluidStack(gregtech.api.enums.ItemList.sDrillingFluid, DRILLING_FLUID_PER_ORE);
        // 先 simulate 再实扣：GT 的流体扣取会跨多个输入仓凑量，不能只看第一个仓
        if (!depleteInput(fluid, true)) return false;
        return depleteInput(fluid, false);
    }

    @Override
    public void emitOre(OreChunk.Entry ore) {
        if (ore == null || ore.material == null) return;
        // 不限定 GT 自家材料：BartWorks / GT++ 的材料走 IOreMaterial 自己的取物口径，
        // 否则这些矿脉挖到了却什么都不产出
        ItemStack oreStack = ore.material.getPart(OrePrefixes.ore, 1);
        if (oreStack == null) return;
        // 精准采集 → 打粉机配方 → 粉碎矿
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
            // 概率没 Roll 中的副产物就是不产出，而不是「保底给 1 个」
            int chance = recipe.getOutputChance(i);
            if (chance < 10000 && machineRandom.nextInt(10000) >= chance) continue;
            ItemStack out = recipe.mOutputs[i].copy();
            // 小矿不套 1~4 倍率
            if (!ore.small) out.stackSize *= 1 + machineRandom.nextInt(4);
            if (out.stackSize > 0) itemBuffer.add(out);
        }
    }

    @Override
    public void emitOil(String planet, int amount) {
        if (amount <= 0) return;
        String registry = drillingFluid(planet);
        if (registry == null) return;
        Integer current = fluidBuffer.get(registry);
        fluidBuffer.put(registry, (current == null ? 0 : current) + amount);
    }

    /** 把缓冲推到输出总线/输出仓（批次结束或手动按钮时调用）。塞不进去的部分按 GT 多方块惯例丢弃。 */
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
            if (!isRunnable(machine, rocketTier)) continue;
            runnable.add(machine);
            totalEu += machine.euPerTick();
        }
        updateHoloPlanet(runnable);
        if (runnable.isEmpty()) return SimpleCheckRecipeResult.ofFailure("futa_sc_no_machine");

        // 润滑油：每台每秒 1000mB（本批次正好 1 秒）；正停着等料的机器不收
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

        // 新批次清一遍软暂停（缺料的会再次置位；「区域采完」是硬停，保留）
        for (SpaceCenterMachine machine : runnable) {
            if (machine.pauseReason != null && !SpaceCenterMachine.PAUSE_AREA_EXHAUSTED.equals(machine.pauseReason)) {
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

    /**
     * 全息投影轮播当前有可运行虚拟机的星球，每 {@value #HOLO_SWITCH_BATCHES} 个批次换一颗。
     * 只同步图标槽位（7 位 custom data），客户端不需要星球名也不需要星球表。
     */
    private void updateHoloPlanet(List<SpaceCenterMachine> runnable) {
        TreeSet<String> planets = new TreeSet<>();
        for (SpaceCenterMachine machine : runnable) {
            planets.add(machine.planet);
        }
        byte packet = 0;
        if (!planets.isEmpty()) {
            holoBatch++;
            String shown = (String) planets.toArray()[Math.floorMod(holoBatch / HOLO_SWITCH_BATCHES, planets.size())];
            packet = (byte) (1 + PlanetRegistry.iconSlotOf(shown));
        }
        setHoloPacket(packet);
    }

    private void setHoloPacket(byte packet) {
        if (packet == holoPacket) return;
        holoPacket = packet;
        IGregTechTileEntity base = getBaseMetaTileEntity();
        if (base != null) base.sendBlockEvent(GregTechTileClientEvents.CHANGE_CUSTOM_DATA, packet);
    }

    /** 服务端：随描述包发给客户端的一小段展示数据（这里是全息图标包）。 */
    @Override
    public byte getUpdateData() {
        return holoPacket;
    }

    /** 客户端：收到 {@link #getUpdateData()} / 块事件传来的全息图标包。 */
    @Override
    public void onValueUpdate(byte aValue) {
        int packet = aValue & 0x7F;
        clientHoloIconSlot = (byte) (packet == 0 ? -1 : packet - 1);
    }

    /** 客户端特效用：全息投影要显示的星球图标槽位；-1 = 不显示。 */
    public byte clientHoloIconSlot() {
        return clientHoloIconSlot;
    }

    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
        // 虚拟机列表是服务端状态：客户端那边 machines 本来就是空的， reconcile 还会顺手去碰星球表
        if (aBaseMetaTileEntity.isServerSide()) reconcileMachines();
    }

    @Override
    public void onPostTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        super.onPostTick(aBaseMetaTileEntity, aTick);
        // 机器停下之后 checkProcessing 不再被调用，全息投影要在这里收掉
        if (holoPacket != 0 && !aBaseMetaTileEntity.isActive()) setHoloPacket((byte) 0);
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
        return aSlot == 1 && GcCompat.rocketTierOf(aStack) > 0;
    }

    @Override
    public boolean isValidSlot(int aSlot) {
        return aSlot == 1;
    }

    // 终端区被本机器自己的三页界面占满：GT 默认塞在右下角的关机原因 / 维护图标 / Logo 会压在页面上
    @Override
    public boolean supportsTerminalRightCornerColumn() {
        return false;
    }

    @Override
    public boolean supportsLogo() {
        return false;
    }

    // ------------------------------------------------------------------
    // 贴图 / 音效 / 提示
    // ------------------------------------------------------------------

    @Override
    public ITexture[] getTexture(IGregTechTileEntity baseMetaTileEntity, ForgeDirection side, ForgeDirection facing,
        int colorIndex, boolean active, boolean redstoneLevel) {
        ITexture casing = TextureFactory.of(CASING_ICON);
        if (side == facing) {
            IIconContainer overlay = active ? FRONT_OVERLAY_ACTIVE : FRONT_OVERLAY;
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
            .addCasingInfoMin(truss, TRUSS_MIN, false)
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
        // 钳到当前配置允许的范围：改过配置或 NBT 被改坏的存档不能把这里撑爆
        height = SpaceCenterMachine.clamp(aNBT.getInteger("height"), minHeight(), maxHeight());
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
            // 等级只有 1..4：越界的话下面按等级查表直接 ArrayIndexOutOfBounds，整台机器跟着炸
            SpaceCenterMachine machine = SpaceCenterMachine
                .create(type, SpaceCenterMachine.clamp(tag.getInteger("tier"), 1, 4), tag.getString("planet"));
            machine.progress = Math.max(0, tag.getInteger("progress"));
            machine.pauseReason = tag.hasKey("pause") ? tag.getString("pause") : null;
            machine.load(tag);
            machines.add(machine);
        }
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
}
