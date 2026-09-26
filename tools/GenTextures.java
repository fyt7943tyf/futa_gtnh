import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 一次性纹理生成器：为自选抽奖机 + 太阳能除钙剂生成程序化像素风占位纹理。
 * 用 JDK 编译运行一次即可，产物直接写进 src/main/resources。
 */
public class GenTextures {

    static final String OUT = "src/main/resources/assets/futa_gtnh/textures";

    public static void main(String[] args) throws Exception {
        new File(OUT + "/items").mkdirs();
        new File(OUT + "/blocks").mkdirs();
        new File(OUT + "/gui").mkdirs();

        itemSolarDescaler();
        blockSide();
        blockTop();
        blockFront();
        gui();
        verify();
        System.out.println("textures written to " + new File(OUT).getAbsolutePath());
    }

    /**
     * 读回每张刚生成的 PNG 做自检。透明 PNG 不会在任何环节报错，只在游戏里
     * 「隐身」，所以生成后立刻验证。
     *
     * <p>两种模式：方块/GUI 每个像素都必须不透明；物品精灵图四角本来就留透明
     * （造型轮廓），只要求「足够多的像素不透明」——全透明说明 alpha 又被丢了。
     */
    static void verify() throws Exception {
        fullOpaque("blocks/loot_machine_side.png", 16, 16);
        fullOpaque("blocks/loot_machine_top.png", 16, 16);
        fullOpaque("blocks/loot_machine_front.png", 16, 16);
        // GUI 是 256x256 画布，面板只占左上角 176x256，画布留白本就透明
        fullOpaque("gui/loot_machine.png", 176, 256);
        mostlyOpaque("items/solar_descaler.png", 0.20);
    }

    static void fullOpaque(String name, int boundsW, int boundsH) throws Exception {
        BufferedImage img = ImageIO.read(new File(OUT, name));
        if (img.getWidth() < boundsW || img.getHeight() < boundsH) {
            throw new IllegalStateException(
                name + " 尺寸 " + img.getWidth() + "x" + img.getHeight()
                    + " 小于要求的 " + boundsW + "x" + boundsH);
        }
        for (int y = 0; y < boundsH; y++) {
            for (int x = 0; x < boundsW; x++) {
                int alpha = img.getRGB(x, y) >>> 24;
                if (alpha != 255) {
                    throw new IllegalStateException(
                        name + " 有透明像素 (" + x + "," + y + ") alpha=" + alpha + "，生成器有问题");
                }
            }
        }
        System.out.println("verified " + name + " (" + img.getWidth() + "x" + img.getHeight() + ", " + new File(OUT, name).length() + " bytes)");
    }

    static void mostlyOpaque(String name, double minRatio) throws Exception {
        BufferedImage img = ImageIO.read(new File(OUT, name));
        int w = img.getWidth(), h = img.getHeight();
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) == 255) opaque++;
            }
        }
        double ratio = (double) opaque / (w * h);
        if (ratio < minRatio) {
            throw new IllegalStateException(
                name + " 不透明像素只占 " + Math.round(ratio * 100) + "%（要求 >= "
                    + Math.round(minRatio * 100) + "%），多半又是全透明了");
        }
        System.out.println(
            "verified " + name + " (" + w + "x" + h + ", " + Math.round(ratio * 100) + "% opaque, "
                + new File(OUT, name).length() + " bytes)");
    }

    // ------------------------------------------------------------------

    static void itemSolarDescaler() throws Exception {
        BufferedImage img = img(16);
        // 喷壶造型：灰色喷嘴 + 蓝色壶身
        rect(img, 6, 1, 4, 2, 0x9C9C9C); // 喷嘴头
        rect(img, 7, 3, 2, 3, 0xB4B4B4); // 喷嘴颈
        rect(img, 4, 6, 8, 8, 0x2C6699); // 壶身轮廓
        rect(img, 5, 7, 6, 6, 0x4FA3E0); // 壶身亮面
        rect(img, 5, 10, 6, 3, 0x2F86C9); // 液面以下
        px(img, 5, 7, 0xBFDCFF); // 高光
        px(img, 6, 7, 0xBFDCFF);
        rect(img, 4, 6, 1, 1, 0x1E4A73); // 左阴影
        rect(img, 11, 6, 1, 8, 0x1E4A73);
        rect(img, 4, 13, 8, 1, 0x1E4A73);
        write(img, "items/solar_descaler.png");
    }

    static void blockSide() throws Exception {
        BufferedImage img = img(16);
        rect(img, 0, 0, 16, 16, 0x8F8F8F);
        rect(img, 0, 0, 16, 1, 0xB0B0B0); // 顶部受光
        rect(img, 0, 15, 16, 1, 0x5A5A5A); // 底部阴影
        rect(img, 0, 0, 1, 16, 0xA0A0A0);
        rect(img, 15, 0, 1, 16, 0x6A6A6A);
        // 四角铆钉
        px(img, 2, 2, 0x6A6A6A);
        px(img, 13, 2, 0x6A6A6A);
        px(img, 2, 13, 0x5A5A5A);
        px(img, 13, 13, 0x5A5A5A);
        // 上下两条水平饰条
        rect(img, 1, 5, 14, 1, 0x7A7A7A);
        rect(img, 1, 10, 14, 1, 0x7A7A7A);
        write(img, "blocks/loot_machine_side.png");
    }

    static void blockTop() throws Exception {
        BufferedImage img = img(16);
        rect(img, 0, 0, 16, 16, 0x9A9A9A);
        rect(img, 0, 0, 16, 1, 0xC0C0C0);
        rect(img, 0, 15, 16, 1, 0x606060);
        rect(img, 15, 0, 1, 16, 0x707070);
        // 中央金色圆盘（太阳能集热口）
        rect(img, 5, 5, 6, 6, 0xB8912E);
        rect(img, 6, 6, 4, 4, 0xE8C33C);
        px(img, 6, 6, 0xF7E08A);
        write(img, "blocks/loot_machine_top.png");
    }

    static void blockFront() throws Exception {
        BufferedImage img = img(16);
        blockSideInto(img);
        // 出货口（暗色凹槽）+ 金色投币槽
        rect(img, 4, 3, 8, 4, 0x3A3A3A);
        rect(img, 5, 4, 6, 2, 0x242424);
        rect(img, 6, 9, 4, 2, 0xE8C33C);
        px(img, 6, 9, 0xF7E08A);
        write(img, "blocks/loot_machine_front.png");
    }

    static void blockSideInto(BufferedImage img) {
        rect(img, 0, 0, 16, 16, 0x8F8F8F);
        rect(img, 0, 0, 16, 1, 0xB0B0B0);
        rect(img, 0, 15, 16, 1, 0x5A5A5A);
        rect(img, 0, 0, 1, 16, 0xA0A0A0);
        rect(img, 15, 0, 1, 16, 0x6A6A6A);
        px(img, 2, 2, 0x6A6A6A);
        px(img, 13, 2, 0x6A6A6A);
        px(img, 2, 13, 0x5A5A5A);
        px(img, 13, 13, 0x5A5A5A);
    }

    // ------------------------------------------------------------------
    // GUI：★ 贴图画布必须是 256x256 ★ —— 原版 Gui.drawTexturedModalRect 硬编码按
    // 1/256 归一化 UV，画布不是 256x256 的话整个面板会被拉伸变形（格子变长方形）。
    // 面板画在画布左上角（176x256），运行时只采样这个区域 —— 原版容器和本模组
    // 的 shared_terminal.png（256x256）都是这个做法。
    // 面板高度因此不能超过 256：出货网格用 9x4。
    // ------------------------------------------------------------------

    static void gui() throws Exception {
        int W = 256, H = 256;
        BufferedImage img = img(W, H);

        // 面板底 + 立体边框（上左亮、下右暗）—— 原版容器的凸起面板
        rect(img, 0, 0, 176, 256, 0xC6C6C6);
        rect(img, 0, 0, 176, 1, 0xFFFFFF);
        rect(img, 0, 0, 1, 256, 0xFFFFFF);
        rect(img, 0, 255, 176, 1, 0x555555);
        rect(img, 175, 0, 1, 256, 0x555555);
        px(img, 0, 0, 0xD4D4D4);
        px(img, 175, 0, 0xD4D4D4);
        px(img, 0, 255, 0xD4D4D4);
        px(img, 175, 255, 0xD4D4D4);

        // 两个输入槽（袋子/书），y=20..38
        slot(img, 8, 20);
        slot(img, 28, 20);

        // 模拟出货区：9x4，y=44..116
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 9; column++) {
                slot(img, 8 + column * 18, 44 + row * 18);
            }
        }

        // 玩家背包 9x3（y=176..230）+ 快捷栏（y=234..252）
        // 「物品栏」标签由代码按原版样式画在 y=165
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                slot(img, 8 + column * 18, 176 + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            slot(img, 8 + column * 18, 234);
        }

        write(img, "gui/loot_machine.png");
    }

    /**
     * 原版风格的 18x18 槽位：内部 #8B8B8B，上/左 1px 深色 #373737，
     * 下/右 1px 白色 #FFFFFF —— 凹陷（inset）效果。
     * <p>
     * 注意方向：原版槽位是「凹进面板的洞」，暗边在受光相反侧（上左），
     * 高光在下右；画反了（上左白）整个格子就会变成「浮起来的凸块」，
     * 一眼假。物品渲染时画在格子原点 +1（见 Container 的槽位坐标），正好居中。
     */
    static void slot(BufferedImage img, int x, int y) {
        rect(img, x, y, 18, 18, 0x8B8B8B);
        rect(img, x, y, 18, 1, 0x373737);
        rect(img, x, y, 1, 18, 0x373737);
        rect(img, x, y + 17, 18, 1, 0xFFFFFF);
        rect(img, x + 17, y, 1, 18, 0xFFFFFF);
    }

    // ------------------------------------------------------------------

    static BufferedImage img(int size) {
        return img(size, size);
    }

    static BufferedImage img(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    static void rect(BufferedImage img, int x, int y, int w, int h, int rgb) {
        Graphics2D g = img.createGraphics();
        // 颜色常量写的是 0xRRGGBB，而 Color(int, boolean) 按 0xAARRGGBB 解析 ——
        // 不补上 FF 的 alpha 会把每个像素都写成全透明（透明 PNG 不报错，
        // 在游戏里的表现和「材质丢失」一模一样：方块隐身、GUI 空白）
        g.setColor(new Color(0xFF000000 | rgb, true));
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    static void px(BufferedImage img, int x, int y, int rgb) {
        rect(img, x, y, 1, 1, rgb);
    }

    static void write(BufferedImage img, String name) throws Exception {
        File file = new File(OUT, name);
        ImageIO.write(img, "png", file);
        System.out.println("wrote " + file);
    }
}
