package com.futa_gtnh.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientStorageCache;
import com.futa_gtnh.client.NecharBridge;
import com.futa_gtnh.client.Pinyin;
import com.futa_gtnh.client.StorageViewEntry;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * 网页用的物品目录：id ↔ 物品、显示名、模组、搜索文本、以及「我现在有多少」。
 *
 * <p>
 * <b>目录来源是 NEI 的物品清单</b>（{@code ItemList.items}），不是创造模式物品栏 ——
 * GTNH 里大量物品根本不在任何创造标签页里（GT 的隐藏元物品、各种中间产物），
 * 而玩家在 NEI 里搜得到它们，所以在网页上也必须搜得到。NEI 自己那份清单
 * 还包含 NBT 变体（附魔书、GT 电池），同样是我们想要的。
 *
 * <p>
 * <b>身份是「物品 + 元数据」</b>，刻意不含 NBT。理由和 NEI 判定
 * {@code areStacksSameTypeCrafting} 一样：配方树要连得起来。
 * 「电动马达（带 NBT 的 GT 元物品）」和配方里写的那个马达，在玩家眼里是同一个东西，
 * 要是把 NBT 算进身份，树会在每一个 GT 元物品上断掉。代价是极少数
 * 「靠 NBT 区分」的输出（附魔书之类）会合并成一个节点，这个取舍是划算的。
 *
 * <p>
 * <b>扫描在客户端线程上分帧做</b>（每 tick 一个预算），和 {@code client/ItemIndex}
 * 同一个套路：几万个物品一次性算拼音搜索串会明显卡一下，摊到几十 tick 里就没感觉了。
 */
public final class WebStore {

    private WebStore() {}

    /** 每 tick 处理多少个物品条目。 */
    private static final int BUILD_BUDGET_PER_TICK = 4000;

    /** 目录上限，纯粹是防御性的（正常 GTNH 在一万到几万之间）。 */
    private static final int MAX_ENTRIES = 400000;

    /** 两次「请服务端重发共享背包快照」之间的最小间隔（毫秒）。 */
    private static final long SNAPSHOT_INTERVAL_MILLIS = 5000L;

    private static final Object LOCK = new Object();

    /**
     * 拼音表的专用锁。
     *
     * <p>
     * {@code Pinyin} 的字典是「第一次用到才读」的，而这里有两个线程会碰它：
     * 客户端线程在分帧建目录，索引线程在给配方里的新物品补登记。
     * 两边都从 {@link #registerLocked} 走，所以在这里串起来就够了 ——
     * 不去改 Pinyin 自己的懒加载，那样会牵动别的调用方。
     */
    private static final Object PINYIN_LOCK = new Object();

    private static final List<ItemStack> STACKS = new ArrayList<>();
    private static final List<String> NAMES = new ArrayList<>();
    private static final List<String> MODS = new ArrayList<>();
    private static final List<String> SEARCH = new ArrayList<>();
    /** 每个物品的稳定键（{@code registryName@meta}），和 STACKS 一一对应。 */
    private static final List<String> KEYS = new ArrayList<>();
    /** 每个物品是不是「非消耗品」，见 {@link #isCatalyst(int)}。 */
    private static final List<Boolean> CATALYST = new ArrayList<>();
    private static final Map<String, Integer> BY_KEY = new HashMap<>();

    /**
     * 每个编号指向的「正式编号」。
     *
     * <p>
     * 身份重复的条目（同一种流体的另一套显示物品、注册表里的重复栈）<b>照样占一个编号</b>，
     * 只是对外一律指向第一个。这么做的理由是<b>编号必须稳定</b>：
     * 以前重复条目被直接跳过、不占号，于是「哪些条目算重复」一变（例如把 NEI 的流体伪物品
     * 和 GT 的流体显示物品并成同一个身份），后面所有编号集体前移 ——
     * 玩家存在浏览器里的计划、选好的配方就全部指向了别的物品，而且页面上看不出来。
     * 现在编号只由「目录扫描顺序」决定，改身份规则再也不会动它。
     */
    private static final List<Integer> ALIAS = new ArrayList<>();

    /** NEI 自己那套流体伪物品的注册名：它把流体写在 damage 上，NBT 是空的。 */
    private static final String NEI_FLUID_ITEM = "NotEnoughItems:neiFluidDisplay";

    /** 被并掉的重复条目数（诊断用：说明这次身份规整生效了多少条）。 */
    private static int mergedEntries;

    /** 合并的几个例子（诊断用：日志里能直接看到「谁和谁并了」）。 */
    private static final List<String> mergedExamples = new ArrayList<>();

    private static volatile Map<Long, Long> stock = Collections.emptyMap();
    private static volatile boolean catalogReady;
    private static volatile boolean catalogFailed;

    private static List<ItemStack> pending;
    private static int cursor;

    // ==================================================================
    // 目录构建（客户端线程，分帧）
    // ==================================================================

    /** 目录是否可用。没就绪时搜索一律返回空，界面会显示进度。 */
    public static boolean isReady() {
        return catalogReady;
    }

    public static float progress() {
        if (catalogReady) return 1.0F;
        int total = pending == null ? 0 : pending.size();
        return total <= 0 ? 0.0F : Math.min(1.0F, (float) cursor / (float) total);
    }

    /** 目录来源的待处理总量（诊断用）。 */
    public static int sourceSize() {
        List<ItemStack> local = pending;
        return local == null ? 0 : local.size();
    }

    /**
     * 在客户端 tick 里调用。
     *
     * <p>
     * 目录的数据源是 {@code client/ItemIndex}（模组原有的那份「所有物品」清单，
     * 它扫的是物品注册表 + 创造模式标签页）。这里<b>特意不用 NEI 的
     * {@code ItemList.items}</b>：那份清单是 NEI 按需加载的，实测在标题界面
     * 待着不动会一直停在「没加载完」，用它等于把本功能钉死在「必须先开一次
     * NEI 物品面板」。而配方里出现的物品本来就会由索引那边补登记，
     * 所以缺的只是「还没被任何配方引用到」的那些，代价可以忽略。
     */
    public static void tick() {
        if (catalogReady || catalogFailed) return;

        if (pending == null) {
            com.futa_gtnh.client.ItemIndex.ensureStarted();
            com.futa_gtnh.client.ItemIndex.tick();
            if (com.futa_gtnh.client.ItemIndex.isBuilding()) return;

            int size = com.futa_gtnh.client.ItemIndex.size();
            if (size <= 0) return;

            List<ItemStack> all = new ArrayList<>(size);
            com.futa_gtnh.client.ItemIndex.filter("", all);
            if (all.isEmpty()) return;

            pending = all;
            cursor = 0;
            FutaGtnhMod.LOG.info("网页配方：开始建立物品目录，共 {} 个条目", all.size());
        }

        int end = Math.min(pending.size(), cursor + BUILD_BUDGET_PER_TICK);
        synchronized (LOCK) {
            if (STACKS.size() < MAX_ENTRIES) {
                for (int i = cursor; i < end; i++) {
                    registerLocked(pending.get(i));
                }
            }
        }
        cursor = end;

        if (cursor >= pending.size()) {
            catalogReady = true;
            pending = null;
            FutaGtnhMod.LOG.info("网页配方：物品目录完成，共 {} 个条目", size());
            // ★ 身份规整的实况：同一种流体的两套显示物品（GT 的 + NEI 的）在这里被并成一个身份。
            // 「熔融焊锡有两个、我选的那条没配方」这类问题，这一行就是答案 ——
            // 合并之后只剩正式那条带配方，重复的那条编号保留（老计划不会指到别的东西）但不展示。
            if (mergedEntries() > 0) {
                FutaGtnhMod.LOG.info(
                    "网页配方：其中 {} 条与其他条目同身份（编号保留、不单独展示）。例：{}",
                    Integer.valueOf(mergedEntries()),
                    mergedExampleText());
            }
        }
    }

    /**
     * 登记一个物品（幂等）。
     *
     * <p>
     * 配方里出现的物品不一定在 NEI 的物品清单里（机器产出的中间产物、只有配方的虚拟物品），
     * 所以索引那边随时可能补登记，这里必须能处理「目录还在建」的情况。
     */
    public static int idOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return -1;
        String key = keyOf(stack);
        synchronized (LOCK) {
            Integer existing = BY_KEY.get(key);
            if (existing != null) return existing;
            return registerLocked(stack);
        }
    }

    /**
     * 按缓存里存下来的身份登记（回读缓存走这条）。
     *
     * <p>
     * 键不能重算：身份键里含显示名（流体就是），而显示名要问语言表 ——
     * 回读发生在索引线程上，那里不做这种事。存了什么就用什么。
     */
    public static int adopt(ItemStack stack, String key) {
        if (stack == null || stack.getItem() == null || key == null || key.isEmpty()) return -1;
        synchronized (LOCK) {
            return registerLocked(stack, key);
        }
    }

    private static int registerLocked(ItemStack stack) {
        return registerLocked(stack, keyOf(stack));
    }

    /**
     * 登记一个物品条目。
     *
     * <p>
     * <b>每个条目都占一个编号</b>，哪怕身份和前面某个条目重复（见 {@link #ALIAS}）：
     * 重复的那个照样有自己的名字、图标、搜索文本，只是搜索列表里不单独展示、
     * 被引用到时指向第一个。
     *
     * @return 这个身份的<b>正式编号</b>（重复条目则返回第一个的编号）
     */
    private static int registerLocked(ItemStack stack, String key) {
        // ★ 编号只由「目录扫描顺序」决定，和身份规整无关。
        //
        // 关键点：<b>不能</b>因为「身份已经有了」就跳过一个条目、不给它编号。
        // 一跳号，后面所有编号集体前移 —— 玩家存在浏览器里的计划、选好的配方
        // 就全部指向了别的物品，而且页面上看不出来（这件事真的发生过：
        // 把 NEI 的流体伪物品和 GT 的流体显示物品并成一个身份之后，
        // 「熔融焊锡」的老计划指到了另一条同名的、没有配方的条目上）。
        //
        // 所以：每个条目都占号，身份重复的那个指向正式那条（{@link #ALIAS}）。
        // 也不能按「同一个物品栈」（注册名+damage）跳过：GT 的流体显示物品
        // 正是同注册名、同 damage、靠 NBT 区分不同流体，按栈跳会把两种流体并成一个。
        Integer existing = BY_KEY.get(key);

        int id = STACKS.size();
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        STACKS.add(copy);
        String name = displayName(copy);
        NAMES.add(name);
        MODS.add(modIdOf(copy));
        SEARCH.add(buildSearchText(copy, name));
        CATALYST.add(looksLikeCatalyst(key, name));
        KEYS.add(key);

        if (existing != null) {
            ALIAS.add(existing);
            mergedEntries++;
            synchronized (mergedExamples) {
                if (mergedExamples.size() < 8) {
                    mergedExamples.add("「" + key + "」← " + registryKey(copy));
                }
            }
            return existing.intValue();
        }

        BY_KEY.put(key, Integer.valueOf(id));
        ALIAS.add(Integer.valueOf(id));
        return id;
    }

    /**
     * 编号的「正式身份」：重复条目的号指向第一个（见 {@link #ALIAS}）。
     *
     * <p>
     * 网页/书签里存的编号可能是旧会话留下的，所以每个进来的编号都要过这一道，
     * 而不是假设它一定是正式的。认出重复条目、把它指回正式编号，
     * 玩家那边的表现就是「我以前存的计划又对了」，而不是「它指向了另一个同名的东西」。
     */
    public static int canonicalId(int id) {
        synchronized (LOCK) {
            return canonicalLocked(id);
        }
    }

    private static int canonicalLocked(int id) {
        int current = id;
        for (int hop = 0; hop < 4; hop++) {
            if (current < 0 || current >= ALIAS.size()) return id;
            int next = ALIAS.get(current)
                .intValue();
            if (next == current) return current;
            current = next;
        }
        return current;
    }

    /** 这个编号是不是「和别人同身份」的重复条目（搜索列表里不单独展示）。 */
    public static boolean isAlias(int id) {
        synchronized (LOCK) {
            return id >= 0 && id < ALIAS.size()
                && ALIAS.get(id)
                    .intValue() != id;
        }
    }

    /** 身份规整并掉了多少条重复（诊断用）。 */
    public static int mergedEntries() {
        synchronized (LOCK) {
            return mergedEntries;
        }
    }

    /** 合并例子的可读文本（诊断用：日志里直接写清楚谁并到了谁）。 */
    public static String mergedExampleText() {
        synchronized (mergedExamples) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < mergedExamples.size(); i++) {
                if (i > 0) builder.append("；");
                builder.append(mergedExamples.get(i));
            }
            return builder.toString();
        }
    }

    /**
     * 这个物品像不像「非消耗品」（可反复使用的那种）。
     *
     * <p>
     * 为什么要在规划里区别对待：GT 里一大类东西是<b>当工具用</b>的 —— 可编程电路、
     * 各种模具、常驻工具、透镜 —— 它们在配方里出现，但做完还在你手上。规划要是把它们
     * 当普通材料算，就会得到「需要 576 个可编程电路」「做 512 次螺丝刀」这种荒唐结果，
     * 玩家还得自己一件件去标「当作原始材料」。
     *
     * <p>
     * 判据尽量保守，只认确定的那几类：
     * <ul>
     * <li>GT 的可编程电路（{@code gt.integrated_circuit}）—— 机器里那个是配置槽，不消耗；</li>
     * <li>GT 的常驻工具（{@code gt.metatool.01}）—— 有耐久、能修，不是一次性的；</li>
     * <li>名字里带 Mold / 模具 的（GT 的模具是反复用的）；</li>
     * <li>名字里带 Lens / 透镜 的。</li>
     * </ul>
     * <b>一次性工具必须排除</b>：它们的名字里带「一次性」，是设计上就要消耗的
     * （所以一份计划里出现「4570 个一次性锻造锤」是对的，不是 bug）。
     *
     * <p>
     * 认错了也没关系：界面上每一行都能改成「还是按消耗算」，反过来也能手动标成非消耗品。
     */
    private static boolean looksLikeCatalyst(String key, String name) {
        if (name.contains("一次性")) return false;
        if (key.startsWith("gregtech:gt.integrated_circuit")) return true;
        if (key.startsWith("gregtech:gt.metatool.01")) return true;
        if (name.contains("Mold") || name.contains("模具")) return true;
        if (name.contains("Lens") || name.contains("透镜")) return true;
        return false;
    }

    /**
     * 这个物品是不是「非消耗品」。
     *
     * <p>
     * 规划在索引线程上跑，所以这里读的是建目录时就定好的布尔表 ——
     * 不去现场问物品要名字（那要碰语言表和物品对象，不能在别的线程做）。
     */
    public static boolean isCatalyst(int id) {
        synchronized (LOCK) {
            int real = canonicalLocked(id);
            return real >= 0 && real < CATALYST.size() && CATALYST.get(real);
        }
    }

    // ==================================================================
    // 读取
    // ==================================================================

    public static ItemStack stackOf(int id) {
        synchronized (LOCK) {
            int real = canonicalLocked(id);
            return real < 0 || real >= STACKS.size() ? null : STACKS.get(real);
        }
    }

    /**
     * 不做身份规整的取栈。
     *
     * <p>
     * 只给索引缓存写盘用：重复条目（同一种流体的另一套显示物品）要按<b>它自己那个物品</b>
     * 记下来，回读时才能原样重建出来。别的调用方一律用 {@link #stackOf(int)}，
     * 那个会把重复条目的号指回正式编号。
     */
    static ItemStack rawStackOf(int id) {
        synchronized (LOCK) {
            return id < 0 || id >= STACKS.size() ? null : STACKS.get(id);
        }
    }

    public static String nameOf(int id) {
        synchronized (LOCK) {
            int real = canonicalLocked(id);
            return real < 0 || real >= NAMES.size() ? "" : NAMES.get(real);
        }
    }

    /** 物品的稳定键（{@code registryName@meta}），编号换了它也不变。 */
    public static String keyOfId(int id) {
        synchronized (LOCK) {
            int real = canonicalLocked(id);
            return real < 0 || real >= KEYS.size() ? "" : KEYS.get(real);
        }
    }

    /**
     * 用稳定键换当前会话的物品编号；没有这个物品时返回 -1。
     *
     * <p>
     * 缓存和界面存档里存的都是键（编号每个会话会重排），用的时候才换回编号。
     */
    public static int idOfKey(String key) {
        if (key == null || key.isEmpty()) return -1;
        synchronized (LOCK) {
            Integer existing = BY_KEY.get(key);
            return existing == null ? -1 : existing;
        }
    }

    public static String modOf(int id) {
        synchronized (LOCK) {
            int real = canonicalLocked(id);
            return real < 0 || real >= MODS.size() ? "" : MODS.get(real);
        }
    }

    /** 模组的人类可读名字（{@code @gtnh} 这种搜索命中时显示用）。 */
    public static String modNameOf(String modId) {
        if (modId == null || modId.isEmpty()) return "";
        try {
            ModContainer container = Loader.instance()
                .getIndexedModList()
                .get(modId);
            if (container != null) return container.getName();
        } catch (Throwable ignored) {
            // Loader 没就绪时退回 modId 就够了
        }
        return modId;
    }

    public static int size() {
        synchronized (LOCK) {
            return STACKS.size();
        }
    }

    /**
     * 这个物品是不是「流体显示物品」（配方里的流体）。
     *
     * <p>
     * 判据是它带着一个 FluidStack 的 NBT —— 界面据此把数量写成 mB 而不是「个」。
     * 一个方块的流体需求写成「64 个」，玩家会去准备 64 个单元。
     */
    /** GTNH 流体显示物品里的流体内部名（没有就返回 null）。 */
    private static String fluidMaterialName(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return null;
        try {
            net.minecraft.nbt.NBTTagCompound tag = stack.getTagCompound();
            if (!tag.hasKey("mFluidMaterialName")) return null;
            String name = tag.getString("mFluidMaterialName");
            return name == null || name.isEmpty() ? null : name;
        } catch (Throwable t) {
            return null;
        }
    }

    /** GTNH 流体显示物品上写的用量（mB）；没有就返回 0。 */
    public static long fluidDisplayAmount(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return 0L;
        try {
            net.minecraft.nbt.NBTTagCompound tag = stack.getTagCompound();
            if (!tag.hasKey("mFluidDisplayAmount")) return 0L;
            long amount = tag.getLong("mFluidDisplayAmount");
            return amount > 0 ? amount : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static boolean isFluidItem(int id) {
        if (id < 0) return false;

        // ★ 目录里存的身份键就是最权威的答案：建索引时它是拿真栈（带 NBT）算出来的
        // 「fluid:显示名」。缓存回读出来的栈没有 NBT，GT 那套显示物品光看栈认不出来，
        // 结果一批流体在页面上变成「需要 64 个熔融焊锡」——数量按个数算，还永远报缺。
        String key = keyOfId(id);
        if (key != null && key.startsWith(FLUID_KEY_PREFIX)) return true;

        ItemStack stack = stackOf(id);
        if (stack == null || stack.getItem() == null) return false;

        // 判据一（最可靠）：两套流体显示物品 —— GT 的（NBT 上带 mFluid* 键）
        // 和 NEI 的（注册名 neiFluidDisplay，流体写在 damage 上）。
        // Forge 那套标准解析认不出这两种，所以必须先看这里。
        if (isFluidDisplay(stack)) return true;

        // 判据二：标准的 FluidStack 的 NBT（GT 的流体单元是这种）
        try {
            if (stack.hasTagCompound()) {
                net.minecraftforge.fluids.FluidStack fluid = net.minecraftforge.fluids.FluidStack
                    .loadFluidStackFromNBT(stack.getTagCompound());
                if (fluid != null && fluid.amount > 0) return true;
            }
        } catch (Throwable t) {
            // 落到判据三
        }

        // 判据三：名字能对上仓库里的某种流体（NEI 那套显示物品走的是这条）。
        // 两条都要有：只认 NBT 的话，NBT 结构不同的伪物品会被漏掉，
        // 而漏掉的后果就是「流体的量按个数显示」。
        //
        // ★ 这里**不认**「按 damage 猜出来的流体身份」：damage 不是流体身份
        // （GT 拿它当材质序号），猜出来的往往是另一种流体 ——
        // 拿它当判据，任何 damage 撞上某个流体 id 的物品都会被当成那种流体。
        return fluidStockByDisplayName(normName(displayName(stack))) > 0L;
    }

    /**
     * 库存数据到底能不能用。
     *
     * <p>
     * 共享存储的快照是服务器推给客户端的，<b>刚进世界那几秒它还是空的</b> ——
     * 那段时间里网页算出来的库存全是 0，于是所有材料都报「还缺」，玩家看到的就是
     * 「我明明有，它说缺」。页面上必须把这种状态说出来，而不是拿一个自己都还不知道的
     * 答案去下结论。
     *
     * @return 客户端这份共享存储快照有没有到（到了才敢说「缺」）
     */
    public static boolean stockReady() {
        try {
            return ClientStorageCache.isReady();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 库存快照里有多少个条目（诊断用：共享背包快照有没有拉下来看这个）。 */
    public static int stockSize() {
        return stock.size();
    }

    // ==================================================================
    // 诊断（{@code /api/stock}）
    //
    // 「仓库里明明有、规划却说缺」的排查全靠这几个方法：它把两边的身份原样摆出来 ——
    // 左边是共享存储里存的名字/注册名/数量，右边是目录条目的名字/身份键/现读显示名。
    // 光看「缺多少」永远看不出是名字对不上还是数量真没有。
    // ==================================================================

    /** 共享存储里的流体种数。 */
    public static int fluidTableSize() {
        return fluidStock.size();
    }

    /** 目录条目的「栈键」（{@code 注册名@damage}），诊断用。 */
    public static String stackKeyOf(int id) {
        return registryKey(stackOf(id));
    }

    /** 拿缓存回读出来的栈<b>现读</b>一遍显示名（和目录里存的名字可能不一样，诊断用）。 */
    public static String freshNameOf(int id) {
        return displayName(stackOf(id));
    }

    /** 这个条目认出来的流体注册名（诊断用）。 */
    public static String fluidRegistryOfId(int id) {
        return fluidRegistryOf(stackOf(id));
    }

    /** 共享存储的流体表：每行 { 显示名, 注册名, 数量 }（照原始条目给，不做归并）。 */
    public static List<String[]> fluidRows(String query, int limit) {
        String needle = normName(query);
        List<String[]> rows = new ArrayList<>();
        try {
            if (!ClientStorageCache.isReady()) return rows;
            for (StorageViewEntry entry : ClientStorageCache.fluids()) {
                String name = entry.getDisplayName() == null ? "" : entry.getDisplayName();
                String registry = entry.getRegistryName() == null ? "" : entry.getRegistryName();
                if (!needle.isEmpty() && !normName(name).contains(needle) && !normName(registry).contains(needle)) {
                    continue;
                }
                rows.add(new String[] { name, registry, String.valueOf(entry.getAmount()) });
                if (rows.size() >= limit) break;
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：读流体表失败（诊断接口）", t);
        }
        return rows;
    }

    /** 共享存储的物品表：每行 { 栈键, 数量, 目录 id, 目录名 }（按数量从多到少）。 */
    public static List<String[]> itemRows(String query, int limit) {
        String needle = normName(query);
        List<long[]> entries = new ArrayList<>(stock.size());
        for (Map.Entry<Long, Long> entry : stock.entrySet()) {
            entries.add(
                new long[] { entry.getKey()
                    .longValue(),
                    entry.getValue()
                        .longValue() });
        }
        Collections.sort(entries, (a, b) -> Long.compare(b[1], a[1]));

        List<String[]> rows = new ArrayList<>();
        for (long[] entry : entries) {
            int itemId = (int) (entry[0] >>> 32);
            int damage = (int) entry[0];
            Item raw = Item.getItemById(itemId);
            String key = raw == null ? ("<id" + itemId + ">") : registryKey(new ItemStack(raw, 1, damage));
            if (!needle.isEmpty() && !key.contains(needle)) continue;
            int catalogId = raw == null ? -1 : idOfKey(keyOf(new ItemStack(raw, 1, damage)));
            rows.add(
                new String[] { key, String.valueOf(entry[1]), String.valueOf(catalogId),
                    catalogId < 0 ? "" : nameOf(catalogId) });
            if (rows.size() >= limit) break;
        }
        return rows;
    }

    /** 库存里有多少这个物品（共享背包 + 玩家背包）。 */
    /**
     * 流体的库存（显示名 -> 有多少 mB）。
     *
     * <p>
     * 流体在配方里是以「伪物品」出现的（GT 给每种流体造一个显示用 ItemStack），
     * 而共享存储里的流体存在<b>流体页</b>、身份是<b>流体本身</b>。两边本来就是两套身份，
     * 所以流体的需求以前<b>永远匹配不上库存</b> —— 页面上就会说「还缺」，
     * 哪怕仓库里明明有一整罐。这里按<b>显示名</b>把两边对上：
     * ofFluid 造的显示物品和配方里的伪物品走的是同一套机制，名字天然一致。
     */
    /**
     * 前缀匹配时允许的尾巴：空，或者纯数量/单位（如 "(144l)"、"x144"、" 144 升"）。
     *
     * <p>
     * 不允许出现单词 —— 「Molten Borosilicate Glass Cell」是个真物品（单元），
     * 它同样以流体名开头，放行的话它的库存会显示成流体的 mB 数。
     */
    private static final java.util.regex.Pattern AMOUNT_TAIL = java.util.regex.Pattern.compile(
        "^[\\s(（\\[【:：x×*\\-–—]*[\\d,.]*\\s*(l|ml|mb|b|升|liters?|litres?)?[\\s)）\\]】]*$",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 归一化：小写 + 去首尾空白（两边名字的写法差异只有这些）。 */
    private static final Map<String, Long> fluidStock = new HashMap<>();

    /** 目录里流体身份键的前缀（{@code fluid:显示名}）。 */
    private static final String FLUID_KEY_PREFIX = "fluid:";

    /** 最近的流体匹配失败（限流打印，便于照着实测字符串改规则）。 */
    private static final java.util.Set<String> fluidMissLogged = new java.util.HashSet<>();

    private static void logFluidMiss(String name) {
        if (name == null || name.isEmpty()) return;
        synchronized (fluidMissLogged) {
            if (fluidMissLogged.size() >= 40 || !fluidMissLogged.add(name)) return;
        }
        StringBuilder keys = new StringBuilder();
        int n = 0;
        for (String k : fluidStock.keySet()) {
            if (n++ >= 6) break;
            keys.append('「')
                .append(k)
                .append('」');
        }
        FutaGtnhMod.LOG.info("网页配方：这个物品名没匹配上任何流体 —— 「{}」；现有流体键：{}", name, keys.toString());
    }

    /** 物品名/流体名的归一化（大小写与首尾空白不影响匹配）。 */
    private static String normName(String name) {
        return name == null ? ""
            : name.trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    public static long stockOf(int id) {
        // ★ 必须走下面那个重载，不要在这里再写一份查找。
        // 流体的匹配（配方里的伪物品 -> 仓库里的流体）加在那边；这里原来有一份内联实现，
        // 结果「加了流体匹配却永远不生效」——接口查库存走的正是这个方法。
        //
        // ★★ 流体先按<b>目录里存的身份键</b>查，而不是拿重建出来的栈现读显示名。
        //
        // 这个区别就是玩家报的「仓库里明明有 426k 过硫酸钠，规划却说缺 216500L」：
        // 目录缓存里流体条目只存了「物品 + damage」，回读出来的栈没有 NBT，
        // 而 GT 那套流体显示物品的名字恰恰来自 NBT —— 现读显示名读出来的是别的字符串，
        // 拿它去流体表里查必然查不到，于是「明明有」变成了「还缺」。
        // 建索引时读过一次真栈，名字就存在目录里（keyOfId 取的就是它），用它去查才对得上。
        //
        // ★★★ 名字对不上就**到此为止**，绝不能退回「现读显示名」那条路。
        //
        // 现读出来的名字可能落到<b>另一种流体</b>上：回读的栈没有 NBT，流体身份只能靠 damage 猜，
        // 而 damage 不是流体身份（GT 拿它当材质序号）。实测玩家报的那一例：
        // 熔融不锈钢的显示物品 @619 现读成「稀硫酸」，而仓库里正好有 16000 稀硫酸 ——
        // 页面于是说「熔融不锈钢有库存 16000」，玩家明明一点都不剩。
        // 宁可报 0（玩家看到「缺」），也不能报另一种流体的数字：那会让整份计划的用量算错。
        if (id < 0) return 0L;
        String key = keyOfId(id);
        if (key != null && key.startsWith(FLUID_KEY_PREFIX)) {
            return fluidStockByDisplayName(key.substring(FLUID_KEY_PREFIX.length()));
        }
        return stockOf(stackOf(id));
    }

    /**
     * 按显示名在流体表里找（先精确、再前缀）。
     *
     * <p>
     * 前缀那一步是给 NEI 那套显示物品留的：它的显示名常常带用量后缀
     * （实测有「Molten Borosilicate Glass (144L)」这种），精确比永远对不上；
     * 而「某个物品名以某个流体名开头」在 GTNH 里基本就是「这是那个流体的显示物品」。
     * 尾巴只允许「单位/数量」，出现单词就不算 —— 否则「Molten Borosilicate Glass Cell」
     * （单元，一个真物品）会因为以流体名开头而被当成那个流体。
     */
    private static long fluidStockByDisplayName(String displayName) {
        String name = normName(displayName);
        if (name.isEmpty() || fluidStock.isEmpty()) return 0L;

        Long exact = fluidStock.get(name);
        if (exact != null) return exact.longValue();

        for (Map.Entry<String, Long> fe : fluidStock.entrySet()) {
            String fluidName = fe.getKey();
            if (fluidName.isEmpty() || !name.startsWith(fluidName)) continue;
            String tail = name.substring(fluidName.length())
                .trim();
            if (AMOUNT_TAIL.matcher(tail)
                .matches()) {
                return fe.getValue()
                    .longValue();
            }
        }
        return 0L;
    }

    /**
     * <b>只给诊断用</b>：按 NBT 材质名 / damage 猜这个流体显示物品是哪种流体。
     *
     * <p>
     * ★ 它的结果<b>不参与库存匹配</b>，只出现在 {@code /api/stock} 的探针里帮人看问题。
     * 原因是 damage 根本不是流体身份：GT 拿它当材质序号，同一个 damage 在不同上下文里
     * 指的是不同的东西。实测「熔融不锈钢」的显示物品 @619 用 damage 猜出来是「稀硫酸」，
     * 而仓库里正好有 16000 稀硫酸 —— 早期版本就是拿这个猜法去查库存，
     * 于是页面报「熔融不锈钢有 16000」，玩家一点都不剩。
     */
    private static String fluidRegistryOf(ItemStack stack) {
        try {
            String material = fluidMaterialName(stack);
            if (material != null) {
                String registry = registryNameOfFluid(material);
                if (registry != null) return registry;
            }
            int damage = stack.getItemDamage();
            if (damage > 0) {
                String registry = registryNameOfFluid(Integer.valueOf(damage));
                if (registry != null) return registry;
            }
        } catch (Throwable t) {
            // 猜不出来就算了
        }
        return null;
    }

    /** {@code key} 既可以是流体的注册名，也可以是流体的数字 id。 */
    private static String registryNameOfFluid(Object key) {
        net.minecraftforge.fluids.Fluid fluid = key instanceof Integer
            ? net.minecraftforge.fluids.FluidRegistry.getFluid(((Integer) key).intValue())
            : net.minecraftforge.fluids.FluidRegistry.getFluid(String.valueOf(key));
        if (fluid == null) return null;
        String name = net.minecraftforge.fluids.FluidRegistry.getFluidName(fluid);
        return name == null || name.isEmpty() ? null : normName(name);
    }

    /** 同一个物品 id 的「通配 meta」键（原版 32767 = 任意 meta 都匹配）。 */
    private static long wildcardKey(ItemStack stack) {
        return ((long) Item.getIdFromItem(stack.getItem()) << 32) | 32767L;
    }

    /**
     * 库存里有多少这个物品（按栈本身查，配方里的任意候选都能问）。
     *
     * <p>
     * 流体的匹配只按<b>名字</b>（先精确、再「名字 + 用量后缀」）：这是唯一一个两边共有、
     * 又不会指错东西的身份。按 damage 猜出来的流体身份不用 —— 它不是流体身份，
     * 会把别的流体的库存算到这件东西头上（见 {@link #fluidRegistryOf} 的说明）。
     */
    public static long stockOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return 0L;

        Long value = stock.get(stockKey(stack));
        if (value != null) return value.longValue();

        if (!fluidStock.isEmpty()) {
            String name = normName(displayName(stack));
            long byName = fluidStockByDisplayName(name);
            if (byName > 0L) return byName;

            // 还是没对上：把这个名字记一次（限流），下一次就能照着实测的字符串改匹配规则，
            // 而不是继续猜
            logFluidMiss(name);
        }
        return 0L;
    }

    // ==================================================================
    // 库存快照（客户端线程）
    // ==================================================================

    /**
     * 重新拍一份「我现在有多少东西」的快照。
     *
     * <p>
     * 两个来源：共享背包的客户端缓存（服务端增量同步过来的）和玩家自己的背包。
     * 都不含容器（箱子/背包）里的东西 —— 那些要玩家自己走过去开，算进来反而会误导。
     */
    public static void refreshStock() {
        Map<Long, Long> map = new HashMap<>(8192);

        try {
            if (ClientStorageCache.isReady()) {
                for (StorageViewEntry entry : ClientStorageCache.items()) {
                    ItemStack display = entry.getDisplay();
                    if (display == null || display.getItem() == null) continue;
                    add(map, stockKey(display), entry.getAmount());
                }
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：读取共享背包快照失败（本次按空处理）", t);
        }

        try {
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null && player.inventory != null) {
                ItemStack[] main = player.inventory.mainInventory;
                for (int i = 0; i < main.length; i++) {
                    ItemStack stack = main[i];
                    if (stack == null || stack.getItem() == null) continue;
                    add(map, stockKey(stack), stack.stackSize);
                }
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：读取玩家背包快照失败（本次按空处理）", t);
        }

        // 流体单独一张表：它们和物品是两套身份，混在一张表里只会互相干扰。
        // 键只取<b>显示名</b>：这是两边唯一共有、又不会指错东西的身份
        // （注册名那把钥匙试过，靠 damage 猜出来的流体身份会指到别的流体上，见 fluidRegistryOf）。
        Map<String, Long> fluids = new HashMap<>(1024);
        try {
            if (ClientStorageCache.isReady()) {
                for (StorageViewEntry entry : ClientStorageCache.fluids()) {
                    String name = normName(entry.getDisplayName());
                    if (name.isEmpty()) continue;
                    Long old = fluids.get(name);
                    fluids.put(name, Long.valueOf((old == null ? 0L : old.longValue()) + entry.getAmount()));
                }
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：读取共享背包的流体快照失败（本次按空处理）", t);
        }
        fluidStock.clear();
        fluidStock.putAll(fluids);

        stock = map;

        // 诊断：快照里有、但按现在的键查不回来的条目数。
        // 「共享存储里明明有、页面却报缺」时，这个数就是那条线索 —— 不为 0 说明
        // 两边的键（物品 id + damage）对不上。每次刷新只打一行。
        try {
            int misses = 0;
            int wildcards = 0;
            for (Map.Entry<Long, Long> entry : map.entrySet()) {
                long key = entry.getKey()
                    .longValue();
                int itemId = (int) (key >>> 32);
                int damage = (int) key;
                if (damage == 32767) wildcards++;
                // 这条库存的身份（物品 id + damage）在目录里存在吗？
                // 不存在的话，页面上任何物品都查不到它 —— 正是「明明有却报缺」的形态
                net.minecraft.item.Item raw = net.minecraft.item.Item.getItemById(itemId);
                if (raw == null) {
                    misses++;
                    continue;
                }
                String identity = keyOf(new ItemStack(raw, 1, damage));
                if (idOfKey(identity) < 0) misses++;
            }
            // 诊断要把流体的名字也打出来：两边是按显示名匹配的，名字对不上时
            // 光看「收到几种」什么都说明不了（这个坑已经踩过一次）。
            StringBuilder sample = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Long> fe : fluids.entrySet()) {
                if (shown++ >= 3) break;
                sample.append('「')
                    .append(fe.getKey())
                    .append('」')
                    .append(fe.getValue())
                    .append(' ');
            }
            FutaGtnhMod.LOG.info(
                "网页配方：库存快照 {} 条（其中通配 meta {} 条，查不回来的 {} 条）+ 流体 {} 种 {}",
                Integer.valueOf(map.size()),
                Integer.valueOf(wildcards),
                Integer.valueOf(misses),
                Integer.valueOf(fluids.size()),
                sample.toString());
        } catch (Throwable ignored) {
            // 只是日志
        }

    }

    private static void add(Map<Long, Long> map, long key, long amount) {
        if (amount <= 0L) return;
        Long old = map.get(key);
        map.put(key, old == null ? amount : old + amount);
    }

    // ==================================================================
    // 共享背包快照的按需拉取
    //
    // 全量快照本来只在打开共享终端界面时下发，之后的增量也只发给开着界面的人。
    // 手机网页要在「没开界面」的情况下算还缺多少，所以这里主动要一份 ——
    // 只在网页真的问到库存时拉，并且限流（见 PacketStorageSnapshotRequest 的注释）。
    // ==================================================================

    /** HTTP 线程置位，客户端 tick 里消费。 */
    private static volatile boolean wantSnapshot;
    private static volatile long lastSnapshotRequest;

    /** 由 HTTP 层调用：觉得库存数据可能太旧了。 */
    public static void requestFreshStock() {
        if (System.currentTimeMillis() - lastSnapshotRequest > SNAPSHOT_INTERVAL_MILLIS) {
            wantSnapshot = true;
        }
    }

    /** 在客户端 tick 里调用（必须主线程：发的是网络包）。 */
    public static void tickStockRequest() {
        if (!wantSnapshot) return;
        wantSnapshot = false;
        if (System.currentTimeMillis() - lastSnapshotRequest <= SNAPSHOT_INTERVAL_MILLIS) return;
        lastSnapshotRequest = System.currentTimeMillis();

        if (Minecraft.getMinecraft().thePlayer == null) return;
        try {
            com.futa_gtnh.network.NetworkHandler.INSTANCE
                .sendToServer(new com.futa_gtnh.network.PacketStorageSnapshotRequest());
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：请求共享背包快照失败（本次按已有数据算）", t);
        }
    }

    // ==================================================================
    // 搜索
    // ==================================================================

    /**
     * 搜索物品 id。
     *
     * <p>
     * 语法和 NEI/共享终端保持一致的部分：空格分隔是「与」，竖线是「或」，
     * {@code @模组} 按模组匹配。物品名走「小写名 + 拼音后缀」的子串匹配，
     * 装了 NotEnoughCharacters 时额外交给它做模糊音 —— 手机虽然能打中文，
     * 但用拼音缩写找东西的习惯是改不掉的。
     *
     * @param out 命中的 id 会追加进来（调用方负责清空）
     * @return 命中总数（可能远大于 {@code out} 里的条数）
     */
    public static int search(String query, int limit, int offset, List<Integer> out) {
        if (!catalogReady) return 0;

        String[] words = query == null ? new String[0]
            : query.trim()
                .toLowerCase(Locale.ROOT)
                .split("\\s+");
        boolean empty = words.length == 0 || (words.length == 1 && words[0].isEmpty());

        int total = 0;
        synchronized (LOCK) {
            for (int id = 0; id < STACKS.size(); id++) {
                // 身份重复的条目（同一种流体的另一套显示物品、注册表里的重复栈）不单独出现：
                // 它们和正式那条同名同图，列出来只会让人点中「没有配方」的那一个 ——
                // 「熔融焊锡有两三条、我选的那条没配方」就是这么来的
                if (ALIAS.get(id)
                    .intValue() != id) continue;
                if (!empty && !matches(id, words)) continue;
                if (total >= offset && out.size() < limit) out.add(id);
                total++;
            }
        }
        return total;
    }

    private static boolean matches(int id, String[] words) {
        for (int w = 0; w < words.length; w++) {
            if (!matchesWord(id, words[w])) return false;
        }
        return true;
    }

    private static boolean matchesWord(int id, String word) {
        String[] alternatives = word.split("\\|");
        for (int a = 0; a < alternatives.length; a++) {
            String term = alternatives[a];
            if (term.isEmpty()) continue;

            if (term.charAt(0) == '@') {
                String needle = term.substring(1);
                if (needle.isEmpty()) continue;
                if (MODS.get(id)
                    .toLowerCase(Locale.ROOT)
                    .contains(needle)
                    || modNameOf(MODS.get(id)).toLowerCase(Locale.ROOT)
                        .contains(needle)) {
                    return true;
                }
                continue;
            }

            if (SEARCH.get(id)
                .contains(term)) return true;
            if (NecharBridge.isAvailable() && NecharBridge.matches(NAMES.get(id), term)) return true;
        }
        return false;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 物品的身份键。
     *
     * <p>
     * ★ 流体显示物品的身份是<b>「哪一种流体」</b>，不是「哪个物品」。
     *
     * <p>
     * GTNH 里同一种流体有好几套显示物品，模组之间各造各的：
     * <ul>
     * <li>GT 的 {@code gregtech:gt.GregTech_FluidDisplay@N} —— 有材质的流体在 NBT 里带
     * {@code mFluidMaterialName}，而水、岩浆、冷却液这种没有材质的流体只有
     * {@code mFluidDisplayAmount}；</li>
     * <li>NEI 的 {@code NotEnoughItems:neiFluidDisplay@N} —— 靠 damage 认流体，NBT 里什么都没有。</li>
     * </ul>
     * 它们在玩家眼里是同一个东西（都叫「熔融焊锡」）。历史上两种错法都犯过：
     * 键里不带流体名 → 所有流体挤成一个 id，谁先索引到就归谁（格子显示成别的流体，时对时错）；
     * 键里带上具体的显示物品 → 一种流体散成好几个 id，而玩家的配方选择是按 id 存的，
     * 选了一个、规划里解析到另一个，于是「我明明选了它的合成方式，还是把它当原料」。
     *
     * <p>
     * 归一到<b>显示名</b>（而不是 GT 的材质名）：两套显示物品唯一共有的身份就是它 ——
     * NEI 那套没有材质名，GT 那套没有材质名的流体也不在少数。
     */
    public static String keyOf(ItemStack stack) {
        if (isFluidDisplay(stack)) {
            String name = displayName(stack);
            if (!name.isEmpty()) return "fluid:" + name;
            // 名字都读不出来的极端情况：退回材质名，至少不会把不同流体混成一个
            String material = fluidMaterialName(stack);
            if (material != null) return "fluid:" + material;
        }
        return registryKey(stack);
    }

    /**
     * 这是不是「流体显示物品」（GT 的和 NEI 的各一套）。
     *
     * <p>
     * GT 那套的判据是 NBT 上的 {@code mFluid*} 键，而且几个键里认出一个就算：
     * {@code mFluidMaterialName} 只有材质流体才有（水、岩浆、冷却液、氙都没有），
     * 用量为 0 时 {@code mFluidDisplayAmount} 也会缺（氦、氟那种）——
     * 只认其中一个，就会有一批流体被当成普通物品。
     *
     * <p>
     * NEI 那套只能认注册名：它的流体写在 damage 上，NBT 是空的。
     *
     * <p>
     * <b>GT 的流体单元不在这里面</b>：单元是<b>真物品</b>（能拿在手上、能装能倒），
     * NBT 是 Forge 标准的 {@code FluidName/Amount}，没有 {@code mFluid*} 键，
     * 所以不会被并进流体本体。
     */
    public static boolean isFluidDisplay(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        try {
            if (stack.hasTagCompound()) {
                net.minecraft.nbt.NBTTagCompound tag = stack.getTagCompound();
                if (tag.hasKey("mFluidMaterialName") || tag.hasKey("mFluidDisplayAmount")
                    || tag.hasKey("mFluidDisplayHeat")
                    || tag.hasKey("mFluidState")) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // NBT 读不出来就当它不是流体：一条坏数据不能把整个目录带崩
        }
        return NEI_FLUID_ITEM.equals(registryNameOf(stack));
    }

    /**
     * 「物品本身」的键（{@code 注册名@metadata}）。
     *
     * <p>
     * 和 {@link #keyOf} 的区别：这个只描述「哪个物品栈」，不含身份规整。
     * 目录缓存用它把条目找回来 —— 流体的身份键是 {@code fluid:显示名}，
     * 反推不出是哪个物品，所以缓存里两份都要存（v12 只存了身份键，
     * 回读时流体条目整批反解失败、被丢掉，重启后所有流体都没有配方）。
     */
    public static String registryKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "";
        return registryNameOf(stack) + '@' + stack.getItemDamage();
    }

    private static String registryNameOf(ItemStack stack) {
        Object registered = Item.itemRegistry.getNameForObject(stack.getItem());
        return registered != null ? registered.toString() : "id" + Item.getIdFromItem(stack.getItem());
    }

    private static long stockKey(ItemStack stack) {
        return ((long) Item.getIdFromItem(stack.getItem()) << 32) | (stack.getItemDamage() & 0xFFFFFFFFL);
    }

    private static String displayName(ItemStack stack) {
        try {
            String name = stack.getDisplayName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            // 个别模组的物品拿显示名会抛异常，一条坏数据不能把整个目录带崩
            return "";
        }
    }

    private static String buildSearchText(ItemStack stack, String displayName) {
        StringBuilder builder = new StringBuilder();
        builder.append(displayName.toLowerCase(Locale.ROOT));
        Object registered = Item.itemRegistry.getNameForObject(stack.getItem());
        if (registered != null) {
            builder.append(' ')
                .append(
                    registered.toString()
                        .toLowerCase(Locale.ROOT));
        }
        synchronized (PINYIN_LOCK) {
            builder.append(Pinyin.searchSuffix(displayName));
        }
        return builder.toString();
    }

    private static String modIdOf(ItemStack stack) {
        Object registered = Item.itemRegistry.getNameForObject(stack.getItem());
        if (registered == null) return "";
        String name = registered.toString();
        int colon = name.indexOf(':');
        return colon <= 0 ? name : name.substring(0, colon);
    }
}
