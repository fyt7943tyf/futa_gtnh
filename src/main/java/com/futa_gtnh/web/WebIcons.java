package com.futa_gtnh.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 物品图标的 PNG 提取。
 *
 * <p>
 * <b>为什么是「读资源包里的贴图」而不是「把物品渲染到帧缓冲再截图」</b>：
 * 后者能连 3D 方块、附魔光效、耐久条一起画出来，好看得多，但它要往客户端的
 * 渲染管线里插一脚（另开 FBO、切投影、读像素），任何一处 GL 状态没还原干净都会
 * 影响正常画面，而且必须在渲染线程、每帧只能画几个。手机上一个搜索结果列表
 * 就是几十张图，这条路既慢又危险。
 *
 * <p>
 * 走资源包这条路拿到的是<b>游戏自己用的同一张 PNG</b>：物品走
 * {@code assets/<mod>/textures/items/}，方块走 {@code textures/blocks/}。
 * 代价是没有 3D 立体感、方块图标显示成它的「物品形态贴图」，
 * 但正确、快、零风险，还能走浏览器缓存。
 *
 * <p>
 * <b>但「同一张贴图」不等于「同样的颜色」</b> —— 这一条踩过坑：
 * 游戏画物品时会先 {@code glColor4f(物品颜色)}，也就是把贴图<b>乘一个颜色</b>
 * （{@link Item#getColorFromItemStack}）。GT 的材质类物品全靠这个上色：
 * 它们的贴图是一张灰白底图（比如「木板」用的是 {@code materialicons/WOOD/plate.png}），
 * 真正显示成棕色的是被材质颜色乘出来的。只搬贴图不上色，页面上就会一片惨白 ——
 * 玩家一眼就看出「这个图标不对」。所以这里把各渲染层按颜色合成出来。
 *
 * <p>
 * 读贴图必须在客户端线程上（资源管理器在资源重载期间会换实例），
 * 解码/上色/裁帧则放在 HTTP 线程 —— {@code ImageIO} 对互不相干的图片是线程安全的。
 * 绝大多数物品（原版那种不透明的）没有任何着色、只有一层，会原样透传，不做无谓的重编码。
 */
public final class WebIcons {

    private WebIcons() {}

    /** 图标缓存条目上限。GTNH 一个大配方树也就几百种物品，几千条够用了。 */
    private static final int CACHE_LIMIT = 4096;

    /** 渲染层数上限，纯粹是防御性的（正常物品 1~2 层）。 */
    private static final int MAX_RENDER_PASSES = 4;

    private static final Map<String, byte[]> CACHE = new LinkedHashMap<String, byte[]>(256, 0.75F, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    /** 已缓存的图标里，哪些是离屏渲染出来的（其余是退回读贴图的），见 {@link Icon#rendered}。 */
    private static final Set<String> RENDERED = new java.util.HashSet<>();

    /** 读过的物品 -> 是否没有图标，避免对着一堆没图标的物品反复抛异常。 */
    private static final Map<String, Boolean> MISSES = new LinkedHashMap<String, Boolean>(256, 0.75F, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    /**
     * 取某个物品的图标 PNG 字节。
     *
     * @return 找不到贴图时返回 null（调用方回 404，界面用文字占位块兜底）
     */
    /**
     * 一张图标，以及它是怎么来的。
     *
     * <p>
     * {@code rendered} 这个标记是给自测用的：离屏渲染失败时会**安静地**退回读贴图，
     * 页面上图标照旧显示（只是变回平面贴图），光看接口根本分不出来 ——
     * 实测「离屏渲染每次都抛异常」就是这样躲过检查的。所以把来源暴露出去，
     * 响应头里带上，测试能断言「确实是画出来的」。
     */
    public static final class Icon {

        public final byte[] png;
        public final boolean rendered;

        Icon(byte[] png, boolean rendered) {
            this.png = png;
            this.rendered = rendered;
        }
    }

    /**
     * 取某个物品的图标。
     *
     * <p>
     * 先走 {@link WebIconRenderer} 的离屏渲染 —— 那是「游戏怎么画，我就怎么画」，
     * 3D 方块、材质着色、运行时绘制的物品全都自动对上。渲染不可用（还没进游戏、
     * GL 上下文没就绪、渲染抛异常）时退回「读资源包贴图」：那条路不完美
     * （方块是平的、材质没上色），但至少不会白屏。
     *
     * @return 找不到图标时返回 null（调用方回 404，界面用文字占位块兜底）
     */
    public static Icon iconOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;

        String cacheKey = identityOf(stack);
        synchronized (CACHE) {
            byte[] cached = CACHE.get(cacheKey);
            if (cached != null) return new Icon(cached, RENDERED.contains(cacheKey));
            if (Boolean.TRUE.equals(MISSES.get(cacheKey))) return null;
        }

        // 一、离屏渲染（渲染线程）
        byte[] png = null;
        boolean rendered = false;
        boolean timedOut = false;
        try {
            png = WebIconRenderer.render(stack, 8000L);
            rendered = png != null;
        } catch (java.util.concurrent.TimeoutException e) {
            // ★ 超时**不能**记成「这个物品没有图标」：渲染线程只是暂时没轮到它
            // （游戏窗口最小化、正在加载、玩家按了暂停……）。记下来的话，
            // 这个物品到关游戏为止都会是空白格子。
            timedOut = true;
        } catch (Throwable t) {
            png = null;
        }

        // 二、退回读贴图（客户端主线程：资源包重载时 ResourceManager 会被整个换掉）
        if (png == null && !timedOut) {
            try {
                png = WebClientTasks.call(() -> renderFromTextures(stack), 8000L);
            } catch (Throwable t) {
                return null;
            }
        }

        if (png == null && timedOut) return null;

        synchronized (CACHE) {
            if (png == null) {
                MISSES.put(cacheKey, Boolean.TRUE);
            } else {
                CACHE.put(cacheKey, png);
                if (rendered) {
                    RENDERED.add(cacheKey);
                } else {
                    RENDERED.remove(cacheKey);
                }
            }
        }
        return new Icon(png, rendered);
    }

    /**
     * 缓存键：物品身份（物品 + 元数据 + NBT 哈希）。
     *
     * <p>
     * 不用「贴图名」当键 —— 同一个贴图配上不同材质颜色就是两个长相完全不同的图标
     * （灰白底图乘棕色 = 木板，乘灰色 = 钢板），用贴图名当键会把它们混成一个。
     * 这个键只读对象字段，HTTP 线程上算也安全。
     */
    private static String identityOf(ItemStack stack) {
        int nbt = stack.getTagCompound() == null ? 0
            : stack.getTagCompound()
                .hashCode();
        return Item.getIdFromItem(stack.getItem()) + "@" + stack.getItemDamage() + "#" + nbt;
    }

    // ==================================================================
    // 在客户端线程上执行的部分
    // ==================================================================

    /** 退路：从资源包里读贴图、按物品颜色上色、多层合成（不完美，但不会白屏）。 */
    private static byte[] renderFromTextures(ItemStack stack) {
        Item item = stack.getItem();
        int meta = stack.getItemDamage();

        int passes = 1;
        try {
            if (item.requiresMultipleRenderPasses()) {
                passes = Math.max(1, Math.min(MAX_RENDER_PASSES, item.getRenderPasses(meta)));
            }
        } catch (Throwable ignored) {
            // 个别物品这里会抛，按单层处理
        }

        BufferedImage composed = null;
        byte[] singleRaw = null;
        boolean tinted = false;

        for (int pass = 0; pass < passes; pass++) {
            IIcon icon;
            try {
                icon = passes == 1 ? stack.getIconIndex() : item.getIconFromDamageForRenderPass(meta, pass);
            } catch (Throwable t) {
                continue;
            }
            if (icon == null) continue;

            byte[] raw = readSprite(icon.getIconName(), stack.getItemSpriteNumber());
            if (raw == null) continue;

            int color = 0xFFFFFF;
            try {
                color = item.getColorFromItemStack(stack, pass) & 0xFFFFFF;
            } catch (Throwable ignored) {
                // 拿不到颜色就按不上色处理
            }
            if (color != 0xFFFFFF) tinted = true;

            if (passes == 1) {
                // 单层且不上色时可以直接透传原文件，省掉一次解码 + 重编码
                if (!tinted) return cropAnimatedFrame(raw);
                singleRaw = raw;
            }

            BufferedImage image = decode(raw);
            if (image == null) continue;
            if (composed == null) {
                composed = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            }
            compose(composed, image, color);
        }

        if (composed == null) {
            // 解码失败但原始字节是好的（少见），至少把原图给出去
            return singleRaw == null ? null : cropAnimatedFrame(singleRaw);
        }
        return encode(composed);
    }

    /** 把一层贴图按颜色乘上去、叠到已有结果上（source-over）。 */
    private static void compose(BufferedImage dst, BufferedImage src, int rgb) {
        int width = Math.min(dst.getWidth(), src.getWidth());
        int height = Math.min(dst.getHeight(), src.getHeight());
        int tr = (rgb >> 16) & 0xFF;
        int tg = (rgb >> 8) & 0xFF;
        int tb = rgb & 0xFF;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = src.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) continue;

                int sr = ((argb >> 16) & 0xFF) * tr / 255;
                int sg = ((argb >> 8) & 0xFF) * tg / 255;
                int sb = (argb & 0xFF) * tb / 255;

                if (alpha == 0xFF) {
                    dst.setRGB(x, y, 0xFF000000 | (sr << 16) | (sg << 8) | sb);
                    continue;
                }
                // 半透明层：按 alpha 混合到已有像素上
                int back = dst.getRGB(x, y);
                int br = (back >> 16) & 0xFF;
                int bg = (back >> 8) & 0xFF;
                int bb = back & 0xFF;
                int outR = (sr * alpha + br * (255 - alpha)) / 255;
                int outG = (sg * alpha + bg * (255 - alpha)) / 255;
                int outB = (sb * alpha + bb * (255 - alpha)) / 255;
                dst.setRGB(x, y, 0xFF000000 | (outR << 16) | (outG << 8) | outB);
            }
        }
    }

    /**
     * 按图标名找到贴图文件并读成字节。
     *
     * <p>
     * 正常情况下第一个候选就命中：图标名是 {@code 模组:路径}，引擎把它补成
     * {@code textures/items/路径.png} 或 {@code textures/blocks/路径.png}。
     * 少数模组注册时自己带了 {@code items/} 前缀，所以再补一条
     * {@code textures/路径.png} —— 多试两次的成本远低于「图标空白」的代价。
     */
    private static byte[] readSprite(String iconName, int spriteNumber) {
        if (iconName == null || iconName.isEmpty()) return null;

        String domain = "minecraft";
        String path = iconName;
        int colon = iconName.indexOf(':');
        if (colon >= 0) {
            domain = iconName.substring(0, colon);
            path = iconName.substring(colon + 1);
        }

        for (ResourceLocation location : candidates(domain, path, spriteNumber)) {
            try {
                IResource resource = Minecraft.getMinecraft()
                    .getResourceManager()
                    .getResource(location);
                if (resource == null) continue;
                InputStream stream = resource.getInputStream();
                try {
                    byte[] data = readAll(stream);
                    if (data != null && data.length > 0) return data;
                } finally {
                    stream.close();
                }
            } catch (Throwable ignored) {
                // 这个候选路径不存在，试下一个
            }
        }
        return null;
    }

    private static List<ResourceLocation> candidates(String domain, String path, int spriteNumber) {
        List<ResourceLocation> out = new ArrayList<>(3);
        String primary = spriteNumber == 0 ? "textures/blocks/" : "textures/items/";
        String secondary = spriteNumber == 0 ? "textures/items/" : "textures/blocks/";
        out.add(new ResourceLocation(domain, primary + path + ".png"));
        out.add(new ResourceLocation(domain, secondary + path + ".png"));
        out.add(new ResourceLocation(domain, "textures/" + path + ".png"));
        return out;
    }

    private static byte[] readAll(InputStream stream) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // ==================================================================
    // 图片处理（HTTP 线程）
    // ==================================================================

    private static BufferedImage decode(byte[] raw) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) return null;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= width || height % width != 0) return image;

            // 动图贴图只取第一帧：水、岩浆、GT 的机器贴图都是「竖着排 N 帧」的长条，
            // 直接丢给 <img> 会被压扁成一条
            BufferedImage frame = image.getSubimage(0, 0, width, width);
            BufferedImage opaque = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
            opaque.getGraphics()
                .drawImage(frame, 0, 0, null);
            return opaque;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 动图贴图取第一帧；静态图原样返回（一个字节都不动）。 */
    static byte[] cropAnimatedFrame(byte[] raw) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) return raw;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= width || height % width != 0) return raw;

            BufferedImage frame = image.getSubimage(0, 0, width, width);
            BufferedImage opaque = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
            opaque.getGraphics()
                .drawImage(frame, 0, 0, null);
            byte[] encoded = encode(opaque);
            return encoded == null ? raw : encoded;
        } catch (Throwable t) {
            FutaGtnhMod.LOG.debug("网页配方：图标裁帧失败，按原图返回", t);
            return raw;
        }
    }
}
