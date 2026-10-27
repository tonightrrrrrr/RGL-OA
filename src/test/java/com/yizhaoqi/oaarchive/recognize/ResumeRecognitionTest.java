package com.yizhaoqi.oaarchive.recognize;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.8 简历按应聘岗位推测部门：src/main/resources/samples-resume 里的虚构简历，对照 cases.json 检查
 * 部门、应聘岗位、日期、标题和提示；推测出来的部门一律进入待确认；不是简历的招聘通知按普通文件识别。
 */
class ResumeRecognitionTest {

    static final Path DIR = Path.of("src/main/resources/samples-resume");
    static ParserRegistry registry;
    static RecognitionEngine engine;
    static TemplateConfig template;

    @BeforeAll
    static void init() throws Exception {
        OaProperties props = new OaProperties();
        RapidOcrEngine ocr = new RapidOcrEngine(props);
        registry = new ParserRegistry(List.of(new DocxParser(), new DocParser(), new ExcelParser(),
                new PdfParser(ocr, props), new ImageParser(ocr)), new TikaFallbackParser());
        engine = new RecognitionEngine();
        try (InputStream in = ResumeRecognitionTest.class.getResourceAsStream("/templates-builtin/default.json")) {
            template = new ObjectMapper().readValue(in, TemplateConfig.class);
        }
    }

    RecognitionResult recognize(String file, PositionAdvisor advisor) throws Exception {
        DocModel doc = registry.parse(DIR.resolve(file), file);
        return engine.recognize(doc, template, advisor);
    }

    @Test
    void samplesMatchCases() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(DIR.resolve("cases.json")));
        List<String> problems = new ArrayList<>();
        for (JsonNode c : cases) {
            String file = c.get("file").asText();
            RecognitionResult r = recognize(file, null);
            boolean resume = c.path("resume").asBoolean(true);
            String line = file + " → 部门 " + r.department().value() + "，岗位 " + r.position() + "，日期 " + r.date().value()
                    + "，标题 " + r.title().value() + "，类型 " + r.docType().value() + "，待确认 " + r.needsReview() + "，原因 " + r.reviewReasons();
            System.out.println("RESUME " + line);
            if (!c.get("department").asText().equals(r.department().value())) {
                problems.add("部门不对：" + line);
            }
            if (resume) {
                if (!c.get("position").asText().equals(r.position())) {
                    problems.add("岗位不对：" + line);
                }
                if (!"简历".equals(r.docType().value())) {
                    problems.add("类型不是简历：" + line);
                }
                // 简历一律要人工确认：部门是推测的，或者日期是导入当天
                if (!r.needsReview() || !r.blocked()) {
                    problems.add("没有进入待确认：" + line);
                }
                String date = c.path("date").isNull() ? LocalDate.now().toString() : c.get("date").asText();
                if (!date.equals(r.date().value())) {
                    problems.add("日期不对（不能用出生日期或工作经历里的日期）：" + line);
                }
                if (!c.get("title").asText().equals(r.title().value())) {
                    problems.add("标题不对：" + line);
                }
                if (c.hasNonNull("reason") && r.reviewReasons().stream().noneMatch(x -> x.contains(c.get("reason").asText()))) {
                    problems.add("提示里没有“" + c.get("reason").asText() + "”：" + line);
                }
                if (c.path("reason").isNull() && r.reviewReasons().stream().anyMatch(x -> x.contains("推测"))) {
                    problems.add("写明了应聘部门，不应该提示推测：" + line);
                }
                assertThat(r.extras()).containsEntry("应聘岗位", r.position());
                assertThat(r.keywords()).first().isEqualTo(r.position());
            } else {
                if (r.position() != null) {
                    problems.add("不是简历，却取了应聘岗位：" + line);
                }
                if (c.hasNonNull("date") && !c.get("date").asText().equals(r.date().value())) {
                    problems.add("日期不对：" + line);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void unmatchedPositionAsksAdvisorWithPositionOnly() throws Exception {
        AtomicReference<String> askedPosition = new AtomicReference<>();
        AtomicReference<List<String>> askedDepts = new AtomicReference<>();
        PositionAdvisor advisor = (position, departments) -> {
            askedPosition.set(position);
            askedDepts.set(departments);
            return new PositionAdvisor.Suggestion("研发部", "插画常配合游戏研发");
        };
        RecognitionResult r = recognize("个人简历_陈晨.docx", advisor);
        // 只把岗位名称和部门名称交给小R
        assertThat(askedPosition.get()).isEqualTo("插画师");
        assertThat(askedDepts.get()).contains("研发部", "人力资源部");
        assertThat(r.department().value()).isEqualTo("研发部");
        assertThat(r.department().evidence().get(0).signal()).isEqualTo("AI_SUGGEST");
        assertThat(r.blocked()).isTrue();
        assertThat(r.reviewReasons()).anyMatch(x -> x.contains("小R推测是研发部") && x.contains("请确认此人是否属于研发部"));

        // 小R的回答不在部门列表里：不用，部门仍未识别
        RecognitionResult bad = recognize("个人简历_陈晨.docx", (p, d) -> new PositionAdvisor.Suggestion("美术部", ""));
        assertThat(bad.department().value()).isEqualTo(TemplateConfig.UNKNOWN_DEPT);

        // 规则能匹配上的岗位不问小R
        AtomicReference<String> notAsked = new AtomicReference<>();
        recognize("个人简历_张伟.docx", (p, d) -> {
            notAsked.set(p);
            return null;
        });
        assertThat(notAsked.get()).isNull();
    }

    @Test
    void positionMatchingRules() {
        // 更长的词更具体；一样长时取靠后的；英文缩写要整词出现
        assertThat(ResumeDetector.match("网络安全工程师", template)).first().extracting(ResumeDetector.Match::department).isEqualTo("研发部");
        assertThat(ResumeDetector.match("安全工程师", template)).first().extracting(ResumeDetector.Match::department).isEqualTo("安环部");
        assertThat(ResumeDetector.match("销售会计", template)).extracting(ResumeDetector.Match::department).containsExactly("财务部", "销售部");
        assertThat(ResumeDetector.match("董事会秘书", template)).first().extracting(ResumeDetector.Match::department).isEqualTo("董秘办");
        assertThat(ResumeDetector.match("HR专员", template)).first().extracting(ResumeDetector.Match::department).isEqualTo("人力资源部");
        assertThat(ResumeDetector.match("Chrome插件开发", template)).noneMatch(m -> m.department().equals("人力资源部"));
        assertThat(ResumeDetector.match("插画师", template)).isEmpty();
        assertThat(ResumeDetector.clean("Java开发工程师 | 期望城市：上海")).isEqualTo("Java开发工程师");
        assertThat(ResumeDetector.clean("面议")).isNull();
    }
}
