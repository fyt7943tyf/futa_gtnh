package com.futa_gtnh.miner;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketMinerDeployUse;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTEOilDrillBase;
import gregtech.common.tileentities.machines.multi.MTEOreDrillingPlantBase;

/**
 * 「矿机一站式部署器」：右键采矿场/油气钻机的<b>主控制器</b>，一键成型整个多方块
 * （外壳 + 框架 + 全部仓体）并贴上 AE 采矿枢纽覆盖板，材料按
 * 背包 → 共享背包 → 随身 ME 终端 三级扣取。潜行右键 = 只看材料清单不动方块。
 *
 * <p>
 * 为什么走 {@code onItemUseFirst} + 独立 C2S 包：GT 控制器的
 * {@code onBlockActivated} 会开自己的 GUI，普通 {@code onItemUse} 轮不到执行；
 * 而客户端 {@code onItemUseFirst} 返回 true 后 1.7.10 的原生 C08 点击包不再发送，
 * 服务端必须靠 {@link PacketMinerDeployUse} 才知道这次右键 ——
 * 和纸箱（{@code ItemBlockCardboardBox}）同一套管线。客户端只做「是不是钻机
 * 控制器」的粗判，真正校验在服务端重做。
 */
public class ItemMinerDeployer extends Item {

    public static final String NAME = "miner_deployer";

    public ItemMinerDeployer() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
        setCreativeTab(net.minecraft.creativetab.CreativeTabs.tabMisc);
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (player == null || world == null) return false;
        if (!world.isRemote) return false;

        // 粗判：目标是不是受支持的钻机控制器；服务端会重新校验
        if (!isSupportedController(world, x, y, z)) return false;
        NetworkHandler.INSTANCE.sendToServer(new PacketMinerDeployUse(x, y, z, player.isSneaking()));
        return true;
    }

    /** 客户端粗判：目标方块实体是不是采矿场/油气钻机控制器。 */
    public static boolean isSupportedController(World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof IGregTechTileEntity gte && gte.getMetaTileEntity() instanceof MetaTileEntity mte
            && (mte instanceof MTEOreDrillingPlantBase || mte instanceof MTEOilDrillBase);
    }
}
