package com.yizhaoqi.oaarchive.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * OCR 预处理（技术文档 6.3.6）：统一为 RGB、白底，长边超过 2400px 时等比缩小。
 * 红黑通道分离、倾斜纠正放在 P3。
 */
public final class OcrPreprocessor {

    static final int MAX_SIDE = 2400;

    private OcrPreprocessor() {
    }

    public static BufferedImage prepare(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        if (scale == 1.0 && src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        // 透明背景（PNG 截图常见）先铺白底，否则会变成黑底导致识别失败
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }
}
