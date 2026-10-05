package com.futa_gtnh.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 把物品<b>离屏渲染</b>成 PNG —— 也就是「和游戏里长得一模一样」的那条路。
 *
 * <p>
 * <b>为什么最后选了离屏渲染，而不是继续从资源包里读贴图</b>：
 * 读贴图那条路简单、快、零风险，但有三件事它永远做不到，而且每一件都被玩家一眼看出来了：
 * <ol>
 * <li><b>方块是 3D 的</b>。物品栏里的机器是多面体模型（GT 有整套自定义方块渲染器），
 * 而贴图只有它的「物品形态」那一面 —— 页面上就成了一块灰板子，和游戏里的机器对不上；</li>
 * <li><b>颜色是算出来的</b>。GT 的材质类物品（板/粉/杆/齿轮……）贴图是一张灰白底图，
 * 真正显示成棕色/铜色/钢色靠的是 {@code Item#getColorFromItemStack} 那一步着色；</li>
 * <li><b>有些物品根本没有静态贴图</b>。流体显示物品之类是运行时画出来的。</li>
 * </ol>
 * 而这几件事，游戏自己是怎么画物品的，照做一遍就全对了：开一个离屏缓冲、
 * 调 {@link RenderItem#renderItemAndEffectIntoGUI}、把像素读回来。
 *
 * <p>
 * <b>代价与边界</b>：必须挂在渲染线程（{@link TickEvent.RenderTickEvent} 的 END 阶段），
 * 每次都要自己把 GL 状态按原样还回去（矩阵压栈、属性压栈、解绑帧缓冲），
 * 所以这里有比较啰嗦的 push/pop —— 漏一处的后果不是图标画不出来，而是把游戏画面搞坏。
 * 另外一次只画几个：手机上翻一页要几十个图标，摊到几十帧里画完，不要卡住某一帧。
 *
 * <p>
 * 取不到（还没进游戏、GL 上下文不可用、渲染抛异常）时返回 null，
 * 由 {@link WebIcons} 退回「读贴图」那条老路 —— 那条路虽然不完美，但不会白屏。
 */
public final class WebIconRenderer {

    private WebIconRenderer() {}

    /**
     * 渲染倍率：物品按这个倍数画进离屏缓冲。
     *
     * <p>
     * 不能按 1× 画完再放大 —— 那样 3D 方块只有一个 16 像素大的模型，棱角全是锯齿。
     * 按 4× 画，方块是实打实按 64 像素光栅化出来的，边缘和面才是清楚的。
     */
    private static final int SCALE = 4;

    /** 物品四周留的空白（GUI 像素，不是设备像素），给 3D 方块超出 16×16 的那一圈。 */
    private static final int MARGIN = 8;

    /** 离屏缓冲边长（设备像素）。 */
    private static final int BUFFER = (16 + MARGIN * 2) * SCALE;

    /** 物品左上角在缓冲里的位置（GUI 像素）。 */
    private static final int ORIGIN = MARGIN;

    /**
     * 输出图标的边长上限。
     *
     * <p>
     * 4× 渲染后普通物品是 64 像素、3D 方块带溢出是 80 上下，正常都碰不到这个上限；
     * 它只用来兜住「某个模组的模型特别大」那种情况，免得一张图几百 KB。
     * 前端槽位是 36 CSS 像素、DPR 2~3（也就是 72~108 设备像素），64~96 正好对上。
     */
    private static final int MAX_OUTPUT = 128;

    /** 每帧最多画几个。 */
    private static final int MAX_PER_FRAME = 2;

    /** 每帧最多花多少毫秒。 */
    private static final long BUDGET_MILLIS = 8L;

    private static final ConcurrentLinkedQueue<Job> QUEUE = new ConcurrentLinkedQueue<>();

    private static Framebuffer framebuffer;
    private static ByteBuffer pixels;
    private static boolean registered;
    private static boolean loggedFailure;
    private static boolean loggedGlError;

    private static final class Job {

        final ItemStack stack;
        final CompletableFuture<byte[]> future = new CompletableFuture<>();

        Job(ItemStack stack) {
            this.stack = stack;
        }
    }

    /** 由 {@code WebRecipeService.register()} 调用（已确认在客户端）。 */
    public static void register() {
        if (registered) return;
        registered = true;
        FMLCommonHandler.instance()
            .bus()
            .register(new RenderListener());
    }

    /**
     * 取一张物品的渲染结果（供 HTTP 线程调用，会阻塞到渲染线程画完）。
     *
     * @return PNG 字节；渲染不可用时返回 null（调用方退回读贴图）
     */
    public static byte[] render(ItemStack stack, long timeoutMillis)
        throws TimeoutException, InterruptedException, ExecutionException {
        if (stack == null || stack.getItem() == null) return null;
        Job job = new Job(stack.copy());
        QUEUE.add(job);
        try {
            return job.future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            QUEUE.remove(job);
            throw e;
        }
    }

    /** 渲染线程上的消费者。必须是 public：FML 的 ASM 事件处理器在另一个类加载器里反射调用它。 */
    public static final class RenderListener {

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (QUEUE.isEmpty()) return;

            long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
            for (int i = 0; i < MAX_PER_FRAME; i++) {
                Job job = QUEUE.poll();
                if (job == null) return;
                try {
                    job.future.complete(renderOne(job.stack));
                } catch (Throwable t) {
                    job.future.completeExceptionally(t);
                    reportFailure(t);
                }
                if (System.currentTimeMillis() >= deadline) return;
            }
        }
    }

    private static void reportFailure(Throwable t) {
        if (loggedFailure) return;
        loggedFailure = true;
        FutaGtnhMod.LOG.warn("网页配方：离屏渲染物品图标失败（退回读贴图那条路，功能不受影响）", t);
    }

    // ==================================================================
    // 真正画的那一步（渲染线程）
    // ==================================================================

    private static byte[] renderOne(ItemStack stack) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer fb = ensureFramebuffer();
        if (fb == null) return null;

        // 先清掉攒下的错误，这样下面读到的错误一定是这一趟里产生的
        drainGlErrors();

        float[] savedProjection = captureMatrix(GL11.GL_PROJECTION_MATRIX);
        float[] savedModelview = captureMatrix(GL11.GL_MODELVIEW_MATRIX);
        boolean[] savedState = captureState();
        fb.bindFramebuffer(true);
        try {
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // 和 GuiContainer 画物品栏时同一套设置
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, BUFFER, BUFFER, 0.0D, 1000.0D, 3000.0D);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
            // 按 SCALE 倍光栅化：下面的坐标仍然按 GUI 像素给（物品就是 16×16），
            // 但真正落到缓冲上的是 16×SCALE —— 3D 方块的棱角才有那个分辨率
            GL11.glScalef(SCALE, SCALE, 1.0F);

            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

            RenderHelper.enableGUIStandardItemLighting();
            try {
                RenderItem.getInstance()
                    .renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, ORIGIN, ORIGIN);
            } finally {
                RenderHelper.disableStandardItemLighting();
            }
            reportGlError("画物品");

            return readAndEncode(fb);
        } finally {
            // 全部按值还原，<b>一个 push/pop 都不用</b>：
            //
            // 原来这里用的是 glPushAttrib/glPushMatrix + 对应的 pop，逻辑上配平，
            // 但实机日志里出现了「1284: Stack underflow」—— 多弹了一次栈。
            // 静态查过 vanilla 的 RenderItem（GUI 路径是配平的）和 Framebuffer（根本不碰栈），
            // 剩下的嫌疑就是 push 本身没成功：lwjgl3ify 把 GL11 映射到 LWJGL3，
            // 而 glPushAttrib 在核心 profile 里是废弃接口，某些位可能不被接受，
            // push 失败之后那个 pop 自然就弹穿了（glGetError 会清标志，所以每个会话只报一次）。
            //
            // 不去赌哪个位支持，直接改成「取值 - 画 - 写回」：栈上一个操作都没有，
            // 也就不可能由我来弹穿它。
            restoreMatrix(GL11.GL_PROJECTION, savedProjection);
            restoreMatrix(GL11.GL_MODELVIEW, savedModelview);
            restoreState(savedState);
            fb.unbindFramebuffer();
        }
    }

    // ==================================================================
    // GL 状态的取值 / 还原（不用栈）
    // ==================================================================

    /** 这一趟会碰到的开关。用 glIsEnabled 查，核心 profile 下也稳。 */
    private static final int[] STATE_CAPS = { GL11.GL_TEXTURE_2D, GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_LIGHTING,
        GL11.GL_ALPHA_TEST };

    private static float[] captureMatrix(int which) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(which, buffer);
        // ★ 不能用 buffer.array()：LWJGL3 的 BufferUtils 返回的是**直接缓冲**，
        // 直接缓冲没有背后数组，array() 会抛 UnsupportedOperationException。
        // 这里踩过一次，而且是「静默失败」—— 渲染每次抛异常、每次都退回读贴图，
        // 表面上图标还在（只是变回平面贴图），GL 错误也确实是 0（压根没画），
        // 差点就这么过去了。所以下面老老实实逐个取。
        float[] out = new float[16];
        for (int i = 0; i < 16; i++) {
            out[i] = buffer.get(i);
        }
        return out;
    }

    private static void restoreMatrix(int mode, float[] matrix) {
        if (matrix == null) return;
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.put(matrix);
        buffer.flip();
        GL11.glMatrixMode(mode);
        GL11.glLoadMatrix(buffer);
    }

    private static boolean[] captureState() {
        boolean[] state = new boolean[STATE_CAPS.length];
        for (int i = 0; i < STATE_CAPS.length; i++) {
            state[i] = GL11.glIsEnabled(STATE_CAPS[i]);
        }
        return state;
    }

    private static void restoreState(boolean[] state) {
        if (state != null) {
            for (int i = 0; i < STATE_CAPS.length; i++) {
                toggle(STATE_CAPS[i], state[i]);
            }
        }
        // 混合/深度函数不去逐条查：GL_BLEND_SRC 这类查询在核心 profile 下不一定可用，
        // 而游戏每帧都会自己设一遍，还原成 GUI 常用的值就够了
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void toggle(int cap, boolean on) {
        if (on) {
            GL11.glEnable(cap);
        } else {
            GL11.glDisable(cap);
        }
    }

    /** 把攒下的 GL 错误清干净（顺便丢掉别人的旧错误，只看这一趟）。 */
    private static void drainGlErrors() {
        for (int i = 0; i < 8; i++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) return;
        }
    }

    /** 这一趟画完有没有留下 GL 错误；有就报一次（只报第一次，别刷屏）。 */
    private static void reportGlError(String stage) {
        int error = GL11.glGetError();
        if (error == GL11.GL_NO_ERROR || loggedGlError) return;
        loggedGlError = true;
        FutaGtnhMod.LOG.warn("网页配方：离屏渲染在「{}」之后留下 GL 错误 {}（图标仍可用；请把这一行反馈给作者）", stage, error);
    }

    private static Framebuffer ensureFramebuffer() {
        if (framebuffer == null) {
            try {
                framebuffer = new Framebuffer(BUFFER, BUFFER, true);
                framebuffer.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
            } catch (Throwable t) {
                reportFailure(t);
                framebuffer = null;
                return null;
            }
        }
        if (pixels == null) {
            pixels = BufferUtils.createByteBuffer(BUFFER * BUFFER * 4);
        }
        return framebuffer;
    }

    private static byte[] readAndEncode(Framebuffer fb) throws Exception {
        pixels.clear();
        GL11.glReadPixels(0, 0, BUFFER, BUFFER, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        BufferedImage image = new BufferedImage(BUFFER, BUFFER, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < BUFFER; y++) {
            for (int x = 0; x < BUFFER; x++) {
                int i = ((BUFFER - 1 - y) * BUFFER + x) * 4; // OpenGL 原点在左下，图片在左上
                int r = pixels.get(i) & 0xFF;
                int g = pixels.get(i + 1) & 0xFF;
                int b = pixels.get(i + 2) & 0xFF;
                int a = pixels.get(i + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }

        BufferedImage trimmed = trim(image);
        // 不再统一缩放成固定尺寸：按 SCALE 光栅化出来的像素本身就是我们要的清晰度
        // （3D 方块的边是实打实按 4 倍画出来的），再重采样一次只会把它糊回去。
        // 只有异常大的模型才压一下，纯粹为了字节数。
        if (trimmed.getWidth() > MAX_OUTPUT) {
            trimmed = scaleTo(trimmed, MAX_OUTPUT);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        ImageIO.write(trimmed, "png", out);
        return out.toByteArray();
    }

    /** 裁掉四周全透明的边（3D 方块会比 16×16 大一圈，裁完刚好是它真正的轮廓）。 */
    private static BufferedImage trim(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) == 0) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX || maxY < minY) return image;

        // 裁成正方形（取长边），图标在格子中央看起来才对称
        int size = Math.max(maxX - minX + 1, maxY - minY + 1);
        int centerX = (minX + maxX) / 2;
        int centerY = (minY + maxY) / 2;
        int left = centerX - size / 2;
        int top = centerY - size / 2;

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx = left + x;
                int sy = top + y;
                if (sx < 0 || sy < 0 || sx >= image.getWidth() || sy >= image.getHeight()) continue;
                out.setRGB(x, y, image.getRGB(sx, sy));
            }
        }
        return out;
    }

    /**
     * 缩放到输出尺寸。
     *
     * <p>
     * 放大用最近邻（保持像素画的硬边，前端也是 {@code pixelated}）；
     * <b>缩小不能用最近邻</b> —— 那会直接丢掉整行整列的像素，红石那种细碎的点
     * 会掉一半，得用平滑缩放把像素平均进去。
     */
    private static BufferedImage scaleTo(BufferedImage image, int size) {
        if (image.getWidth() == size && image.getHeight() == size) return image;

        if (image.getWidth() > size) {
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, size, size, null);
            g.dispose();
            return out;
        }

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double ratio = (double) image.getWidth() / size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx = (int) (x * ratio);
                int sy = (int) (y * ratio);
                if (sx >= image.getWidth()) sx = image.getWidth() - 1;
                if (sy >= image.getHeight()) sy = image.getHeight() - 1;
                out.setRGB(x, y, image.getRGB(sx, sy));
            }
        }
        return out;
    }
}
