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

    /** 没调过时的默认倍率（1.0 = 原版速度）。 */
    public static final float DEFAULT_MULTIPLIER = 1.0F;

    /** 下限。低于 1 就是减速了，没意义。 */
    public static final float MIN_MULTIPLIER = 1.0F;

    /** 原版飞行速度。飞行倍率是相对它的。 */
    public static final float VANILLA_FLY_SPEED = 0.05F;

    /** 原版移动速度（= {@code PlayerCapabilities.walkSpeed} 的默认值）。 */
    public static final float VANILLA_WALK_SPEED = 0.1F;

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
        if (Float.isNaN(configured) || configured < MIN_MULTIPLIER) return 20.0F;
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
        // 附魔光效：一眼能看出这个迅步是「调过速度的」
        return getFlightMultiplier(stack) > DEFAULT_MULTIPLIER || getWalkMultiplier(stack) > DEFAULT_MULTIPLIER;
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
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.swift_step.tooltip.gui"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.swift_step.tooltip.equip"));
    }

    private static String fixed(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
