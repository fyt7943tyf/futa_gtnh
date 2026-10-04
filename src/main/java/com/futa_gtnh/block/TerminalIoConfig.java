package com.futa_gtnh.block;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

/**
 * 「共享终端方块往哪个面主动搬东西」的配置：方向 + 筛选。
 *
 * <p>
 * 每个面、每种东西（物品 / 流体）各自有一个方向：
 * <ul>
 * <li>{@link Mode#OFF}：什么都不做；</li>
 * <li>{@link Mode#PULL}：把<b>相邻容器</b>里的东西搬进共享存储（主动抽取）；</li>
 * <li>{@link Mode#PUSH}：把<b>共享存储</b>里的东西搬到相邻容器（主动输出）；</li>
 * <li>{@link Mode#PUSH_PULL}：同一个面双向都做 —— 先抽入再输出，一轮里各按各的档位走。</li>
 * </ul>
 * 物品和流体分开配，是因为「同一个面抽物品、同时往同一个面输出流体」是很常见的组合
 * （GT 的覆盖板也是这么分的）。
 *
 * <p>
 * <b>每面独立的输出白名单</b>：主动推送和被动抽取都受它限制，空白名单不输出。
 * 物品和流体分别匹配；主动抽入和被动接收不使用筛选。
 *
 * <p>
 * 筛选条件有三种，<b>任意一种命中就算通过</b>：
 * <ol>
 * <li>具体的物品 / 流体条目（可以选多个）；</li>
 * <li>{@link Preset 矿辞预设}（原矿、粉碎的矿、洗净的矿、含杂的矿、热力离心后的矿）；</li>
 * <li>自己敲的矿辞前缀（例如 {@code ingot}、{@code plate}）。</li>
 * </ol>
 *
 * <p>
 * <b>矿辞是「按最具体的前缀归类」的。</b>这一点必须说清楚，因为 GT 的前缀天生重叠：
 * {@code crushedIron}（粉碎的矿）是 {@code crushedPurifiedIron}（洗净的矿）的<b>前缀</b>。
 * 如果直接 {@code startsWith} 判断，勾了「粉碎的矿」就会把洗净的、离心的也一起放进来。
 * 所以这里先在所有已知前缀（5 个预设的前缀 + 自己敲的）里找出<b>最长</b>的那个匹配，
 * 再问「那一个是不是被勾上了」—— {@code crushedPurifiedIron} 归到
 * {@code crushedPurified}，于是勾「粉碎的矿」不会误伤它。
 */
public final class TerminalIoConfig {

    /**
     * 一个面、一种东西的搬运方向。
     *
     * <p>
     * {@link #PUSH_PULL} 加在枚举末尾：NBT 里存的是 ordinal，追加不影响旧存档的 0..2。
     * 共享终端自己的配置界面仍然只在前三态里循环，这一态是 IO 节点在用。
     */
    public enum Mode {

        OFF("futa_gtnh.gui.terminal.io.mode.off"),
        PULL("futa_gtnh.gui.terminal.io.mode.pull"),
        PUSH("futa_gtnh.gui.terminal.io.mode.push"),
        PUSH_PULL("futa_gtnh.gui.terminal.io.mode.push_pull");

        /** 这个方向会不会往共享存储里搬（主动抽入）。 */
        public boolean pulls() {
            return this == PULL || this == PUSH_PULL;
        }

        /** 这个方向会不会从共享存储往外搬（主动输出）。 */
        public boolean pushes() {
            return this == PUSH || this == PUSH_PULL;
        }

        private final String langKey;

        Mode(String langKey) {
            this.langKey = langKey;
        }

        public String getLangKey() {
            return langKey;
        }

        /** 循环切换：关 → 抽 → 输 → 关。界面上的按钮点一下就往下走一格。 */
        public Mode next() {
            Mode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /**
     * 默认的矿辞预设。
     *
     * <p>
     * 前缀都对着 GT 的 {@code OrePrefixes} 抄的（连大小写）：GT 的矿辞名就是
     * 「前缀 + 材料名」，所以 {@code crushed} + {@code Iron} = {@code crushedIron}。
     * 「原矿」给了两个前缀：GTNH 2.7 之后矿石掉的是<b>粗矿</b>（{@code rawOreIron}），
     * 而老版本和部分模组用的还是<b>矿石方块</b>（{@code oreIron}）——
     * 两个都认，免得玩家在两种世界里得到一个「勾了没反应」的预设。
     */
    public enum Preset {

        RAW_ORE("rawOre", "ore"),
        CRUSHED("crushed"),
        PURIFIED("crushedPurified"),
        IMPURE("dustImpure"),
        CENTRIFUGED("crushedCentrifuged");

        private final String[] prefixes;

        Preset(String... prefixes) {
            this.prefixes = prefixes;
        }

        public String[] getPrefixes() {
            return prefixes;
        }
    }

    /** 面的数量，等于 {@code ForgeDirection} 的取值个数。 */
    public static final int FACES = 6;

    /**
     * 节奏用「档位」而不是任意整数。
     *
     * <p>
     * 这些档位<b>同时就是服务端的上限</b>：客户端把配置发上来之后，
     * 服务端会把三个数字各自吸附到最接近的档位上（见 {@link #readFromNbt}）。
     * 所以伪造一个「每 tick 搬一百万」的包没有用 —— 落在档位之间的值会被夹回来。
     * 界面上也沿着同一组档位加加减减，玩家看到的就是服务端认的那几个数。
     *
     * <p>
     * <b>档位要够高，别让方块自己先成瓶颈。</b>中段是按 GTNH 管道的量级定的：
     * 流体那几个数字（Enderium 1800、Naquadah 9000、Neutronium 16800、
     * NetherStar 19200、MysteriousCrystal 24000，单位 L/s）是从 GT 的
     * {@code LoaderMetaPipeEntities.registerFluidPipes} 里逐个读出来的，
     * 16000 mB/轮在 20 tick 那一档正好等于中子素管道的 16000 L/s。
     *
     * <p>
     * 顶部两档则是<b>刻意远远超过任何管道</b>的（物品 32768 个/轮、流体 2000 万 mB/轮）：
     * 共享存储是个无限仓库，玩家要的往往是「一口气把这一箱抽空」，
     * 实际能搬多少仍然由目标容器自己决定（一次 fill / 一格一格塞，
     * 塞不下的部分原样留在仓库里），所以档位给得高只是少一道人为限制，
     * 不会凭空造出东西、也不会把目标撑爆。
     */
    private static final int[] INTERVAL_STEPS = { 1, 2, 5, 10, 20, 40, 100 };
    private static final int[] ITEM_STEPS = { 1, 4, 16, 64, 256, 1024, 4096, 16384, 32768 };
    private static final int[] FLUID_STEPS = { 100, 500, 1000, 4000, 16000, 64000, 256000, 1000000, 4000000, 20000000 };

    /** 每隔多少 tick 动一轮。 */
    private int intervalTicks = 5;
    /** 每个面每轮最多搬多少个物品。 */
    private int itemsPerOperation = 16;
    /** 每个面每轮最多搬多少毫巴流体。 */
    private int fluidPerOperation = 1000;

    private final Mode[] itemModes = new Mode[FACES];
    private final Mode[] fluidModes = new Mode[FACES];

    private int modeRevision;
    private final TerminalOutputFilter[] outputFilters = new TerminalOutputFilter[FACES];

    public TerminalIoConfig() {
        for (int i = 0; i < FACES; i++) {
            itemModes[i] = Mode.OFF;
            fluidModes[i] = Mode.OFF;
            outputFilters[i] = new TerminalOutputFilter();
        }
    }

    // ==================================================================
    // 方向
    // ==================================================================

    public Mode getMode(ForgeDirection face, boolean fluid) {
        int index = index(face);
        return index < 0 ? Mode.OFF : (fluid ? fluidModes[index] : itemModes[index]);
    }

    public void setMode(ForgeDirection face, boolean fluid, Mode mode) {
        int index = index(face);
        if (index < 0 || mode == null || getMode(face, fluid) == mode) return;
        modeRevision++;
        if (fluid) {
            fluidModes[index] = mode;
        } else {
            itemModes[index] = mode;
        }
    }

    /** @return 有没有任何一个面配了动作。没有的话整个搬运逻辑直接跳过。 */
    public boolean hasAnyMode() {
        for (int i = 0; i < FACES; i++) {
            if (itemModes[i] != Mode.OFF || fluidModes[i] != Mode.OFF) return true;
        }
        return false;
    }

    /** @return 节奏被改过没有（决定要不要往存档里写这一段） */
    public boolean hasCustomRates() {
        return intervalTicks != 5 || itemsPerOperation != 16 || fluidPerOperation != 1000;
    }

    private static int index(ForgeDirection face) {
        if (face == null) return -1;
        int ordinal = face.ordinal();
        return ordinal >= 0 && ordinal < FACES ? ordinal : -1;
    }

    // ==================================================================
    // 节奏（每台终端各自设置，界面上改）
    // ==================================================================

    public int getIntervalTicks() {
        return intervalTicks;
    }

    public int getItemsPerOperation() {
        return itemsPerOperation;
    }

    public int getFluidPerOperation() {
        return fluidPerOperation;
    }

    /** 间隔的档位编号，界面画「第几档」用。 */
    public int getIntervalStep() {
        return stepIndex(INTERVAL_STEPS, intervalTicks);
    }

    public int getItemStep() {
        return stepIndex(ITEM_STEPS, itemsPerOperation);
    }

    public int getFluidStep() {
        return stepIndex(FLUID_STEPS, fluidPerOperation);
    }

    public int getIntervalStepCount() {
        return INTERVAL_STEPS.length;
    }

    public int getItemStepCount() {
        return ITEM_STEPS.length;
    }

    public int getFluidStepCount() {
        return FLUID_STEPS.length;
    }

    /**
     * 沿档位走一格。
     *
     * @param which 0 = 间隔，1 = 物品，2 = 流体
     * @param delta +1 / -1
     */
    public void stepRate(int which, int delta) {
        switch (which) {
            case 0:
                intervalTicks = step(INTERVAL_STEPS, intervalTicks, delta);
                break;
            case 1:
                itemsPerOperation = step(ITEM_STEPS, itemsPerOperation, delta);
                break;
            default:
                fluidPerOperation = step(FLUID_STEPS, fluidPerOperation, delta);
                break;
        }
    }

    /** 把三个数字都吸附到最近的档位上。用在读存档 / 读客户端发来的配置时。 */
    private void snapRates() {
        intervalTicks = snap(INTERVAL_STEPS, intervalTicks);
        itemsPerOperation = snap(ITEM_STEPS, itemsPerOperation);
        fluidPerOperation = snap(FLUID_STEPS, fluidPerOperation);
    }

    private static int step(int[] steps, int current, int delta) {
        int index = stepIndex(steps, current);
        int next = Math.max(0, Math.min(steps.length - 1, index + (delta < 0 ? -1 : 1)));
        return steps[next];
    }

    private static int stepIndex(int[] steps, int value) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            int distance = Math.abs(steps[i] - value);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int snap(int[] steps, int value) {
        return steps[stepIndex(steps, value)];
    }

    // ==================================================================
    // 筛选
    // ==================================================================

    /** 仅有效的六个面拥有筛选；UNKNOWN 不对应可抽取的输出。 */
    public TerminalOutputFilter getOutputFilter(ForgeDirection face) {
        int index = index(face);
        if (index < 0) throw new IllegalArgumentException("Output filters require a valid terminal face");
        return outputFilters[index];
    }

    public boolean matchesOutput(ForgeDirection face, ItemStack stack) {
        int index = index(face);
        return index >= 0 && outputFilters[index].matches(stack);
    }

    public boolean matchesOutput(ForgeDirection face, FluidStack stack) {
        int index = index(face);
        return index >= 0 && outputFilters[index].matches(stack);
    }

    /** 输出槽位索引缓存同时跟踪方向与白名单变化。 */
    public int getOutputRevision() {
        int revision = modeRevision;
        for (TerminalOutputFilter filter : outputFilters) revision += filter.getRevision();
        return revision;
    }

    public boolean hasAnyFilter() {
        for (TerminalOutputFilter filter : outputFilters) {
            if (!filter.isEmpty()) return true;
        }
        return false;
    }

    public void clearFilters() {
        for (TerminalOutputFilter filter : outputFilters) filter.clear();
    }

    // ==================================================================
    // 存档 / 网络
    // ==================================================================

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();

        byte[] itemBytes = new byte[FACES];
        byte[] fluidBytes = new byte[FACES];
        for (int i = 0; i < FACES; i++) {
            itemBytes[i] = (byte) itemModes[i].ordinal();
            fluidBytes[i] = (byte) fluidModes[i].ordinal();
        }
        tag.setByteArray("itemModes", itemBytes);
        tag.setByteArray("fluidModes", fluidBytes);

        tag.setInteger("intervalTicks", intervalTicks);
        tag.setInteger("itemsPerOperation", itemsPerOperation);
        tag.setInteger("fluidPerOperation", fluidPerOperation);

        tag.setInteger("filterVersion", 2);
        NBTTagList filters = new NBTTagList();
        for (int i = 0; i < FACES; i++) {
            NBTTagCompound filter = outputFilters[i].writeToNbt();
            filter.setInteger("face", i);
            filters.appendTag(filter);
        }
        tag.setTag("faceFilters", filters);

        return tag;
    }

    public void readFromNbt(NBTTagCompound tag) {
        modeRevision++;
        clearFilters();
        intervalTicks = 5;
        itemsPerOperation = 16;
        fluidPerOperation = 1000;
        for (int i = 0; i < FACES; i++) {
            itemModes[i] = Mode.OFF;
            fluidModes[i] = Mode.OFF;
        }

        if (tag == null) return;

        // 节奏：存档里读出来的、以及客户端发上来的一律先吸附到档位。
        // 客户端发的那份不能信，这一步就是那道闸门（落在档位之间的值会被夹回来）
        if (tag.hasKey("intervalTicks")) intervalTicks = tag.getInteger("intervalTicks");
        if (tag.hasKey("itemsPerOperation")) itemsPerOperation = tag.getInteger("itemsPerOperation");
        if (tag.hasKey("fluidPerOperation")) fluidPerOperation = tag.getInteger("fluidPerOperation");
        snapRates();

        byte[] itemBytes = tag.getByteArray("itemModes");
        byte[] fluidBytes = tag.getByteArray("fluidModes");
        Mode[] values = Mode.values();
        for (int i = 0; i < FACES; i++) {
            if (itemBytes.length > i && itemBytes[i] >= 0 && itemBytes[i] < values.length) {
                itemModes[i] = values[itemBytes[i]];
            }
            if (fluidBytes.length > i && fluidBytes[i] >= 0 && fluidBytes[i] < values.length) {
                fluidModes[i] = values[fluidBytes[i]];
            }
        }

        if (tag.hasKey("faceFilters", 9)) {
            NBTTagList filters = tag.getTagList("faceFilters", 10);
            for (int i = 0; i < filters.tagCount(); i++) {
                NBTTagCompound filter = filters.getCompoundTagAt(i);
                if (!filter.hasKey("face", 3)) continue;
                int face = filter.getInteger("face");
                if (face >= 0 && face < FACES) outputFilters[face].readFromNbt(filter);
            }
        } else {
            // 旧版的全局白名单复制给六个面。空白名单按新版规则禁止输出。
            for (TerminalOutputFilter filter : outputFilters) filter.readFromNbt(tag);
        }
    }
}
