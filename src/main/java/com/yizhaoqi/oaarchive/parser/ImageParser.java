package com.yizhaoqi.oaarchive.parser;

import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.ocr.OcrEngine;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 图片解析：直接走 OCR。多页 TIFF（常见于扫描仪输出）逐页识别，最多 20 页。
 */
@Component
public class ImageParser implements DocumentParser {

    private static final Set<String> EXTS = Set.of("jpg", "jpeg", "png", "bmp", "tif", "tiff", "gif", "webp");
    private static final int MAX_PAGES = 20;

    private final OcrEngine ocr;

    public ImageParser(OcrEngine ocr) {
        this.ocr = ocr;
    }

    @Override
    public boolean supports(String ext) {
        return EXTS.contains(ext);
    }

    @Override
    public DocModel parse(Path file, String originalName) throws Exception {
        List<String> notes = new ArrayList<>();
        List<BufferedImage> pages = readPages(file);
        if (pages.isEmpty()) {
            throw new BizException("无法读取图片（不支持的图片格式）");
        }
        List<Block> blocks = new ArrayList<>();
        if (!ocr.available()) {
            notes.add("OCR 不可用：" + ocr.unavailableReason() + "，只能根据文件名识别");
        } else {
            for (int i = 0; i < pages.size(); i++) {
                blocks.addAll(ocr.recognize(pages.get(i), i + 1));
            }
            notes.add("图片文件，已使用 OCR");
        }
        return new DocModel(originalName, "image", pages.size(), blocks, Map.of(), ocr.available(), notes);
    }

    private static List<BufferedImage> readPages(Path file) throws Exception {
        List<BufferedImage> pages = new ArrayList<>();
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return pages;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return pages;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int n = Math.min(reader.getNumImages(true), MAX_PAGES);
                for (int i = 0; i < n; i++) {
                    pages.add(reader.read(i));
                }
            } finally {
                reader.dispose();
            }
        }
        return pages;
    }
}
