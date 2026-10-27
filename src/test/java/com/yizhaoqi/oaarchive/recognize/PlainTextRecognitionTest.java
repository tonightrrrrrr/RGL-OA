package com.yizhaoqi.oaarchive.recognize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.parser.Block;
import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.8.1：txt 等纯文本没有字号，原来认不出标题，单独一行的成文日期也不算落款，
 * 文件名没有含义时类型、日期置信度偏低，全部进入待确认（技术难点 2.7）。
 */
class PlainTextRecognitionTest {

    static TemplateConfig template;
    static RecognitionEngine engine = new RecognitionEngine();

    @BeforeAll
    static void init() throws Exception {
        try (InputStream in = PlainTextRecognitionTest.class.getResourceAsStream("/templates-builtin/default.json")) {
            template = new ObjectMapper().readValue(in, TemplateConfig.class);
        }
    }

    static RecognitionResult recognize(String name, String text) {
        List<Block> blocks = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                blocks.add(Block.text(line.strip(), 1, 0, null, false));
            }
        }
        return engine.recognize(new DocModel(name, "txt", 1, blocks, Map.of(), false, new ArrayList<>()), template, null);
    }

    @Test
    void officialNoticeInPlainText() {
        RecognitionResult r = recognize("perf_000.txt", """
                XXX有限公司行政部
                关于加强办公秩序管理的通知（第3号）
                各部门、各车间：
                为进一步加强办公秩序管理，经公司研究决定，现将有关事项通知如下：
                一、请各单位于本月底前完成自查。
                特此通知。
                行政部
                2026年1月15日
                """);
        // 发文机关标志和末尾的“（第3号）”都去掉，和 Word、PDF 的规则一致
        assertThat(r.title().value()).isEqualTo("关于加强办公秩序管理的通知");
        assertThat(r.docType().value()).isEqualTo("通知");
        assertThat(r.docType().confidence()).isGreaterThanOrEqualTo(0.75);
        assertThat(r.date().value()).isEqualTo("2026-01-15");
        assertThat(r.date().confidence()).isGreaterThanOrEqualTo(0.75);
        assertThat(r.department().value()).isEqualTo("行政部");
    }

    @Test
    void bodyTextIsNotTakenAsTitle() {
        RecognitionResult r = recognize("备忘.txt", """
                今天和供应商沟通了关于下个月交货安排的通知方式，对方同意改用邮件确认，后续由我跟进。
                2026年3月2日
                """);
        assertThat(r.title().evidence()).noneMatch(e -> e.signal().equals("TITLE_REGION"));
    }
}
