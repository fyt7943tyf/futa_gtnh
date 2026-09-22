package com.futa_gtnh.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
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
 * 飞行护符：戴在身上就能提高飞行移动速度，右键打开界面自己调倍率。
 *
 * <p>
 * <b>速度改的是客户端的 {@code PlayerCapabilities.flySpeed}</b>，不是服务端。
 * 原因是 1.7.10 里 {@code setFlySpeed} / {@code setPlayerWalkSpeed} 都标着
 * {@code @SideOnly(Side.CLIENT)} —— 服务端那份类里<b>根本没有这两个方法</b>，
 * 在服务端调用会直接 {@code NoSuchMethodError}。而飞行速度本身就是个纯客户端参数
 * （{@code EntityPlayer.moveEntityWithHeading} 里
 * {@code jumpMovementFactor = capabilities.getFlySpeed()}，跑在客户端），
 * 服务端也验证不了玩家到底飞多快，所以放客户端是天经地义的。
 *
 * <p>
 * 倍率存在<b>物品自己的 NBT</b> 里（服务端写入、客户端读取），
 * 所以每个护符可以各调各的，而且跟着物品走、不会因为换人用就变。
 */
public class ItemFlightCharm extends Item implements IBauble {

    public static final String NAME = "flight_charm";

    /** 倍率存在物品 NBT 里的键名。带模组前缀避免和别的模组撞。 */
    private static final String TAG_MULTIPLIER = "futa_gtnh.flight_multiplier";

    /** 没调过时的默认倍率（1.0 = 原版飞行速度）。 */
    public static final float DEFAULT_MULTIPLIER = 1.0F;

    /** 下限。低于 1 就是减速了，没意义。 */
    public static final float MIN_MULTIPLIER = 1.0F;

    /** 原版飞行速度。倍率是相对它的。 */
    public static final float VANILLA_FLY_SPEED = 0.05F;

    public ItemFlightCharm() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        // 饰品不该能堆叠：戴的是「一个」，堆叠会让 NBT 里的倍率归属变得含糊
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    // ==================================================================
    // 倍率读写
    // ==================================================================

    /** @return 这个护符的倍率；没有 NBT 或数值非法时返回默认值 */
    public static float getMultiplier(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemFlightCharm)) return DEFAULT_MULTIPLIER;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(TAG_MULTIPLIER)) return DEFAULT_MULTIPLIER;
        return clampMultiplier(tag.getFloat(TAG_MULTIPLIER));
    }

    public static void setMultiplier(ItemStack stack, float value) {
        if (stack == null || !(stack.getItem() instanceof ItemFlightCharm)) return;
        if (stack.getTagCompound() == null) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound()
            .setFloat(TAG_MULTIPLIER, clampMultiplier(value));
    }

    /**
     * 把倍率夹到合法范围。
     *
     * <p>
     * 上下界都要夹：客户端可以把任意浮点数塞进包里发过来，
     * 不夹的话一个改过的客户端就能把 flySpeed 开到荒谬的值。
     * （本模组其它地方也是同一条原则：客户端的数字一律不信。）
     */
    public static float clampMultiplier(float value) {
        if (Float.isNaN(value)) return DEFAULT_MULTIPLIER;
        float max = (float) Math.max(MIN_MULTIPLIER, Config.flightCharmMaxMultiplier);
        if (value < MIN_MULTIPLIER) return MIN_MULTIPLIER;
        if (value > max) return max;
        return value;
    }

    /**
     * @return 该玩家身上戴着的护符倍率；没戴返回 0
     *
     *         <p>
     *         只遍历一遍饰品栏。两个护符不会叠乘 —— 取<b>最后一个</b>戴上的那个的倍率，
     *         叠乘会让「戴两个就更快」变成刻意刷数值的玩法，而且玩家也搞不清最终是几倍。
     */
    public static float findEquippedMultiplier(EntityPlayer player) {
        if (player == null) return 0.0F;
        IInventory baubles = BaublesApi.getBaubles(player);
        if (baubles == null) return 0.0F;

        for (int i = 0; i < baubles.getSizeInventory(); i++) {
            ItemStack stack = baubles.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemFlightCharm) {
                return getMultiplier(stack);
            }
        }
        return 0.0F;
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
        // 速度的应用在客户端 tick 里做（见 FlightCharmHandler），
        // 那里能直接拿到 ItemStack 和 capabilities，比在这里靠 EntityLivingBase 判断干净
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
            // 代理把「只有客户端才有的实现」隔离在 ClientProxy 里，更干净。
            FutaGtnhMod.proxy.openFlightCharmGui(stack);
        }
        return stack;
    }

    /**
     * 把手里这个护符放进第一个能收它的空饰品槽。
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
        // 附魔光效：一眼能看出这个护符是「开了加速的」
        return getMultiplier(stack) > DEFAULT_MULTIPLIER;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        float multiplier = getMultiplier(stack);
        tooltip.add(
            EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                "item.futa_gtnh.flight_charm.tooltip.speed",
                String.format(java.util.Locale.ROOT, "%.2f", multiplier),
                String.format(java.util.Locale.ROOT, "%.3f", multiplier * VANILLA_FLY_SPEED)));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.flight_charm.tooltip.gui"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.flight_charm.tooltip.equip"));
    }
}
