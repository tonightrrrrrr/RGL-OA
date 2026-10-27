package com.yizhaoqi.oaarchive.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 OA 模板的内容（技术文档 6.6.1）。以 JSON 形式保存在数据库，可以导入导出。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TemplateConfig {

    public static final String UNKNOWN_DEPT = "未识别部门";
    public static final String OTHER_TYPE = "其他";

    /** 公司名称：用于去掉标题前缀 */
    private String companyName = "XXX有限公司";
    /** 是否去掉标题开头的公司名 */
    private boolean stripCompanyPrefix = true;
    private List<DeptDef> departments = new ArrayList<>();
    private List<TypeDef> docTypes = new ArrayList<>();
    /** 关键词 → 部门 推断规则 */
    private List<KeywordRule> keywordRules = new ArrayList<>();
    /**
     * 岗位 → 部门 推断规则（v1.8）：只用于简历，按“应聘岗位”推测这个人属于哪个部门，比如“软件工程师”→ 研发部。
     * 和关键词规则分开：简历正文里的“招聘”“绩效”多半是以前的工作内容，不能说明应聘哪个部门。
     */
    private List<KeywordRule> positionRules = new ArrayList<>();
    /** 新文件名格式（不含扩展名） */
    private String namingPattern = "{部门}_{类型}_{日期}_{标题}";
    /** 归档目录层级，用 / 分隔 */
    private String pathPattern = "{年}/{部门}/{类型}";
    private String archiveNoPattern = "{年}-{部门代码}-{类型代码}-{序号4}";
    private String dateFormat = "yyyy-MM-dd";
    /** 置信度阈值，低于此值进入待确认 */
    private double threshold = 0.75;
    /** COPY 或 MOVE */
    private String archiveMode = "COPY";

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeptDef {
        private String name;
        private String code;
        private List<String> aliases = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TypeDef {
        private String name;
        private String code;
        /** 标题后缀，如“通知”“会议纪要” */
        private List<String> suffixes = new ArrayList<>();
        /** 内容特征，如“合同编号”“甲方” */
        private List<String> features = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeywordRule {
        private String department;
        private List<String> keywords = new ArrayList<>();
    }

    public DeptDef findDept(String name) {
        return departments.stream().filter(d -> d.getName().equals(name)).findFirst().orElse(null);
    }

    public TypeDef findType(String name) {
        return docTypes.stream().filter(t -> t.getName().equals(name)).findFirst().orElse(null);
    }
}
