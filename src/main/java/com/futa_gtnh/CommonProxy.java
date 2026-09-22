package com.futa_gtnh;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.oredict.ShapedOreRecipe;

import com.futa_gtnh.block.BlockSharedTerminal;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.command.CommandSharedStorage;
import com.futa_gtnh.common.ForgeEventHandler;
import com.futa_gtnh.common.GuiHandler;
import com.futa_gtnh.common.ModEventHandler;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.shared.SharedStorageManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * 服务端 + 客户端共用的逻辑放在这里。
 * 只在客户端做的事（渲染器、按键绑定、GUI 等）写进 {@link ClientProxy}。
 */
public class CommonProxy {

    /** 注册用的方块实例，{@code FutaGtnhMod} 里也持有同一个引用。 */
    public static BlockSharedTerminal blockSharedTerminal;

    public void preInit(FMLPreInitializationEvent event) {
        // 读取配置文件（config/futa_gtnh.cfg）
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        NetworkHandler.init();
        // 两个事件处理器分别挂在两条不同的总线上，见 ForgeEventHandler 的类注释
        ModEventHandler.register();
        ForgeEventHandler.register();

        registerBlocks();
        registerRecipes();

        NetworkRegistry.INSTANCE.registerGuiHandler(FutaGtnhMod.instance, new GuiHandler());
    }

    public void init(FMLInitializationEvent event) {
        // 注册配方、事件监听
    }

    public void postInit(FMLPostInitializationEvent event) {
        // 处理与其他模组（例如 GregTech / NEI）的联动
    }

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSharedStorage());
    }

    // ==================================================================
    // 注册
    // ==================================================================

    private void registerBlocks() {
        blockSharedTerminal = new BlockSharedTerminal();
        GameRegistry.registerBlock(blockSharedTerminal, BlockSharedTerminal.NAME);
        GameRegistry
            .registerTileEntity(TileEntitySharedTerminal.class, FutaGtnhMod.MODID + ":" + BlockSharedTerminal.NAME);
        FutaGtnhMod.blockSharedTerminal = blockSharedTerminal;
    }

    /**
     * 共享终端的合成配方。
     *
     * <p>
     * 用矿物词典而不是具体物品，这样在装了其他模组、木桶/玻璃被统一成别的物品时
     * 配方仍然成立：
     *
     * <pre>
     *   玻璃  末影珍珠  玻璃
     *   末影珍珠  箱子  末影珍珠
     *   玻璃  末影珍珠  玻璃
     * </pre>
     *
     * 「末影珍珠」对应跨空间共享，「箱子」对应存储，和功能是自洽的。
     */
    private void registerRecipes() {
        if (!Config.enableRecipe) return;

        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(blockSharedTerminal, 1),
                new Object[] { "GEG", "ECE", "GEG", 'G', "blockGlass", 'E', "enderpearl", 'C', "chestWood" }));
    }

    // ==================================================================
    // 打开界面
    // ==================================================================

    /**
     * 让服务端为玩家打开共享存储界面。
     *
     * <p>
     * 必须由服务端调用：客户端的 {@code displayGuiScreen} 只会画个界面，
     * 服务端那边没有对应的 {@code Container}，任何操作都发不出去。
     *
     * @param terminal 方块终端；为 null 表示「按键远程打开」，此时没有终端输出功能
     */
    public static void openSharedStorage(EntityPlayer player, TileEntitySharedTerminal terminal) {
        if (player == null || player.worldObj == null) return;

        if (terminal != null) {
            player.openGui(
                FutaGtnhMod.instance,
                GuiHandler.GUI_SHARED_TERMINAL,
                player.worldObj,
                terminal.xCoord,
                terminal.yCoord,
                terminal.zCoord);
            return;
        }

        // 远程打开：坐标用玩家自己的位置占位。GuiHandler 会在那里找不到方块实体，
        // 于是 terminal 为 null —— 这正是我们想要的「没有终端」语义。
        World world = player.worldObj;
        player.openGui(
            FutaGtnhMod.instance,
            GuiHandler.GUI_SHARED_TERMINAL,
            world,
            (int) Math.floor(player.posX),
            (int) Math.floor(player.posY),
            (int) Math.floor(player.posZ));
    }

    // ==================================================================
    // 一个方便调试的辅助（被指令和日志用到）
    // ==================================================================

    /** @return 当前共享存储是否已经随存档载入 */
    public static boolean isStorageReady() {
        return SharedStorageManager.isLoaded();
    }
}
