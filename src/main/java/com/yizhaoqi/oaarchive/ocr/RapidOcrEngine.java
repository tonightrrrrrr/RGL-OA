package com.yizhaoqi.oaarchive.ocr;

import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.Point;
import com.benjaminwan.ocrlibrary.TextBlock;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.parser.Block;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.ParamConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 基于 RapidOCR-Java（PP-OCRv4 + ONNX Runtime）的离线 OCR 实现。
 * 引擎在第一次使用时加载；原生库加载失败时降级为不可用，不影响其他功能（技术文档 6.3.7）。
 */
@Slf4j
@Component
public class RapidOcrEngine implements OcrEngine {

    private final OaProperties props;
    private volatile InferenceEngine engine;
    private volatile String unavailableReason;
    private volatile boolean initTried;

    public RapidOcrEngine(OaProperties props) {
        this.props = props;
    }

    @Override
    public boolean available() {
        return ensureEngine() != null;
    }

    @Override
    public String unavailableReason() {
        ensureEngine();
        return unavailableReason;
    }

    private InferenceEngine ensureEngine() {
        if (!props.getOcr().isEnabled()) {
            unavailableReason = "OCR 已在配置中关闭";
            return null;
        }
        if (initTried) {
            return engine;
        }
        synchronized (this) {
            if (!initTried) {
                try {
                    engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);
                    log.info("OCR 引擎加载成功：PP-OCRv4 (ONNX)，临时目录 {}", System.getProperty("java.io.tmpdir"));
                } catch (Throwable t) {
                    // UnsatisfiedLinkError 等错误也要接住，保证程序不崩溃
                    unavailableReason = "OCR 原生库加载失败：" + t;
                    log.error(unavailableReason, t);
                } finally {
                    initTried = true;
                }
            }
        }
        return engine;
    }

    @Override
    public List<Block> recognize(BufferedImage image, int page) {
        InferenceEngine e = ensureEngine();
        if (e == null || image == null) {
            return List.of();
        }
        BufferedImage prepared = OcrPreprocessor.prepare(image);
        Path tmp = null;
        try {
            // RapidOCR 只接受文件路径，先写成临时 PNG
            tmp = Files.createTempFile("oa-ocr-", ".png");
            ImageIO.write(prepared, "png", tmp.toFile());
            ParamConfig cfg = ParamConfig.getDefaultConfig();
            cfg.setDoAngle(true);
            cfg.setMostAngle(true);
            OcrResult result;
            // 原生引擎是单例，不确定是否线程安全，所以串行调用
            synchronized (this) {
                result = e.runOcr(tmp.toString(), cfg);
            }
            return toBlocks(result.getTextBlocks(), prepared.getWidth(), prepared.getHeight(), page);
        } catch (IOException ex) {
            log.warn("OCR 临时文件写入失败", ex);
            return List.of();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }

    private static List<Block> toBlocks(List<TextBlock> raw, int width, int height, int page) {
        List<Block> blocks = new ArrayList<>();
        if (raw == null) {
            return blocks;
        }
        for (TextBlock tb : raw) {
            String text = tb.getText() == null ? "" : tb.getText().trim();
            if (text.isEmpty() || tb.getBoxPoint() == null || tb.getBoxPoint().isEmpty()) {
                continue;
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0;
            for (Point p : tb.getBoxPoint()) {
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
            }
            float conf = averageScore(tb);
            blocks.add(new Block(text, page, maxY - minY, null, false,
                    (float) minX / width, (float) minY / height,
                    (float) (maxX - minX) / width, (float) (maxY - minY) / height,
                    conf, Block.Source.OCR));
        }
        return OcrLineMerger.merge(blocks);
    }

    private static float averageScore(TextBlock tb) {
        float[] scores = tb.getCharScores();
        if (scores == null || scores.length == 0) {
            return tb.getBoxScore();
        }
        float sum = 0;
        for (float s : scores) {
            sum += s;
        }
        return sum / scores.length;
    }
}
