package com.yizhaoqi.oaarchive.ocr;

import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.parser.Block;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 冒烟测试（技术文档 10.6）：原生库能否加载、中文识别是否可用、耗时多少。
 */
@EnabledOnOs(OS.WINDOWS)
class OcrSmokeTest {

    private static final File SAMPLES = new File("src/test/resources/samples/A");
    private static RapidOcrEngine engine;

    @BeforeAll
    static void init() {
        engine = new RapidOcrEngine(new OaProperties());
        long t = System.currentTimeMillis();
        assertThat(engine.available()).as(engine.unavailableReason()).isTrue();
        System.out.printf("[OCR] 引擎加载耗时 %d ms%n", System.currentTimeMillis() - t);
    }

    @Test
    void recognizesRenderedContractPage() throws Exception {
        String text = ocrPdfPage("法务部_原材料采购框架合同_2025-10-01.pdf", 0);
        assertThat(text).contains("合同编号").contains("采购");
    }

    @Test
    void recognizesSealImagePage() throws Exception {
        // 销售合同第 2 页的印章是位图，只能靠 OCR
        String text = ocrPdfPage("销售部_年度设备销售合同_2025-12-25.pdf", 1);
        assertThat(text).contains("有限公司");
    }

    @Test
    void recognizesSyntheticScreenshot() {
        BufferedImage img = new BufferedImage(1400, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1400, 500);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 48));
        g.drawString("关于开展安全生产检查的通知", 200, 120);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 32));
        g.drawString("编制部门：安环部", 120, 260);
        g.drawString("日期：2026年05月18日", 120, 340);
        g.dispose();

        long t = System.currentTimeMillis();
        List<Block> blocks = engine.recognize(img, 1);
        String text = blocks.stream().map(Block::text).collect(Collectors.joining("\n"));
        System.out.printf("[OCR] 合成截图 %d ms：%n%s%n", System.currentTimeMillis() - t, text);
        assertThat(text).contains("安全生产").contains("安环部").contains("2026");
        // 标题行的框高度应大于正文
        assertThat(blocks.get(0).fontSize()).isGreaterThan(blocks.get(blocks.size() - 1).fontSize());
    }

    private String ocrPdfPage(String file, int pageIndex) throws Exception {
        try (PDDocument doc = PDDocument.load(new File(SAMPLES, file))) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(pageIndex, 300, ImageType.RGB);
            long t = System.currentTimeMillis();
            List<Block> blocks = engine.recognize(img, pageIndex + 1);
            String text = blocks.stream()
                    .map(b -> String.format("%.2f %s", b.confidence(), b.text()))
                    .collect(Collectors.joining("\n"));
            System.out.printf("[OCR] %s 第 %d 页 %dx%d，%d ms：%n%s%n", file, pageIndex + 1,
                    img.getWidth(), img.getHeight(), System.currentTimeMillis() - t, text);
            return text;
        }
    }
}
