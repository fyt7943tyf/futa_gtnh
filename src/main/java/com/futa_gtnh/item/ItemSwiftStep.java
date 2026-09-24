package com.futa_gtnh.item;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import baubles.api.BaubleType;
import baubles.api.BaublesApi;
import baubles.api.IBauble;
import baubles.common.container.InventoryBaubles;

/**
 * 迅步：戴在身上同时提高<b>飞行</b>和<b>移动</b>速度，右键打开界面自己调倍率。
 *
 * <p>
 * 两项速度的<b>实现机制完全不同</b>，因为 1.7.10 里它们本来就走的不是同一条路：
 *
 * <ul>
 * <li><b>移动速度</b>走「移动速度属性」。原版
 * {@code EntityPlayer.onLivingUpdate} 每 tick 在<b>服务端</b>把
 * {@code capabilities.getWalkSpeed()} 写成该属性的基值，再用属性值去
 * {@code setAIMoveSpeed}。所以这里只要挂一个
 * {@link AttributeModifier} 上去就行 —— 公开 API、服务端权威、还会自动同步给客户端。</li>
 * <li><b>飞行速度</b>没有属性可用，就是 {@code capabilities.flySpeed} 这个私有字段，
 * 而它的 setter 标了 {@code @SideOnly(CLIENT)}（服务端那份类里根本没这个方法）。
 * 所以只能在客户端改，见 {@code SwiftStepClientHandler}。
 * <b>不能</b>用反射去写服务端那个私有字段：生产环境一混淆，字段名就变成 SRG 名，
 * 写死的 {@code "flySpeed"} 会直接找不到。</li>
 * </ul>
 *
 * <p>
 * 倍率存在<b>物品自己的 NBT</b> 里（服务端写入、客户端读取），
 * 所以每个迅步可以各调各的，而且跟着物品走。
 */
public class ItemSwiftStep extends Item implements IBauble {

    public static final String NAME = "swift_step";

    private static final String TAG_FLIGHT = "futa_gtnh.swift_step.flight";
    private static final String TAG_WALK = "futa_gtnh.swift_step.walk";
    private static final String TAG_LIGHT = "futa_gtnh.swift_step.light";

    /**
     * 照明亮度的默认值：<b>0 = 不亮</b>。
     *
     * <p>
     * 默认关着，是因为它会在客户端脚下放一个隐形的发光方块 ——
     * 玩家没要求就别替他把世界点亮。
     */
    public static final int DEFAULT_LIGHT = 0;

    /** 照明亮度上限 = 原版光照上限（火把是 14）。 */
    public static final int MAX_LIGHT = 15;

    /** 原版火把的亮度，界面里当参照用。 */
    public static final int TORCH_LIGHT = 14;

    /** 没调过时的默认倍率（1.0 = 原版速度）。 */
    public static final float DEFAULT_MULTIPLIER = 1.0F;

    /** 下限。低于 1 就是减速了，没意义。 */
    public static final float MIN_MULTIPLIER = 1.0F;

    /** 原版飞行速度。飞行倍率是相对它的。 */
    public static final float VANILLA_FLY_SPEED = 0.05F;

    /** 原版移动速度（= {@code PlayerCapabilities.walkSpeed} 的默认值）。 */
    public static final float VANILLA_WALK_SPEED = 0.1F;

    /**
     * 飞行时每 tick 的水平阻力。
     *
     * <p>
     * 取自 {@code EntityLivingBase.moveEntityWithHeading}：玩家在飞行时那两个分支
     * （水中、岩浆）都会被 {@code !capabilities.isFlying} 挡掉，走的是最后那条
     * {@code f2 = 0.91F} 的路，末尾 {@code motionX *= 0.91; motionZ *= 0.91}。
     */
    private static final double FLY_HORIZONTAL_DRAG = 0.91D;

    /**
     * 飞行终端速度相对 {@code flySpeed} 的倍数 = {@code 1 / (1 - 0.91) ≈ 11.11}。
     *
     * <p>
     * 水平速度是个等比级数：每 tick 先加速 {@code flySpeed}，再乘 0.91，
     * 收敛到 {@code flySpeed / 0.09}。所以
     * <b>每 tick 位移 = 倍率 × 0.05 × 11.11 = 倍率 × 0.5556 格</b>。
     *
     * <p>
     * 原版 1 倍代进去是 0.556 格/tick = 11.1 格/秒 —— 正好对上创造模式飞行的体感，
     * 可以用来校验这个系数没算错。
     */
    public static final double FLY_TERMINAL_FACTOR = 1.0D / (1.0D - FLY_HORIZONTAL_DRAG);

    /**
     * 服务端允许的每 tick 位移上限（格）。
     *
     * <p>
     * 来自 {@code NetHandlerPlayServer.processPlayer}：
     *
     * <pre>
     * double d10 = d7*d7 + d8*d8 + d9*d9;   // 每轴取 max(|位移|, |motion|)
     * if (d10 &gt; 100.0D &amp;&amp; (!serverController.isSinglePlayer()
     *                      || !serverController.getServerOwner().equals(playerName))) {
     *     logger.warn("... moved too quickly! ...");
     *     this.setPlayerLocation(lastPosX, lastPosY, lastPosZ, ...);   // 拉回原地
     *     return;
     * }
     * </pre>
     *
     * {@code d10} 就是位移的平方和，所以 {@code > 100} 等价于
     * <b>每 tick 移动超过 10 格</b>（不分方向，斜着飞也一样）。
     *
     * <p>
     * <b>注意那个 {@code isSinglePlayer} 条件：单人存档里只要你就是房主，
     * 整条检查会被跳过。</b>这就是「自己开档感觉不出来、一连服务器就失效」的原因。
     */
    private static final double SERVER_MAX_BLOCKS_PER_TICK = 10.0D;

    /**
     * 专用服务器上不会被拉回的最大飞行倍率。
     *
     * <p>
     * 解 {@code 倍率 × 0.05 × 11.11 ≤ 10} 得 {@code 倍率 ≤ 18.0}。
     * 这是<b>物理上限，不是偏好</b>：超过它的飞行速度不是「快一点但有点风险」，
     * 而是<b>每 tick 都被服务端拉回原地，等于完全没加速</b>。
     *
     * <p>
     * 所以默认上限取的是比它低一点的 16（留出垂直分量的余量）。
     */
    public static float serverSafeFlyMultiplier() {
        return (float) (SERVER_MAX_BLOCKS_PER_TICK / (VANILLA_FLY_SPEED * FLY_TERMINAL_FACTOR));
    }

    /**
     * 原版空中前进的加速度（{@code EntityLivingBase.jumpMovementFactor} 的默认值）。
     *
     * <p>
     * <b>为什么空中要单独处理：</b>{@code EntityLivingBase.moveEntityWithHeading}
     * 里地面和空中用的是两个完全不同的量 ——
     *
     * <pre>
     *   地面：f4 = getAIMoveSpeed() * 0.16277136 / (阻力³)
     *   空中：f4 = jumpMovementFactor
     * </pre>
     *
     * 地面的那个来自「移动速度属性」，所以 {@link #applyWalkSpeedModifier} 一放大，
     * 走路就快了。但 {@code jumpMovementFactor} 是 {@code EntityLivingBase} 上的一个
     * 常量字段，和属性没有任何关系 —— 于是会出现
     * <b>「走着 5 倍，一跳起来就掉回原版速度」</b>。
     *
     * <p>
     * 数值上两者本来是配平的：原版地面终端速度
     * {@code 0.1 * 1.0 / (1 - 0.546) ≈ 0.2203} 格/tick，空中
     * {@code 0.02 / (1 - 0.91) ≈ 0.2222}，基本相等。所以把
     * {@code jumpMovementFactor} 按同一个倍率放大，就又配平了。
     */
    public static final float VANILLA_JUMP_MOVEMENT_FACTOR = 0.02F;

    /**
     * 疾跑时原版给空中加速度的额外加成。
     *
     * <p>
     * 见 {@code EntityPlayer.onLivingUpdate}：
     * {@code if (isSprinting()) jumpMovementFactor += speedInAir * 0.3F}。
     * 我们整个覆写这个字段，所以要自己把这 30% 补回去，
     * 否则「疾跑跳」会比原版还慢。
     */
    private static final float SPRINT_AIR_BONUS = 1.3F;

    /**
     * 移动速度修饰符的固定 UUID。
     *
     * <p>
     * 必须是常量：{@code applyModifier} 是按 UUID 去重的，每次换一个 UUID 就会
     * 越叠越多层，玩家调两次速度就成了 4 倍、8 倍。用同一个 UUID
     * 才能做到「先删旧的、再挂新的」这种幂等更新。
     */
    private static final UUID WALK_MODIFIER_ID = UUID.fromString("8f3a5c1e-9b2d-4a7f-8c3e-1d5b9a7c2e40");
    private static final String WALK_MODIFIER_NAME = "futa_gtnh.swift_step.walk";

    /**
     * 修饰符运算方式：{@code 1 = 乘基值}。
     *
     * <p>
     * 1.7.10 里这是个 int 而不是枚举，取值来自
     * {@code ModifiableAttributeInstance.computeValue()}：
     * 
     * <pre>
     *   operation 0:  d0 += amount
     *   operation 1:  d1 += d0 * amount      ← 乘基值
     *   operation 2:  d1 *= (1 + amount)
     * </pre>
     * 
     * 所以传 {@code amount = 倍率 - 1} 得到的就是精确的「基值 × 倍率」。
     * 用 0 的话会变成加法（不是按比例），用 2 则会连其它修饰符一起放大。
     */
    private static final int OP_MULTIPLY_BASE = 1;

    public ItemSwiftStep() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        // 饰品不该能堆叠：戴的是「一个」，堆叠会让 NBT 里的倍率归属变得含糊
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    // ==================================================================
    // 倍率读写
    // ==================================================================

    public static float getFlightMultiplier(ItemStack stack) {
        return readMultiplier(stack, TAG_FLIGHT);
    }

    public static float getWalkMultiplier(ItemStack stack) {
        return readMultiplier(stack, TAG_WALK);
    }

    public static void setFlightMultiplier(ItemStack stack, float value) {
        writeMultiplier(stack, TAG_FLIGHT, value);
    }

    public static void setWalkMultiplier(ItemStack stack, float value) {
        writeMultiplier(stack, TAG_WALK, value);
    }

    // ==================================================================
    // 照明亮度（0 = 关）
    // ==================================================================

    /**
     * @return 这个迅步的照明亮度（0 = 不亮，1~15 是光照等级）
     */
    public static int getLightLevel(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemSwiftStep)) return DEFAULT_LIGHT;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_LIGHT)) return DEFAULT_LIGHT;
        return clampLight(tag.getInteger(TAG_LIGHT));
    }

    public static void setLightLevel(ItemStack stack, int value) {
        if (stack == null || !(stack.getItem() instanceof ItemSwiftStep)) return;
        if (stack.getTagCompound() == null) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound()
            .setInteger(TAG_LIGHT, clampLight(value));
    }

    /**
     * 夹到 0~15。
     *
     * <p>
     * 和倍率一样：客户端的数字一律不信，服务端收到包之后要再夹一次。
     * 交给光照引擎一个 16 以上的值会溢出到元数据的高位，那已经不是「亮一点」了。
     */
    public static int clampLight(int value) {
        if (value < 0) return DEFAULT_LIGHT;
        return Math.min(value, MAX_LIGHT);
    }

    /** @return 给日志/提示用的一句话，例如「14（火把）」或「关」 */
    public static String describeLight(int level) {
        if (level <= 0) return "关";
        if (level >= TORCH_LIGHT) return level + "（" + (level == TORCH_LIGHT ? "火把" : "最亮") + "）";
        return Integer.toString(level);
    }

    private static float readMultiplier(ItemStack stack, String key) {
        if (stack == null || !(stack.getItem() instanceof ItemSwiftStep)) return DEFAULT_MULTIPLIER;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(key)) return DEFAULT_MULTIPLIER;
        return clampMultiplier(tag.getFloat(key));
    }

    private static void writeMultiplier(ItemStack stack, String key, float value) {
        if (stack == null || !(stack.getItem() instanceof ItemSwiftStep)) return;
        if (stack.getTagCompound() == null) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound()
            .setFloat(key, clampMultiplier(value));
    }

    /**
     * 把倍率夹到合法范围。
     *
     * <p>
     * 上下界都要夹：客户端可以把任意浮点数塞进包里发过来，
     * 不夹的话一个改过的客户端就能把速度开到荒谬的值。
     * （本模组其它地方也是同一条原则：客户端的数字一律不信。）
     */
    public static float clampMultiplier(float value) {
        if (Float.isNaN(value)) return DEFAULT_MULTIPLIER;
        float max = maxMultiplier();
        if (value < MIN_MULTIPLIER) return MIN_MULTIPLIER;
        if (value > max) return max;
        return value;
    }

    /** @return 配置里的倍率上限。配置读失败时退回一个保守值，免得算出 NaN。 */
    public static float maxMultiplier() {
        float configured = (float) Config.swiftStepMaxMultiplier;
        if (Float.isNaN(configured) || configured < MIN_MULTIPLIER) return 16.0F;
        return configured;
    }

    // ==================================================================
    // 找身上戴着的那个
    // ==================================================================

    /**
     * @return 玩家饰品栏里第一个迅步；没戴返回 null
     *
     *         <p>
     *         只取<b>一个</b>。两个迅步不会叠乘 —— 叠乘会让「戴两个就更快」变成
     *         刻意刷数值的玩法，而且玩家也搞不清最终是几倍。
     */
    public static ItemStack findEquipped(EntityPlayer player) {
        if (player == null) return null;
        IInventory baubles = BaublesApi.getBaubles(player);
        if (baubles == null) return null;

        for (int i = 0; i < baubles.getSizeInventory(); i++) {
            ItemStack stack = baubles.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemSwiftStep) {
                return stack;
            }
        }
        return null;
    }

    // ==================================================================
    // 移动速度（服务端权威的属性修饰符）
    // ==================================================================

    /**
     * 把移动速度属性上的迅步修饰符调整成「现在该有的样子」。
     *
     * <p>
     * <b>两端都要调。</b>服务端那份是权威的（属性变化会同步给客户端）；
     * 客户端那份是为了即时生效，免得改完要等一个网络来回才感觉得到。
     * 两边用的是同一个 UUID，所以不会互相叠加成双倍。
     *
     * <p>
     * 幂等：倍率没变就什么都不做。每 tick 都会调它，
     * 不判断的话会每 tick 删一次挂一次，白白往客户端推属性同步包。
     */
    public static void applyWalkSpeedModifier(EntityPlayer player) {
        if (player == null) return;
        IAttributeInstance instance = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        if (instance == null) return;

        ItemStack charm = findEquipped(player);
        float multiplier = charm == null ? DEFAULT_MULTIPLIER : getWalkMultiplier(charm);
        boolean wanted = multiplier > DEFAULT_MULTIPLIER + 1.0E-4F;
        double amount = multiplier - 1.0D;

        AttributeModifier existing = instance.getModifier(WALK_MODIFIER_ID);
        if (existing != null && (!wanted || Math.abs(existing.getAmount() - amount) > 1.0E-6D)) {
            // 这个映射里 IAttributeInstance 只提供 removeModifier(AttributeModifier)，
            // 没有按 UUID 删的重载，所以要把查出来的实例本身传回去
            instance.removeModifier(existing);
            existing = null;
        }
        if (wanted && existing == null) {
            instance
                .applyModifier(new AttributeModifier(WALK_MODIFIER_ID, WALK_MODIFIER_NAME, amount, OP_MULTIPLY_BASE));
        }
    }

    /**
     * 让空中的前进速度和地面上的移动速度保持一致。
     *
     * <p>
     * 直接写 {@code EntityLivingBase.jumpMovementFactor} —— 那是个 <b>public 字段</b>，
     * 不需要碰任何私有成员，也不涉及 {@code capabilities} 那套只在客户端存在的 API。
     *
     * <p>
     * <b>时序是安全的，而且不需要每 tick 抢：</b>
     * {@code EntityPlayer.onLivingUpdate} 里先在第 612 行做移动、第 620 行才把
     * {@code jumpMovementFactor} 从 {@code speedInAir} 重置回来；而
     * {@code PlayerTickEvent(END)} 在那之后。所以我们写的值会一直保留到
     * <b>下一 tick 的移动</b>时被读到。这也正是「走路快、跳起来慢」的补法：
     * 地面那半边由属性负责，空中这半边由这里负责。
     *
     * <p>
     * <b>摘掉饰品不需要还原逻辑。</b>没有迅步时我们什么都不写，而原版第 620 行
     * 每 tick 都会把字段重置成 {@code speedInAir}（0.02 / 疾跑 0.026），
     * 自己就回到原样了。反过来特意去写 0.02 反而会在别的模组也调这个字段时打架。
     *
     * <p>
     * 飞行时不用担心被覆盖：{@code EntityPlayer.moveEntityWithHeading} 会在飞行的
     * 那一段临时把它换成 {@code flySpeed}，出来再换回来，我们的值不受影响。
     */
    public static void applyAirSpeedModifier(EntityPlayer player) {
        if (player == null) return;

        ItemStack charm = findEquipped(player);
        float multiplier = charm == null ? DEFAULT_MULTIPLIER : getWalkMultiplier(charm);
        // 没超速就完全不碰这个字段，让原版自己管（见上面的说明）
        if (multiplier <= DEFAULT_MULTIPLIER + 1.0E-4F) return;

        float wanted = VANILLA_JUMP_MOVEMENT_FACTOR * multiplier;
        if (player.isSprinting()) {
            wanted *= SPRINT_AIR_BONUS;
        }
        if (Math.abs(player.jumpMovementFactor - wanted) > 1.0E-5F) {
            player.jumpMovementFactor = wanted;
        }
    }

    // ==================================================================
    // IBauble
    // ==================================================================

    /**
     * {@code UNIVERSAL} 是「任何饰品槽都收」。
     *
     * <p>
     * 这不是猜的：Baubles-Expanded 的 {@code SlotBauble.isItemValid} 最后会调
     * {@code BaublesConfig.canTypeFitSlot(itemType, slotType)}，而那个方法的第一个判断就是
     * {@code if (itemType.equals("universal")) return true;} —— 物品类型是 universal
     * 就直接放行，不看槽位是什么。
     */
    @Override
    public BaubleType getBaubleType(ItemStack stack) {
        return BaubleType.UNIVERSAL;
    }

    @Override
    public void onWornTick(ItemStack stack, EntityLivingBase player) {
        // 速度的应用不在这里：飞行速度要在客户端 tick 里改 capabilities，
        // 移动速度要用 PlayerTickEvent 才会同时覆盖两端（见 ModEventHandler）
    }

    @Override
    public void onEquipped(ItemStack stack, EntityLivingBase player) {}

    @Override
    public void onUnequipped(ItemStack stack, EntityLivingBase player) {}

    @Override
    public boolean canEquip(ItemStack stack, EntityLivingBase player) {
        return true;
    }

    @Override
    public boolean canUnequip(ItemStack stack, EntityLivingBase player) {
        return true;
    }

    // ==================================================================
    // 右键
    // ==================================================================

    /**
     * 右键：不潜行开调整界面，潜行则装备到空的饰品槽。
     *
     * <p>
     * 之所以不给「右键直接装备」：一个右键只能干一件事，而这个物品最需要的是
     * 那个调整界面。装备走潜行右键，或者照 Baubles 的常规做法在原版背包界面里
     * 拖进饰品槽（Baubles-Expanded 把饰品槽并进了玩家背包界面）。
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            if (!world.isRemote) {
                equipToFreeSlot(player, stack);
            }
            return stack;
        }

        if (world.isRemote) {
            // 走代理而不是直接 new 客户端界面类：这个类是公共的，
            // 引用了 net.minecraft.client.* 的话服务端就得靠 JVM 的惰性解析兜着。
            FutaGtnhMod.proxy.openSwiftStepGui(stack);
        }
        return stack;
    }

    /**
     * 把手里这个迅步放进第一个能收它的空饰品槽。
     *
     * <p>
     * 服务端专用。放完必须显式 {@code syncSlotToClients} ——
     * {@code InventoryBaubles.markDirty()} 只是转手调了一下
     * {@code player.inventory.markDirty()}，并不会把饰品槽推给客户端，
     * 不同步的话客户端那边槽位是空的，速度立刻就不生效。
     */
    private static void equipToFreeSlot(EntityPlayer player, ItemStack held) {
        IInventory baubles = BaublesApi.getBaubles(player);
        if (baubles == null || held == null || held.stackSize <= 0) return;

        for (int i = 0; i < baubles.getSizeInventory(); i++) {
            if (baubles.getStackInSlot(i) != null) continue;
            if (!baubles.isItemValidForSlot(i, held)) continue;

            ItemStack single = held.copy();
            single.stackSize = 1;
            baubles.setInventorySlotContents(i, single);
            if (baubles instanceof InventoryBaubles) {
                ((InventoryBaubles) baubles).syncSlotToClients(i);
            }

            held.stackSize--;
            if (held.stackSize <= 0) {
                player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
            }
            return;
        }
    }

    // ==================================================================
    // 显示
    // ==================================================================

    @Override
    public boolean hasEffect(ItemStack stack, int pass) {
        // 附魔光效：一眼能看出这个迅步是「调过的」
        return getFlightMultiplier(stack) > DEFAULT_MULTIPLIER || getWalkMultiplier(stack) > DEFAULT_MULTIPLIER
            || getLightLevel(stack) > 0;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        tooltip.add(
            EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                "item.futa_gtnh.swift_step.tooltip.flight",
                fixed(getFlightMultiplier(stack), 2)));
        tooltip.add(
            EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                "item.futa_gtnh.swift_step.tooltip.walk",
                fixed(getWalkMultiplier(stack), 2)));
        tooltip.add(
            EnumChatFormatting.GRAY + StatCollector
                .translateToLocalFormatted("item.futa_gtnh.swift_step.tooltip.light", describeLightLocalized(stack)));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.swift_step.tooltip.air"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.swift_step.tooltip.gui"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.swift_step.tooltip.equip"));
    }

    /**
     * 照明那一行的取值部分（会被翻译键套进去）。
     *
     * <p>
     * 和 {@link #describeLight} 的区别：这里要出<b>可翻译</b>的文字
     * （「关」/「火把」在英文客户端上得是 Off / torch），
     * 方法名里的 Localized 就是提醒这一点。
     */
    public static String describeLightLocalized(ItemStack stack) {
        int level = getLightLevel(stack);
        if (level <= 0) return StatCollector.translateToLocal("item.futa_gtnh.swift_step.light.off");
        if (level == TORCH_LIGHT) {
            return StatCollector.translateToLocalFormatted("item.futa_gtnh.swift_step.light.torch", level);
        }
        return Integer.toString(level);
    }

    private static String fixed(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
