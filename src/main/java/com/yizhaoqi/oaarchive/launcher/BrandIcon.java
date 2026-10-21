package com.yizhaoqi.oaarchive.launcher;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * 程序图标“RGL”（RGL-OA智能归档助手）：手绘二次元女孩打哈欠，旁边手写青色 RGL。
 * 图片在 resources/brand 下，由 design/icon-drafts/crop.py 生成：
 * 40px 及以下只有脸（放不下字），48px 及以上带 RGL。exe 图标、托盘图标、托盘面板标志都从这里取。
 */
public final class BrandIcon {

    public static final String SHORT_NAME = "RGL";
    public static final String FULL_NAME = "RGL-OA智能归档助手";

    /** 资源里有的尺寸 → 文件名 */
    private static final TreeMap<Integer, String> FILES = new TreeMap<>(Map.of(
            16, "face-16.png", 20, "face-20.png", 24, "face-24.png", 32, "face-32.png", 40, "face-40.png",
            48, "full-48.png", 64, "full-64.png", 128, "full-128.png", 256, "full-256.png"));

    private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();

    private BrandIcon() {
    }

    /** 取指定尺寸的图标：有正好的就直接用，否则取大一档的图缩小（缩小比放大清晰），超过 256 时放大 256 的图 */
    public static BufferedImage create(int size) {
        Integer key = FILES.ceilingKey(size);
        BufferedImage src = load(FILES.get(key != null ? key : FILES.lastKey()));
        return scale(src, size);
    }

    private static BufferedImage load(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            try (InputStream in = BrandIcon.class.getResourceAsStream("/brand/" + n)) {
                if (in == null) {
                    throw new IllegalStateException("缺少图标资源 /brand/" + n);
                }
                return ImageIO.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** 总是返回新图，调用方可以随意修改，不会影响缓存 */
    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (src.getWidth() == size) {
            g.drawImage(src, 0, 0, null);
        } else {
            // 一次缩小太多会丢细节，每次最多缩一半
            BufferedImage cur = src;
            while (cur.getWidth() / 2 >= size) {
                int half = cur.getWidth() / 2;
                BufferedImage next = new BufferedImage(half, half, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gh = next.createGraphics();
                gh.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                gh.drawImage(cur, 0, 0, half, half, null);
                gh.dispose();
                cur = next;
            }
            g.drawImage(cur, 0, 0, size, size, null);
        }
        g.dispose();
        return out;
    }
}
