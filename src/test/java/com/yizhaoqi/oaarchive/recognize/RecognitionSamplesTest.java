package com.yizhaoqi.oaarchive.recognize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.ocr.RapidOcrEngine;
import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.parser.DocParser;
import com.yizhaoqi.oaarchive.parser.DocxParser;
import com.yizhaoqi.oaarchive.parser.ExcelParser;
import com.yizhaoqi.oaarchive.parser.ImageParser;
import com.yizhaoqi.oaarchive.parser.ParserRegistry;
import com.yizhaoqi.oaarchive.parser.PdfParser;
import com.yizhaoqi.oaarchive.parser.TikaFallbackParser;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 识别准确率测试（技术文档 13.3）：
 * A 集（原文件名）必须 100% 正确且全部自动通过；
 * B 集（文件名改成无意义的名字）不允许“静默识别错”——识别错时必须进入待确认。
 */
class RecognitionSamplesTest {

    static final Path SAMPLES = Path.of("src/test/resources/samples/A");
    static ParserRegistry registry;
    static RecognitionEngine engine;
    static TemplateConfig template;
    static List<String[]> expected;

    @BeforeAll
    static void init() throws Exception {
        OaProperties props = new OaProperties();
        RapidOcrEngine ocr = new RapidOcrEngine(props);
        registry = new ParserRegistry(List.of(new DocxParser(), new DocParser(), new ExcelParser(),
                new PdfParser(ocr, props), new ImageParser(ocr)), new TikaFallbackParser());
        engine = new RecognitionEngine();
        try (InputStream in = RecognitionSamplesTest.class.getResourceAsStream("/templates-builtin/default.json")) {
            template = new ObjectMapper().readValue(in, TemplateConfig.class);
        }
        expected = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of("src/test/resources/samples/expected.csv"), StandardCharsets.UTF_8);
        for (String l : lines.subList(1, lines.size())) {
            expected.add(l.split(","));
        }
    }

    @Test
    void setA_originalNames() throws Exception {
        StringBuilder report = new StringBuilder("=== A 集（原文件名）===\n");
        int correct = 0;
        for (String[] e : expected) {
            RecognitionResult r = recognize(SAMPLES.resolve(e[0]), e[0]);
            boolean ok = matches(r, e);
            correct += ok ? 1 : 0;
            report.append(describe(e[0], r, e, ok));
            assertThat(ok).as(e[0] + "\n" + describe(e[0], r, e, ok)).isTrue();
            assertThat(r.needsReview()).as(e[0] + " 不应进入待确认：" + r.reviewReasons()).isFalse();
        }
        report.append("A 集正确 ").append(correct).append("/").append(expected.size()).append('\n');
        Files.writeString(Path.of("target/recognition-A.txt"), report, StandardCharsets.UTF_8);
    }

    @Test
    void setB_meaninglessNames() throws Exception {
        StringBuilder report = new StringBuilder("=== B 集（文件名改为 文档N）===\n");
        int correct = 0, review = 0, silentWrong = 0, n = 0;
        for (String[] e : expected) {
            String ext = e[0].substring(e[0].lastIndexOf('.'));
            String fake = "文档" + (++n) + ext;
            RecognitionResult r = recognize(SAMPLES.resolve(e[0]), fake);
            boolean ok = matches(r, e);
            if (ok) {
                correct++;
            } else if (r.needsReview()) {
                review++;
            } else {
                silentWrong++;
            }
            report.append(describe(fake + " ← " + e[0], r, e, ok));
        }
        report.append(String.format("B 集：正确 %d，进入待确认 %d，静默识别错 %d（共 %d）%n", correct, review, silentWrong, n));
        Files.writeString(Path.of("target/recognition-B.txt"), report, StandardCharsets.UTF_8);
        assertThat(silentWrong).as(report.toString()).isZero();
        assertThat(correct).as(report.toString()).isGreaterThanOrEqualTo(9);
    }

    /** C 集：PDF 第 1 页渲染成图片、文件名改为“扫描件N.png”，只能靠 OCR 识别 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void setC_scannedImages() throws Exception {
        Path dir = Files.createDirectories(Path.of("target/samplesC"));
        StringBuilder report = new StringBuilder("=== C 集（PDF 首页渲染为图片）===\n");
        int correct = 0, review = 0, silentWrong = 0, n = 0;
        for (String[] e : expected) {
            if (!e[0].endsWith(".pdf")) {
                continue;
            }
            Path img = dir.resolve("扫描件" + (++n) + ".png");
            try (PDDocument pdf = PDDocument.load(SAMPLES.resolve(e[0]).toFile())) {
                ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(0, 200, ImageType.RGB), "png", img.toFile());
            }
            RecognitionResult r = recognize(img, img.getFileName().toString());
            boolean ok = matches(r, e);
            if (ok) {
                correct++;
            } else if (r.needsReview()) {
                review++;
            } else {
                silentWrong++;
            }
            report.append(describe(img.getFileName() + " ← " + e[0], r, e, ok));
        }
        report.append(String.format("C 集：正确 %d，进入待确认 %d，静默识别错 %d（共 %d）%n", correct, review, silentWrong, n));
        Files.writeString(Path.of("target/recognition-C.txt"), report, StandardCharsets.UTF_8);
        assertThat(silentWrong).as(report.toString()).isZero();
    }

    private static RecognitionResult recognize(Path file, String name) throws Exception {
        DocModel doc = registry.parse(file, name);
        return engine.recognize(doc, template);
    }

    private static boolean matches(RecognitionResult r, String[] e) {
        return e[1].equals(r.department().value()) && e[2].equals(r.docType().value()) && e[3].equals(r.date().value());
    }

    private static String describe(String name, RecognitionResult r, String[] e, boolean ok) {
        return String.format("%s %s%n  部门 %s (%.2f, 差 %.2f) 期望 %s | 依据 %s%n  类型 %s (%.2f) 期望 %s%n"
                        + "  日期 %s (%.2f) 期望 %s%n  标题 %s%n  待确认 %s %s%n  扩展 %s 关键词 %s%n",
                ok ? "✔" : "✘", name,
                r.department().value(), r.department().confidence(), r.department().margin(), e[1], r.department().evidence(),
                r.docType().value(), r.docType().confidence(), e[2],
                r.date().value(), r.date().confidence(), e[3],
                r.title().value(), r.needsReview(), r.reviewReasons(), r.extras(), r.keywords());
    }
}
