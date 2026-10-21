package com.yizhaoqi.oaarchive.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.template.TemplateAnalyzer.Issue;
import com.yizhaoqi.oaarchive.template.TemplateAnalyzer.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateAnalyzerTest {

    TemplateConfig def;

    @BeforeEach
    void load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/templates-builtin/default.json")) {
            def = new ObjectMapper().readValue(in, TemplateConfig.class);
        }
    }

    @Test
    void builtinDefaultHasNoErrorsOrWarnings() {
        List<Issue> issues = TemplateAnalyzer.lint(def);
        issues.forEach(i -> System.out.println("[默认模板] " + i.level() + " " + i.message()));
        assertThat(issues).noneMatch(i -> i.level() == Level.ERROR);
        assertThat(issues).noneMatch(i -> i.level() == Level.WARNING);
        TemplateService.validate(def); // 不抛异常
    }

    @Test
    void detectsCommonMistakes() {
        def.setNamingPattern("{部门}_{类型}_{日其}");
        def.setArchiveNoPattern("{年}-{部门代码}");
        def.setDateFormat("yyyy/MM/dd");
        def.setThreshold(0.5);
        def.getDepartments().get(1).setCode(def.getDepartments().get(0).getCode());
        def.getKeywordRules().get(0).setDepartment("市场部");
        def.getKeywordRules().get(1).getKeywords().add("合");

        List<Issue> issues = TemplateAnalyzer.lint(def);
        assertThat(issues).anyMatch(i -> i.level() == Level.ERROR && i.message().contains("{日其}"));
        assertThat(issues).anyMatch(i -> i.level() == Level.ERROR && i.message().contains("市场部")
                && i.fix() != null && "ADD_DEPT".equals(i.fix().kind()));
        assertThat(issues).anyMatch(i -> i.message().contains("没有 {标题}") && i.fix() != null);
        assertThat(issues).anyMatch(i -> i.message().contains("没有 {序号4}") && "APPEND_ARCHIVE_NO".equals(i.fix().kind()));
        assertThat(issues).anyMatch(i -> i.area().equals("日期") && i.message().contains("斜杠"));
        assertThat(issues).anyMatch(i -> i.area().equals("阈值") && i.level() == Level.WARNING);
        assertThat(issues).anyMatch(i -> i.message().contains("共用"));
        assertThat(issues).anyMatch(i -> i.message().contains("“合”只有一个字"));
        // 错误排在前面
        assertThat(issues.get(0).level()).isEqualTo(Level.ERROR);
        assertThatThrownBy(() -> TemplateService.validate(def)).hasMessageContaining("无法保存");
    }

    @Test
    void invalidDateFormatIsError() {
        def.setDateFormat("yyyy-MM-dd {{");
        assertThat(TemplateAnalyzer.lint(def)).anyMatch(i -> i.level() == Level.ERROR && i.area().equals("日期"));
    }

    @Test
    void diffAgainstBase() throws Exception {
        TemplateConfig target = new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(def), TemplateConfig.class);
        target.setNamingPattern("{部门}_{标题}_{日期}");
        TemplateConfig.DeptDef market = new TemplateConfig.DeptDef();
        market.setName("市场部");
        market.setCode("SB");
        target.getDepartments().add(market);
        target.getDepartments().removeIf(d -> d.getName().equals("董秘办"));
        target.getDepartments().get(0).getAliases().add("办公室");
        target.getKeywordRules().get(2).getKeywords().add("发票");

        TemplateAnalyzer.Diff d = TemplateAnalyzer.diff(def, target);
        assertThat(d.settings()).singleElement().satisfies(f -> {
            assertThat(f.label()).isEqualTo("命名规则");
            assertThat(f.target()).isEqualTo("{部门}_{标题}_{日期}");
        });
        assertThat(d.departments()).extracting(TemplateAnalyzer.ItemChange::name, TemplateAnalyzer.ItemChange::change)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("市场部", "ADDED"),
                        org.assertj.core.groups.Tuple.tuple("董秘办", "REMOVED"),
                        org.assertj.core.groups.Tuple.tuple("行政部", "CHANGED"));
        assertThat(d.keywords()).singleElement().satisfies(k -> assertThat(k.added()).containsExactly("发票"));
        assertThat(d.total()).isEqualTo(5);
        assertThat(TemplateAnalyzer.diff(def, def).total()).isZero();
    }
}
