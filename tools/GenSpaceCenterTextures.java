import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 深空采矿指挥中心的程序化贴图生成器：采掘桁架、控制器正面（待机 / 运行）、全息投影核心（动画）、
 * 信号天线阵列（动画）、星球图标图集。
 *
 * <p>
 * 用 JDK 直接运行一次即可（在仓库根目录）：{@code java tools/GenSpaceCenterTextures.java}，
 * 产物写进 src/main/resources，生成后立刻读回自检。
 *
 * <p>
 * <b>图集分区是契约</b>：{@code SpaceCenterPartRenderer} 按固定的像素区域从全息核心 / 天线贴图里取图，
 * 星球图集是 16 列 × 4 行、每格 32×32、按星球名的 Java {@code String.hashCode} 取模 64 定格——
 * 改这里的布局必须同步改那边（{@code PlanetIconWidget.COLS/ROWS/CELL}）。
 */
public class GenSpaceCenterTextures {

    static final String OUT = "src/main/resources/assets/futa_gtnh/textures";
    /** 一帧贴图的边长。 */
    static final int S = 32;
    /** 星球图集：列数 / 行数 / 单格边长。 */
    static final int PLANET_COLS = 16;
    static final int PLANET_ROWS = 4;
    static final int PLANET_CELL = 32;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File blocks = new File(OUT + "/blocks");
        blocks.mkdirs();
        File gui = new File(OUT + "/gui");
        gui.mkdirs();

        write(blocks, "space_center_truss.png", truss());
        write(blocks, "space_center_front.png", front(false));
        write(blocks, "space_center_front_active.png", front(true));
        write(blocks, "space_center_holo.png", holo());
        writeText(blocks, "space_center_holo.png.mcmeta", "{\"animation\":{\"frametime\":2}}");
        write(blocks, "space_center_antenna.png", antenna());
        writeText(blocks, "space_center_antenna.png.mcmeta", "{\"animation\":{\"frametime\":3}}");
        write(gui, "planets.png", planets());
        verify();
        System.out.println("space center textures written to " + new File(OUT).getAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 自检：透明 PNG 不会在任何环节报错，只在游戏里「隐身」，所以生成后立刻读回验证
    // ------------------------------------------------------------------

    static void verify() throws Exception {
        opaque("blocks/space_center_truss.png", S, S);
        opaque("blocks/space_center_front.png", S, S);
        opaque("blocks/space_center_front_active.png", S, S);
        opaque("blocks/space_center_holo.png", S, S * 16);
        opaque("blocks/space_center_antenna.png", S, S * 12);

        BufferedImage planets = ImageIO.read(new File(OUT, "gui/planets.png"));
        if (planets.getWidth() != PLANET_COLS * PLANET_CELL || planets.getHeight() != PLANET_ROWS * PLANET_CELL) {
            throw new IllegalStateException(
                "planets.png 尺寸 " + planets.getWidth() + "x" + planets.getHeight());
        }
        int slot = slotOf("moon");
        int ox = slot % PLANET_COLS * PLANET_CELL, oy = slot / PLANET_COLS * PLANET_CELL;
        // 格子四角必须透明（否则 GUI 里每颗星球后面都是一块黑底），球心必须不透明
        for (int[] corner : new int[][] { { 0, 0 }, { PLANET_CELL - 1, 0 }, { 0, PLANET_CELL - 1 },
            { PLANET_CELL - 1, PLANET_CELL - 1 } }) {
            if ((planets.getRGB(ox + corner[0], oy + corner[1]) >>> 24) != 0) {
                throw new IllegalStateException("planets.png 格子四角应当透明");
            }
        }
        if ((planets.getRGB(ox + PLANET_CELL / 2, oy + PLANET_CELL / 2) >>> 24) != 255) {
            throw new IllegalStateException("planets.png 月球球心应当不透明");
        }
    }

    static void opaque(String name, int width, int height) throws Exception {
        BufferedImage img = ImageIO.read(new File(OUT, name));
        if (img.getWidth() != width || img.getHeight() != height) {
            throw new IllegalStateException(name + " 尺寸 " + img.getWidth() + "x" + img.getHeight());
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((img.getRGB(x, y) >>> 24) != 255) throw new IllegalStateException(name + " 应当完全不透明");
            }
        }
    }

    static void write(File dir, String name, Img img) throws Exception {
        ImageIO.write(img.image(), "png", new File(dir, name));
    }

    static void writeText(File dir, String name, String text) throws Exception {
        java.nio.file.Files.write(
            new File(dir, name).toPath(),
            text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    // ==================================================================
    // 画布与颜色
    // ==================================================================

    /** RGBA 画布，颜色是 {@code aarrggbb}。 */
    static final class Img {

        final int w, h;
        final int[] p;

        Img(int w, int h) {
            this.w = w;
            this.h = h;
            this.p = new int[w * h];
        }

        boolean in(int x, int y) {
            return x >= 0 && y >= 0 && x < w && y < h;
        }

        void set(int x, int y, int c) {
            if (in(x, y)) p[y * w + x] = c;
        }

        int get(int x, int y) {
            return in(x, y) ? p[y * w + x] : 0;
        }

        /** source-over：把半透明色叠到已有像素上。 */
        void over(int x, int y, int c) {
            if (!in(x, y)) return;
            int d = p[y * w + x];
            int sa = a(c), da = a(d);
            int oa = sa + da * (255 - sa) / 255;
            if (oa == 0) {
                p[y * w + x] = 0;
                return;
            }
            int r = (r(c) * sa + r(d) * da * (255 - sa) / 255) / oa;
            int g = (g(c) * sa + g(d) * da * (255 - sa) / 255) / oa;
            int b = (b(c) * sa + b(d) * da * (255 - sa) / 255) / oa;
            p[y * w + x] = oa << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
        }

        /** 加色提亮已有像素（光晕）；透明像素不动，保持轮廓干净。 */
        void add(int x, int y, int r, int g, int b, int strength) {
            if (!in(x, y)) return;
            int d = p[y * w + x];
            if (a(d) == 0) return;
            p[y * w + x] = a(d) << 24 | clamp(r(d) + r * strength / 255) << 16
                | clamp(g(d) + g * strength / 255) << 8
                | clamp(b(d) + b * strength / 255);
        }

        BufferedImage image() {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, w, h, p, 0, w);
            return img;
        }
    }

    static int rgb(int r, int g, int b) {
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    static int rgb(float r, float g, float b) {
        return rgb(Math.round(r), Math.round(g), Math.round(b));
    }

    static int rgba(int r, int g, int b, int a) {
        return clamp(a) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    static int rgba(float r, float g, float b, float a) {
        return rgba(Math.round(r), Math.round(g), Math.round(b), Math.round(a));
    }

    static int r(int c) {
        return c >> 16 & 255;
    }

    static int g(int c) {
        return c >> 8 & 255;
    }

    static int b(int c) {
        return c & 255;
    }

    static int a(int c) {
        return c >>> 24;
    }

    static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** 在 (r,g,b) 上叠加一个亮度偏移（-1..1 的比例），保持色相。 */
    static int shade(int rr, int gg, int bb, float delta) {
        float k = 1f + delta / 100f;
        return rgb(Math.round(rr * k), Math.round(gg * k), Math.round(bb * k));
    }

    // ---- 确定性噪声 ----

    static int mix(int x) {
        x *= 0x27d4eb2d;
        x ^= x >>> 15;
        x *= 0x85ebca6b;
        x ^= x >>> 13;
        return x;
    }

    /** [0,1) 的哈希噪声。 */
    static float rnd(int x, int y, int seed) {
        return (mix(x * 73856093 ^ y * 19349663 ^ seed * 83492791) & 0xFFFFFF) / 16777216f;
    }

    static float rnd3(int x, int y, int z) {
        return (mix(x * 73856093 ^ y * 19349663 ^ z * 83492791) & 0xFFFFFF) / 16777216f;
    }

    static float fade(float t) {
        return t * t * (3f - 2f * t);
    }

    /** 三维值噪声（三线性插值）。星球表面按球面法向量采样，天然无缝。 */
    static float noise3(float x, float y, float z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float fx = fade(x - ix), fy = fade(y - iy), fz = fade(z - iz);
        float x00 = lerp(rnd3(ix, iy, iz), rnd3(ix + 1, iy, iz), fx);
        float x10 = lerp(rnd3(ix, iy + 1, iz), rnd3(ix + 1, iy + 1, iz), fx);
        float x01 = lerp(rnd3(ix, iy, iz + 1), rnd3(ix + 1, iy, iz + 1), fx);
        float x11 = lerp(rnd3(ix, iy + 1, iz + 1), rnd3(ix + 1, iy + 1, iz + 1), fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    /** 三个倍频叠加的分形噪声，结果大致落在 0.1..0.9。 */
    static float fbm(float x, float y, float z) {
        return 0.58f * noise3(x, y, z) + 0.28f * noise3(x * 2.1f + 5.3f, y * 2.1f + 5.3f, z * 2.1f + 5.3f)
            + 0.14f * noise3(x * 4.3f + 9.7f, y * 4.3f + 9.7f, z * 4.3f + 9.7f);
    }

    // ---- 基本图元 ----

    static void rect(Img img, int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) img.set(x, y, c);
    }

    static void disc(Img img, float cx, float cy, float radius, int fill, int rim) {
        for (int y = (int) Math.floor(cy - radius - 1); y <= cy + radius + 1; y++) {
            for (int x = (int) Math.floor(cx - radius - 1); x <= cx + radius + 1; x++) {
                float d = (float) Math.hypot(x + 0.5f - cx, y + 0.5f - cy);
                if (d > radius) continue;
                img.set(x, y, rim != 0 && d > radius - 1f ? rim : fill);
            }
        }
    }

    /** 圆角发光点（LED / 指示灯），带一圈同色光晕。 */
    static void lamp(Img img, int cx, int cy, int c, int glowStrength) {
        img.set(cx - 1, cy - 1, c);
        img.set(cx, cy - 1, c);
        img.set(cx - 1, cy, c);
        img.set(cx, cy, c);
        if (glowStrength <= 0) return;
        for (int y = cy - 2; y <= cy + 1; y++) {
            for (int x = cx - 2; x <= cx + 1; x++) {
                if (x >= cx - 1 && x <= cx && y >= cy - 1 && y <= cy) continue;
                img.add(x, y, r(c), g(c), b(c), glowStrength);
            }
        }
    }

    // ==================================================================
    // 采掘桁架：拉丝钢板 + 凸起边框 + 内凹面板 + X 形斜撑 + 节点板
    // ==================================================================

    static Img truss() {
        Img img = new Img(S, S);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float brushed = rnd(0, y, 11) * 16f - 8f; // 拉丝：只随行变化 → 横向细纹
                float grain = rnd(x, y, 12) * 8f - 4f;
                boolean border = x < 3 || y < 3 || x >= 29 || y >= 29;
                int c = border ? shade(124, 132, 144, brushed * 0.6f + grain)
                    : shade(60, 64, 72, grain); // 内凹面板
                // 边框倒角：外缘上/左受光、下/右背光；内壁反过来
                if (x == 0 || y == 0) c = shade(184, 192, 204, grain);
                if (x == 31 || y == 31) c = shade(50, 54, 62, grain);
                if (x == 3 || y == 3) c = shade(74, 79, 88, grain);
                if (x == 28 || y == 28) c = shade(156, 164, 176, grain);
                img.set(x, y, c);
            }
        }
        // X 形斜撑：内面板四角到对角
        brace(img, 4.5f, 4.5f, 27.5f, 27.5f);
        brace(img, 27.5f, 4.5f, 4.5f, 27.5f);
        // 中央节点板 + 铆钉
        disc(img, 16f, 16f, 3.7f, rgb(152, 160, 172), rgb(92, 98, 108));
        disc(img, 16f, 16f, 1.3f, rgb(80, 86, 96), 0);
        // 边框中点螺栓：相邻桁架拼起来之后，它们正好落在纵横梁的交叉点上
        for (int[] bolt : new int[][] { { 15, 1 }, { 15, 30 }, { 1, 15 }, { 30, 15 } }) {
            int bx = bolt[0], by = bolt[1];
            img.set(bx, by, rgb(190, 198, 210));
            img.set(bx + 1, by, rgb(124, 132, 144));
            img.set(bx, by + 1, rgb(92, 98, 108));
            img.set(bx + 1, by + 1, rgb(150, 158, 170));
        }
        return img;
    }

    /** 一根带明暗的圆斜撑：朝左上的一侧受光、朝右下的一侧背光。 */
    static void brace(Img img, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        float half = 1.7f;
        for (int y = 4; y <= 27; y++) {
            for (int x = 4; x <= 27; x++) {
                // 有符号距离：>0 在斜撑的右下侧
                float sd = ((x + 0.5f - x0) * dy - (y + 0.5f - y0) * dx) / len;
                if (Math.abs(sd) >= half) continue;
                float t = sd / half;
                int c = t < 0 ? mixColor(rgb(200, 208, 220), rgb(144, 152, 164), t + 1f)
                    : mixColor(rgb(144, 152, 164), rgb(66, 70, 78), t);
                img.set(x, y, shade(r(c), g(c), b(c), rnd(x, y, 13) * 6f - 3f));
            }
        }
    }

    static int mixColor(int from, int to, float t) {
        t = clamp(t);
        return rgb(
            Math.round(lerp(r(from), r(to), t)),
            Math.round(lerp(g(from), g(to), t)),
            Math.round(lerp(b(from), b(to), t)));
    }

    // ==================================================================
    // 控制器正面：主屏（星球 + 轨道 + 遥测柱）+ 指示灯 + 散热格栅
    // ==================================================================

    static Img front(boolean active) {
        Img img = new Img(S, S);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float grain = rnd(x, y, 21) * 7f - 3.5f;
                int c = shade(38, 42, 50, grain);
                if (x == 0 || y == 0 || x == 31 || y == 31) c = rgb(20, 22, 26);
                if (x == 1 || y == 1) c = rgb(94, 102, 114);
                if (x == 30 || y == 30) c = rgb(22, 24, 28);
                img.set(x, y, c);
            }
        }
        // 屏幕外框（下沉的斜面）
        rect(img, 3, 3, 28, 20, rgb(22, 24, 28));
        for (int x = 4; x <= 27; x++) img.set(x, 4, rgb(58, 64, 74));

        // 玻璃：自上而下的深空渐变
        float pcx = 12.5f, pcy = 12.5f, pr = 4.6f;
        for (int y = 5; y <= 18; y++) {
            for (int x = 5; x <= 26; x++) {
                float t = (y - 5) / 13f;
                int c = mixColor(rgb(10, 24, 58), rgb(4, 8, 22), t);
                img.set(x, y, c);
            }
        }
        // 星图网格点
        for (int y = 7; y <= 17; y += 4) {
            for (int x = 7; x <= 25; x += 4) img.set(x, y, rgb(24, 46, 82));
        }
        // 星点
        float[][] stars = { { 7, 6 }, { 17, 6 }, { 22, 6 }, { 25, 9 }, { 7, 16 }, { 18, 16 }, { 24, 15 }, { 20, 10 } };
        for (int i = 0; i < stars.length; i++) {
            int bright = active ? 255 : 190;
            if (!active && (i & 1) == 1) bright = 130;
            int sx = (int) stars[i][0], sy = (int) stars[i][1];
            img.set(sx, sy, rgb(bright, bright, Math.min(255, bright + 20)));
            if (active && i % 3 == 0) {
                img.set(sx - 1, sy, rgb(90, 150, 200));
                img.set(sx, sy - 1, rgb(90, 150, 200));
            }
        }
        // 轨道：虚线圆，运行时点亮。只画在星球所在的左半屏——右边要留给遥测柱
        int orbitColor = active ? rgb(74, 232, 212) : rgb(34, 112, 112);
        for (float t = 0; t < Math.PI * 2; t += 0.01f) {
            for (float radius : new float[] { 6.0f, 8.4f }) {
                int dash = (int) (t * 9 / Math.PI);
                if ((dash & 1) == 1) continue;
                int x = Math.round(pcx + (float) Math.cos(t) * radius);
                int y = Math.round(pcy + (float) Math.sin(t) * radius * 0.78f);
                if (x >= 6 && x <= 19 && y >= 6 && y <= 17) img.set(x, y, orbitColor);
            }
        }
        // 星球：左上受光、右下入夜，两处陨石坑
        float[] light = { -0.5f, -0.55f, 0.67f };
        for (int y = 8; y <= 17; y++) {
            for (int x = 8; x <= 17; x++) {
                float dx = (x + 0.5f - pcx) / pr, dy = (y + 0.5f - pcy) / pr;
                float d2 = dx * dx + dy * dy;
                if (d2 > 1f) continue;
                float dz = (float) Math.sqrt(1f - d2);
                float diff = Math.max(0, dx * light[0] + dy * light[1] + dz * light[2]);
                float lit = 0.14f + 0.86f * (float) Math.pow(diff, 0.8f);
                int base = active ? rgb(186, 198, 222) : rgb(136, 146, 170);
                float crater = Math.hypot(dx + 0.25f, dy + 0.2f) < 0.2f ? 0.8f
                    : Math.hypot(dx - 0.35f, dy - 0.3f) < 0.13f ? 0.82f : 1f;
                img.set(
                    x,
                    y,
                    rgb(
                        r(base) * lit * crater,
                        g(base) * lit * crater,
                        b(base) * lit * crater + (active ? 14 * lit : 0)));
            }
        }
        // 卫星 / 上行探测器：轨道上的亮点
        int satColor = active ? rgb(190, 255, 255) : rgb(120, 200, 220);
        img.set(17, 7, satColor);
        img.set(18, 7, satColor);
        if (active) img.add(17, 8, 120, 255, 240, 140);

        // 右侧遥测柱
        int barColor = active ? rgb(92, 236, 226) : rgb(40, 108, 118);
        int[][] bars = { { 21, 5, 5 }, { 23, 5, 8 }, { 25, 5, 3 } };
        int shift = active ? 0 : 1;
        for (int[] bar : bars) {
            for (int i = 0; i < bar[2] - shift; i++) img.set(bar[0], 17 - i, barColor);
        }
        for (int x = 20; x <= 26; x++) img.set(x, 18, active ? rgb(50, 150, 150) : rgb(26, 60, 70));

        // CRT 扫描线 + 运行时的一条亮带
        for (int y = 5; y <= 18; y++) {
            for (int x = 5; x <= 26; x++) {
                int c = img.get(x, y);
                if ((y & 1) == 1) c = rgb(r(c) * 78 / 100, g(c) * 78 / 100, b(c) * 78 / 100);
                if (active && y == 10) c = mixColor(c, rgb(90, 220, 210), 0.35f);
                img.set(x, y, c);
            }
        }
        // 运行时屏幕内侧一圈辉光
        if (active) {
            for (int x = 5; x <= 26; x++) {
                img.add(x, 5, 60, 210, 190, 150);
                img.add(x, 18, 60, 210, 190, 110);
            }
            for (int y = 5; y <= 18; y++) {
                img.add(5, y, 60, 210, 190, 110);
                img.add(26, y, 60, 210, 190, 150);
            }
        }

        // 屏幕下沿的强调线
        for (int x = 4; x <= 27; x++) {
            img.set(x, 22, rgb(30, 33, 40));
            img.set(x, 23, active ? rgb(62, 224, 202) : rgb(36, 148, 138));
        }
        // 指示灯：运行 / 链路 / 火箭就绪 / 功率
        int[][] idle = { { 66, 76, 86 }, { 66, 76, 86 }, { 128, 92, 40 }, { 66, 76, 86 } };
        int[][] live = { { 112, 255, 140 }, { 92, 230, 255 }, { 255, 200, 90 }, { 230, 240, 255 } };
        for (int i = 0; i < 4; i++) {
            int[] c = active ? live[i] : idle[i];
            lamp(img, 7 + i * 6, 26, rgb(c[0], c[1], c[2]), active ? 110 : 0);
        }
        // 底部：两侧散热格栅，中间铭牌
        for (int x : new int[] { 5, 7, 9, 11, 20, 22, 24, 26 }) {
            for (int y = 28; y <= 30; y++) img.set(x, y, rgb(16, 18, 22));
        }
        rect(img, 14, 28, 17, 30, rgb(78, 84, 94));
        img.set(15, 29, rgb(128, 136, 148));
        img.set(16, 29, rgb(128, 136, 148));
        return img;
    }

    // ==================================================================
    // 全息投影核心：中间 8 列是能量柱条带（下扫扫描线），两侧是两道轨道（图标用）
    //   图集分区：x 12..19 × 全高 = 能量柱；其中 y 12..19 的 8×8 = 柱顶 / 横梁
    // ==================================================================

    static Img holo() {
        int frames = 16;
        Img img = new Img(S, S * frames);
        for (int f = 0; f < frames; f++) {
            int oy = f * S;
            float phase = f / (float) frames;
            // 深空底 + 微弱的竖向电路纹
            for (int y = 0; y < S; y++) {
                for (int x = 0; x < S; x++) {
                    int c = mixColor(rgb(6, 8, 26), rgb(18, 10, 44), y / 31f);
                    if ((x & 7) == 0 || (x & 7) == 7) c = mixColor(c, rgb(30, 60, 120), 0.5f);
                    if ((y % 6) == 3) c = mixColor(c, rgb(20, 40, 80), 0.35f);
                    img.set(x, oy + y, c);
                }
            }
            // 星点：两组交替明暗
            for (int y = 0; y < S; y++) {
                for (int x = 0; x < S; x++) {
                    float h = rnd(x, y, 31);
                    if (h > 0.055f) continue;
                    boolean lit = ((f >> 2) + (h < 0.02f ? 1 : 0) & 1) == 0;
                    img.set(x, oy + y, lit ? rgb(150, 205, 255) : rgb(50, 80, 130));
                }
            }
            // 轨道 A（青）与 B（紫）：虚线沿椭圆流动，各带一颗亮点
            // 一上一下：中心与半径算过，亮点转到最高 / 最低点时也不会越出这一帧
            orbit(img, oy, 10.5f, 14.6f, 3.8f, 0.42f, phase, rgb(96, 232, 255), rgb(236, 255, 255));
            orbit(img, oy, 21.5f, 12.6f, 3.0f, -0.55f, 1f - phase * 0.8f, rgb(196, 124, 255), rgb(248, 232, 255));

            // 能量柱：x 12..19。截面 = 暗-中-亮-亮-亮-亮-中-暗
            int scanY = f * 2; // 每帧下移 2 行，16 帧扫完一遍
            for (int y = 0; y < S; y++) {
                // 扫描亮带：亮头 + 往上拖 6 行的尾
                int back = (y - scanY + S) % S;
                float boost = back <= 5 ? (1f - back / 6f) : 0f;
                for (int x = 12; x <= 19; x++) {
                    int edge = Math.min(x - 12, 19 - x);
                    int[] c = edge == 0 ? new int[] { 46, 140, 204 }
                        : edge == 1 ? new int[] { 112, 222, 246 } : new int[] { 224, 252, 255 };
                    img.set(
                        x,
                        oy + y,
                        rgb(
                            c[0] + (255 - c[0]) * boost,
                            c[1] + (255 - c[1]) * boost,
                            c[2] + (255 - c[2]) * boost));
                }
            }
        }
        return img;
    }

    /** 倾斜椭圆轨道上的流动虚线 + 一颗沿轨道跑的亮点。 */
    static void orbit(Img img, int oy, float cy, float rx, float ry, float tilt, float phase, int line, int head) {
        float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        for (float t = 0; t < Math.PI * 2; t += 0.01f) {
            float flow = (t / (float) (Math.PI * 2) + phase) % 1f;
            if (flow < 0f) flow += 1f;
            if (flow > 0.62f) continue; // 留一段空档，看起来像在转
            float ex = (float) Math.cos(t) * rx, ey = (float) Math.sin(t) * ry;
            int x = Math.round(16f + ex * ct - ey * st);
            int y = Math.round(cy + ex * st + ey * ct);
            int fade = (int) (120 + 135 * (1f - flow / 0.62f));
            // 只画在本帧范围内（y 0..31），绝不写进相邻帧
            if (y >= 0 && y < S) img.set(x, oy + y, rgb(r(line) * fade / 255, g(line) * fade / 255, b(line) * fade / 255));
        }
        float th = phase * (float) Math.PI * 2;
        float ex = (float) Math.cos(th) * rx, ey = (float) Math.sin(th) * ry;
        disc(img, 16f + ex * ct - ey * st, oy + cy + ex * st + ey * ct, 1.1f, head, 0);
    }

    // ==================================================================
    // 信号天线阵列：暗钢板底 + 基座 + 桅杆 + 呼吸的发射头 + 信号弧
    //   图集分区：基座 x 10..21 × y 27..31；桅杆 x 15..16 × y 11..26；发射头 x 12..19 × y 3..10
    // ==================================================================

    static Img antenna() {
        int frames = 12;
        Img img = new Img(S, S * frames);
        for (int f = 0; f < frames; f++) {
            int oy = f * S;
            float pulse = 0.5f - 0.5f * (float) Math.cos(Math.PI * 2 * f / frames);
            for (int y = 0; y < S; y++) {
                for (int x = 0; x < S; x++) {
                    float grain = rnd(x, y, 41) * 7f - 3.5f;
                    int c = shade(44, 48, 56, grain);
                    if (x == 0 || y == 0 || x == 31 || y == 31) c = rgb(22, 24, 28);
                    if (x == 1 || y == 1) c = rgb(88, 96, 108);
                    if (x == 30 || y == 30) c = rgb(26, 28, 32);
                    img.set(x, oy + y, c);
                }
            }
            // 侧向信号弧：灯左右两侧各两道，随呼吸变亮。灯离顶边只有 7px，
            // 以它为圆心画大弧会越出这一帧、写进相邻帧的底部（动画里就是一条乱闪的斜线）
            for (int side : new int[] { -1, 1 }) {
                for (int k = 0; k < 2; k++) {
                    float radius = 5.6f + k * 2.2f;
                    int strength = (int) (50 + 100 * pulse * (1f - k * 0.3f));
                    for (float phi = -0.5f; phi <= 0.5f; phi += 0.02f) {
                        int x = Math.round(16f + side * (float) Math.cos(phi) * radius);
                        int y = Math.round(oy + 7f + (float) Math.sin(phi) * radius);
                        img.add(x, y, 70, 220, 230, strength);
                    }
                }
            }
            // 基座
            rect(img, 10, oy + 28, 21, oy + 30, rgb(112, 118, 130));
            rect(img, 10, oy + 27, 21, oy + 27, rgb(172, 180, 192));
            for (int y = oy + 28; y <= oy + 30; y++) {
                img.set(10, y, rgb(150, 158, 170));
                img.set(21, y, rgb(74, 78, 88));
            }
            rect(img, 10, oy + 31, 21, oy + 31, rgb(50, 54, 62));
            img.set(13, oy + 29, rgb(84, 90, 100));
            img.set(18, oy + 29, rgb(84, 90, 100));
            // 桅杆 + 两根振子
            rect(img, 15, oy + 11, 15, oy + 26, rgb(180, 188, 200));
            rect(img, 16, oy + 11, 16, oy + 26, rgb(102, 108, 120));
            for (int y : new int[] { oy + 14, oy + 21 }) {
                rect(img, 12, y, 19, y, rgb(142, 150, 162));
                img.set(11, y, rgb(206, 214, 226));
                img.set(20, y, rgb(206, 214, 226));
            }
            // 发射头：外圈随呼吸变色，中心常亮
            for (int y = oy + 3; y <= oy + 10; y++) {
                for (int x = 12; x <= 19; x++) {
                    float d = (float) Math.hypot(x + 0.5f - 16f, y + 0.5f - (oy + 7f));
                    if (d > 4.3f) continue;
                    int c = d < 1.6f ? rgb(236, 255, 255)
                        : rgb(40 + (int) (70 * pulse), 170 + (int) (85 * pulse), 196 + (int) (59 * pulse));
                    img.set(x, y, c);
                }
            }
        }
        return img;
    }

    // ==================================================================
    // 星球图标图集
    // ==================================================================

    /** 一种星球画法。 */
    static final class Style {

        final int[] base;
        final int[] accent;
        /** rocky / banded / ice / lumpy */
        final String type;
        final boolean ring;
        final int[] atmosphere;
        final float craters;
        final float spec;
        final float seed;

        Style(int[] base, int[] accent, String type, boolean ring, int[] atmosphere, float craters, float spec,
            float seed) {
            this.base = base;
            this.accent = accent;
            this.type = type;
            this.ring = ring;
            this.atmosphere = atmosphere;
            this.craters = craters;
            this.spec = spec;
            this.seed = seed;
        }
    }

    static int[] c(int r, int g, int b) {
        return new int[] { r, g, b };
    }

    /** 星球名 → 画法。名字是 Galacticraft / GalaxySpace 的维度名。 */
    static Map<String, Style> knownPlanets() {
        Map<String, Style> m = new LinkedHashMap<>();
        m.put("moon", new Style(c(176, 178, 190), c(128, 132, 148), "rocky", false, null, 1f, 0.05f, 1));
        m.put("mars", new Style(c(206, 112, 72), c(150, 70, 44), "rocky", false, c(236, 150, 108), 0.5f, 0.05f, 2));
        m.put("asteroids", new Style(c(150, 138, 122), c(104, 94, 82), "lumpy", false, null, 1f, 0.03f, 3));
        m.put("venus", new Style(c(234, 202, 146), c(206, 160, 96), "banded", false, c(250, 222, 150), 0f, 0.12f, 4));
        m.put("mercury", new Style(c(160, 148, 134), c(108, 98, 88), "rocky", false, null, 1f, 0.04f, 5));
        m.put("europa", new Style(c(226, 228, 222), c(172, 112, 82), "ice", false, null, 0f, 0.22f, 6));
        m.put("iojupiter", new Style(c(228, 192, 82), c(124, 72, 32), "banded", false, null, 0.3f, 0.08f, 7));
        m.put("callisto", new Style(c(128, 114, 100), c(222, 214, 204), "rocky", false, null, 1f, 0.05f, 8));
        m.put("ganymed", new Style(c(176, 156, 136), c(204, 190, 170), "rocky", false, null, 0.6f, 0.06f, 9));
        m.put("ceres", new Style(c(158, 152, 138), c(246, 246, 240), "rocky", false, null, 0.8f, 0.08f, 10));
        m.put("enceladus", new Style(c(240, 246, 250), c(150, 192, 232), "ice", false, null, 0f, 0.3f, 11));
        m.put("titan", new Style(c(224, 170, 78), c(196, 126, 48), "banded", false, c(240, 190, 100), 0f, 0.1f, 12));
        m.put("triton", new Style(c(226, 214, 222), c(198, 168, 190), "ice", false, null, 0.2f, 0.2f, 13));
        m.put("proteus", new Style(c(132, 128, 124), c(90, 86, 84), "lumpy", false, null, 0.7f, 0.04f, 14));
        m.put("miranda", new Style(c(184, 192, 198), c(138, 148, 158), "rocky", false, null, 0.4f, 0.08f, 15));
        m.put("oberon", new Style(c(160, 150, 140), c(106, 98, 90), "rocky", false, null, 1f, 0.04f, 16));
        m.put("pluto", new Style(c(206, 182, 158), c(238, 226, 210), "rocky", false, null, 0.4f, 0.08f, 17));
        m.put("makemake", new Style(c(190, 140, 130), c(140, 92, 84), "rocky", false, null, 0.5f, 0.06f, 18));
        m.put("haumea", new Style(c(236, 236, 240), c(190, 196, 210), "ice", true, null, 0f, 0.2f, 19));
        m.put("phobos", new Style(c(112, 98, 86), c(72, 62, 54), "lumpy", false, null, 1f, 0.03f, 20));
        m.put("deimos", new Style(c(134, 120, 104), c(88, 78, 66), "lumpy", false, null, 0.8f, 0.03f, 21));
        m.put("centauribb", new Style(c(150, 182, 226), c(96, 140, 200), "ice", true, c(150, 200, 255), 0f, 0.3f, 22));
        m.put("vega1", new Style(c(170, 196, 236), c(118, 160, 214), "banded", true, c(170, 210, 255), 0f, 0.2f, 23));
        m.put("barnarda2", new Style(c(206, 166, 120), c(150, 104, 66), "rocky", false, c(240, 180, 120), 0.5f, 0.08f, 24));
        m.put("barnarda4", new Style(c(120, 176, 150), c(64, 128, 112), "ice", false, c(140, 230, 200), 0f, 0.3f, 25));
        m.put("barnarda5", new Style(c(196, 150, 140), c(140, 92, 84), "rocky", false, null, 0.5f, 0.06f, 26));
        m.put("ross128b", new Style(c(180, 170, 206), c(120, 108, 152), "rocky", false, c(190, 170, 240), 0.4f, 0.1f, 27));
        m.put("ross128ba", new Style(c(206, 192, 166), c(160, 138, 104), "banded", false, null, 0f, 0.08f, 28));
        m.put("tcetie", new Style(c(150, 206, 170), c(84, 150, 118), "banded", false, c(150, 240, 190), 0f, 0.2f, 29));
        m.put("anubis", new Style(c(206, 182, 120), c(158, 128, 66), "banded", false, null, 0f, 0.08f, 30));
        m.put("seth", new Style(c(170, 160, 206), c(112, 98, 152), "banded", true, null, 0f, 0.15f, 31));
        m.put("horus", new Style(c(224, 190, 130), c(176, 134, 70), "banded", true, null, 0f, 0.15f, 32));
        m.put("maahes", new Style(c(206, 140, 130), c(146, 82, 74), "banded", false, null, 0f, 0.1f, 33));
        return m;
    }

    static Img planets() {
        Img img = new Img(PLANET_COLS * PLANET_CELL, PLANET_ROWS * PLANET_CELL);
        Style[] slots = new Style[PLANET_COLS * PLANET_ROWS];
        for (Map.Entry<String, Style> e : knownPlanets().entrySet()) {
            slots[slotOf(e.getKey())] = e.getValue();
        }
        // 没被已知星球占到的格子：按格号随机一种画法，留给整合包里的其他星球
        String[] types = { "rocky", "banded", "ice" };
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) continue;
            int hue = (int) (rnd(i, 7, 51) * 360);
            float sat = 0.25f + rnd(i, 8, 52) * 0.35f;
            int[] base = hsv(hue, sat, 0.62f + rnd(i, 9, 53) * 0.25f);
            int[] accent = hsv((hue + 40 + (int) (rnd(i, 10, 54) * 80)) % 360, sat + 0.15f, 0.5f);
            String type = types[(int) (rnd(i, 11, 55) * types.length)];
            slots[i] = new Style(
                base,
                accent,
                type,
                rnd(i, 12, 56) < 0.22f,
                null,
                type.equals("rocky") ? 0.3f + rnd(i, 13, 57) * 0.6f : 0f,
                type.equals("ice") ? 0.25f : 0.06f,
                100 + i);
        }
        for (int i = 0; i < slots.length; i++) {
            drawPlanet(img, i % PLANET_COLS * PLANET_CELL, i / PLANET_COLS * PLANET_CELL, slots[i]);
        }
        return img;
    }

    /** Java {@link String#hashCode()} 取模——与 {@code PlanetRegistry.iconSlotOf} 完全同一个算法。 */
    static int slotOf(String name) {
        return Math.floorMod(name.hashCode(), PLANET_COLS * PLANET_ROWS);
    }

    static int[] hsv(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2f - 1f)), m = v - c;
        int i = (int) (h / 60f) % 6;
        float r = i == 0 || i == 1 ? c : i == 2 || i == 3 ? 0f : x;
        float g = i == 1 || i == 2 ? x : i == 3 || i == 4 ? c : 0f;
        float b = i == 3 ? x : i == 4 || i == 5 ? c : 0f;
        return new int[] { (int) ((r + m) * 255), (int) ((g + m) * 255), (int) ((b + m) * 255) };
    }

    // 光照：来自左上前方
    static final float[] LIGHT = { -0.48f, 0.52f, 0.707f };

    static void drawPlanet(Img img, int ox, int oy, Style s) {
        float center = PLANET_CELL / 2f;
        float radius = s.ring ? 9.2f : 11.2f;
        if (s.ring) drawRing(img, ox, oy, center, false);

        // 陨石坑：球面上随机方向 + 角半径
        List<float[]> craters = new ArrayList<>();
        int count = Math.round(s.craters * 7);
        for (int i = 0; i < count; i++) {
            float u = rnd(i, 3, (int) s.seed), v = rnd(i, 5, (int) s.seed), w = rnd(i, 9, (int) s.seed);
            float z = 0.15f + 0.8f * w;
            float a = u * (float) Math.PI * 2, r = (float) Math.sqrt(1f - z * z);
            craters.add(new float[] { (float) Math.cos(a) * r, (float) Math.sin(a) * r, z, 0.10f + 0.20f * v });
        }

        for (int v = 0; v < PLANET_CELL; v++) {
            for (int u = 0; u < PLANET_CELL; u++) {
                float dx = (u + 0.5f - center) / radius, dy = (v + 0.5f - center) / radius;
                float d = (float) Math.hypot(dx, dy);
                // 不规则小天体：半径随方位角起伏
                float rim = 1f;
                if (s.type.equals("lumpy")) {
                    float th = (float) Math.atan2(dy, dx);
                    rim = 0.80f + 0.20f * fbm((float) Math.cos(th) * 1.6f + s.seed, (float) Math.sin(th) * 1.6f,
                        3.5f);
                }
                if (d > rim) {
                    // 大气辉光：贴着轮廓往外 20%
                    if (s.atmosphere != null && d < 1.2f * rim) {
                        float glow = clamp((d - rim * 0.98f) / (0.22f * rim));
                        glow = (1f - glow) * (1f - glow) * 0.40f;
                        img.set(
                            ox + u,
                            oy + v,
                            rgba(s.atmosphere[0], s.atmosphere[1], s.atmosphere[2], glow * 255f));
                    }
                    continue;
                }
                float dn = d / rim;
                float dz = (float) Math.sqrt(Math.max(0f, 1f - dn * dn));
                float nx = dx / rim, ny = -dy / rim;
                float diff = Math.max(0, nx * LIGHT[0] + ny * LIGHT[1] + dz * LIGHT[2]);
                float lit = 0.15f + 0.85f * (float) Math.pow(diff, 0.85f);

                float[] col = surface(s, nx, ny, dz);
                // 陨石坑：坑底变暗、坑沿受光变亮
                for (float[] crater : craters) {
                    float dot = clamp(nx * crater[0] + ny * crater[1] + dz * crater[2]);
                    float ang = (float) Math.acos(dot);
                    if (ang >= crater[3]) continue;
                    float inner = ang / crater[3];
                    if (inner > 0.78f) {
                        float bright = 1.12f;
                        col[0] *= bright;
                        col[1] *= bright;
                        col[2] *= bright;
                    } else {
                        float dark = 0.80f + 0.10f * inner;
                        col[0] *= dark;
                        col[1] *= dark;
                        col[2] *= dark;
                    }
                }
                // 高光（冰面 / 海洋）
                float hx = LIGHT[0], hy = LIGHT[1], hz = LIGHT[2] + 1f;
                float hl = (float) Math.hypot(hx, Math.hypot(hy, hz));
                float spec = s.spec * (float) Math.pow(Math.max(0, (nx * hx + ny * hy + dz * hz) / hl), 26f);
                // 轮廓内侧的边缘光，带一点大气色
                float rimLight = (float) Math.pow(1f - dz, 3f) * 0.30f;
                // 没配大气色时边缘光就用本色
                float atr = s.atmosphere == null ? col[0] : s.atmosphere[0];
                float atg = s.atmosphere == null ? col[1] : s.atmosphere[1];
                float atb = s.atmosphere == null ? col[2] : s.atmosphere[2];
                int alpha = (int) (clamp((rim - d) * radius * 1.8f) * 255f);

                img.set(
                    ox + u,
                    oy + v,
                    rgba(
                        col[0] * lit + 255f * spec * 0.6f + atr * rimLight,
                        col[1] * lit + 255f * spec * 0.65f + atg * rimLight,
                        col[2] * lit + 255f * spec * 0.7f + atb * rimLight,
                        alpha));
            }
        }
        if (s.ring) drawRing(img, ox, oy, center, true);
    }

    /** 球面某点的固有色（未乘光照）。 */
    static float[] surface(Style s, float nx, float ny, float nz) {
        float[] col = new float[] { s.base[0], s.base[1], s.base[2] };
        switch (s.type) {
            case "banded": {
                // 沿纬度的条纹，再让噪声把它揉出一点起伏
                float warp = fbm(nx * 2.2f + s.seed, ny * 4.4f, nz * 2.2f) - 0.5f;
                float band = 0.5f + 0.5f * (float) Math.sin(ny * 11f + warp * 3.2f);
                band = band * band * (3f - 2f * band);
                col[0] = lerp(s.base[0], s.accent[0], band);
                col[1] = lerp(s.base[1], s.accent[1], band);
                col[2] = lerp(s.base[2], s.accent[2], band);
                float grain = 0.94f + 0.12f * fbm(nx * 6f, ny * 6f, nz * 6f);
                col[0] *= grain;
                col[1] *= grain;
                col[2] *= grain;
                break;
            }
            case "ice": {
                float crack = Math.abs(fbm(nx * 5.5f + s.seed, ny * 5.5f, nz * 5.5f) - 0.5f);
                if (crack < 0.028f) {
                    float t = 1f - crack / 0.028f;
                    col[0] = lerp(s.base[0], s.accent[0], t);
                    col[1] = lerp(s.base[1], s.accent[1], t);
                    col[2] = lerp(s.base[2], s.accent[2], t);
                }
                if (Math.abs(ny) > 0.78f) { // 极冠更白
                    col[0] = lerp(col[0], 255f, 0.5f);
                    col[1] = lerp(col[1], 255f, 0.5f);
                    col[2] = lerp(col[2], 255f, 0.5f);
                }
                float grain = 0.95f + 0.10f * fbm(nx * 9f + 3f, ny * 9f, nz * 9f);
                col[0] *= grain;
                col[1] *= grain;
                col[2] *= grain;
                break;
            }
            default: {
                float n = fbm(nx * 3.4f + s.seed, ny * 3.4f, nz * 3.4f);
                float tint = 0.86f + 0.28f * n;
                col[0] *= tint;
                col[1] *= tint;
                col[2] *= tint;
                if (n > 0.60f) { // 大块的反照率差异（陆地 / 暗斑 / 亮斑）
                    float t = clamp((n - 0.60f) / 0.25f);
                    col[0] = lerp(col[0], s.accent[0], t);
                    col[1] = lerp(col[1], s.accent[1], t);
                    col[2] = lerp(col[2], s.accent[2], t);
                }
                break;
            }
        }
        return col;
    }

    /** 行星环：绕横轴倾斜的椭圆，后半圈在星球背后、前半圈在前面。 */
    static void drawRing(Img img, int ox, int oy, float center, boolean front) {
        float squash = 0.36f;
        for (int v = 0; v < PLANET_CELL; v++) {
            for (int u = 0; u < PLANET_CELL; u++) {
                float px = u + 0.5f - center, py = (v + 0.5f - center) / squash;
                // py < 0 是上半个椭圆 = 星球背后那一侧
                if ((py < 0) == front) continue;
                float r = (float) Math.hypot(px, py);
                if (r < 11.3f || r > 15f) continue;
                // 几圈宽窄不同的环带，中间留一条暗缝
                float alpha = r < 12.3f ? 0.56f * ramp(r, 11.3f, 11.8f)
                    : r < 13.0f ? 0.18f
                        : r < 14.2f ? 0.62f : 0.30f * ramp(15f, 14.4f, 15f);
                int shade = front ? 255 : 190;
                float grain = 0.82f + 0.18f * rnd(u, v, 61);
                img.set(
                    ox + u,
                    oy + v,
                    rgba(226 * shade / 255, 214 * shade / 255, 190 * shade / 255, alpha * grain * 255f));
            }
        }
    }

    /** 在 [edge0, edge1] 之间从 0 线性升到 1（edge0 > edge1 时反向）。 */
    static float ramp(float v, float edge0, float edge1) {
        return clamp((v - edge0) / (edge1 - edge0));
    }
}
