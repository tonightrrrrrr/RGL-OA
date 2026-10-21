package com.yizhaoqi.oaarchive.parser;

import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.ocr.RapidOcrEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 10 个示例文件验证各解析器：都能解析出文字，PDF 阅读顺序正确，Word 标题字号最大。
 * 解析结果写到 target/parse-dump.txt，方便人工检查。
 */
class ParserSamplesTest {

    static final File SAMPLES = new File("src/test/resources/samples/A");
    static ParserRegistry registry;

    @BeforeAll
    static void init() {
        OaProperties props = new OaProperties();
        RapidOcrEngine ocr = new RapidOcrEngine(props);
        registry = new ParserRegistry(List.of(
                new DocxParser(), new DocParser(), new ExcelParser(), new PdfParser(ocr, props), new ImageParser(ocr)),
                new TikaFallbackParser());
    }

    @Test
    void parsesAllSamples() throws Exception {
        StringBuilder dump = new StringBuilder();
        File[] files = SAMPLES.listFiles();
        assertThat(files).isNotNull().hasSize(10);
        Arrays.sort(files);
        for (File f : files) {
            DocModel m = registry.parse(f.toPath(), f.getName());
            dump.append("===== ").append(f.getName()).append(" | ").append(m.format())
                    .append(" | pages=").append(m.pageCount()).append(" | ocr=").append(m.ocrUsed())
                    .append(" | notes=").append(m.notes()).append('\n');
            for (Block b : m.blocks()) {
                dump.append(String.format("[p%d %4.1f%s %s] %s%n", b.page(), b.fontSize(), b.bold() ? "B" : " ",
                        b.source().name().charAt(0), b.text()));
            }
            assertThat(m.blocks()).as(f.getName()).isNotEmpty();
        }
        Files.writeString(Path.of("target/parse-dump.txt"), dump, StandardCharsets.UTF_8);
    }

    @Test
    void pdfReadingOrderIsFixed() throws Exception {
        DocModel m = registry.parse(new File(SAMPLES, "法务部_原材料采购框架合同_2025-10-01.pdf").toPath(), "x.pdf");
        String text = m.fullText();
        // Tika 抽取时“第三条”会出现在“第二条”前面（技术文档 3.2 F6）
        assertThat(text.indexOf("第二条")).isPositive().isLessThan(text.indexOf("第三条"));
    }

    @Test
    void docxTitleHasLargestFont() throws Exception {
        DocModel m = registry.parse(new File(SAMPLES, "人力资源部_关于张明等同志职务任免的通知_2026-03-15.docx").toPath(), "x.docx");
        Block first = m.blocks().get(0);
        assertThat(first.text()).isEqualTo("关于张明等同志职务任免的通知");
        assertThat(first.fontSize()).isEqualTo(22f);
        assertThat(m.blocks().stream().skip(1).mapToDouble(Block::fontSize).max().orElse(0)).isLessThan(22);
    }

    @Test
    void cleansWpsSpacing() {
        assertThat(TextCleaner.clean("编制日期：2026 年 01 月 05 日")).isEqualTo("编制日期：2026年01月05日");
        assertThat(TextCleaner.clean("XXX 有限公司")).isEqualTo("XXX有限公司");
        assertThat(TextCleaner.clean("ＨＣ－２０２５")).isEqualTo("HC－2025");
        assertThat(TextCleaner.clean("HC-JC-2000 型 AI 智能")).isEqualTo("HC-JC-2000型AI智能");
    }
}
