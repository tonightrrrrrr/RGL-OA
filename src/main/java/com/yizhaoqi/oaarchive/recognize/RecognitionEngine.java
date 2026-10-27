package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 字段识别引擎（技术文档 6.5）：区域切分 → 锚点 → 部门 / 类型 / 日期 / 标题 → 扩展字段 → 是否需要人工确认。
 */
@Component
public class RecognitionEngine {

    /** 第一名与第二名得分差小于该值时也进入待确认 */
    static final double MIN_MARGIN = 0.15;

    /** 模板预览、示例测试用：完全离线，不问小R */
    public RecognitionResult recognize(DocModel doc, TemplateConfig template) {
        return recognize(doc, template, null);
    }

    /**
     * 导入时用。advisor 不为空时：简历的岗位没有匹配的岗位规则，就把岗位名称交给它推测部门（v1.8，小R兜底）。
     */
    public RecognitionResult recognize(DocModel doc, TemplateConfig template, PositionAdvisor advisor) {
        Regions regions = RegionSplitter.split(doc);
        Anchors anchors = Anchors.of(regions);
        FileNameInfo fn = FileNameInfo.of(doc.fileName());
        ResumeDetector.Info resume = ResumeDetector.detect(doc, regions, anchors, fn, template);
        RecognitionContext ctx = new RecognitionContext(doc, regions, anchors, template, fn, resume);

        FieldResult dept = DepartmentRecognizer.recognize(ctx);
        String aiNote = null;
        if (resume.resume() && resume.position() != null && advisor != null && unknown(dept, TemplateConfig.UNKNOWN_DEPT)) {
            List<String> names = template.getDepartments().stream().map(TemplateConfig.DeptDef::getName).toList();
            PositionAdvisor.Suggestion s = advisor.suggest(resume.position(), names);
            if (s != null && names.contains(s.department())) {
                dept = FieldResult.single(s.department(), 0.50, "AI_SUGGEST",
                        "小R根据应聘岗位“" + resume.position() + "”推测" + (s.reason() == null || s.reason().isBlank() ? "" : "：" + s.reason()));
                aiNote = s.reason();
            }
        }
        FieldResult type = DocTypeRecognizer.recognize(ctx);
        FieldResult date = DateRecognizer.recognize(ctx);
        FieldResult title = resume.resume() ? resumeTitle(ctx, dept.value()) : TitleRecognizer.recognize(ctx, dept.value());
        Map<String, String> extras = new LinkedHashMap<>(ExtraFieldExtractor.extract(ctx));
        List<String> keywords = new ArrayList<>(ExtraFieldExtractor.keywords(ctx));
        if (resume.resume()) {
            if (resume.name() != null) {
                extras.put("姓名", resume.name());
            }
            if (resume.position() != null) {
                extras.put("应聘岗位", resume.position());
                keywords.remove(resume.position());
                keywords.add(0, resume.position()); // 台账里能按岗位搜到这份简历
            }
        }

        double threshold = template.getThreshold();
        List<String> reasons = new ArrayList<>();
        boolean forced = false;
        if (resume.resume()) {
            forced = resumeDept(dept, resume, aiNote != null || dept.evidence().stream().anyMatch(e -> e.signal().equals("AI_SUGGEST")), reasons);
        } else {
            check("部门", dept, threshold, true, TemplateConfig.UNKNOWN_DEPT, reasons);
        }
        check("类型", type, threshold, true, TemplateConfig.OTHER_TYPE, reasons);
        if (resume.resume() && date.evidence().stream().anyMatch(e -> e.signal().equals("IMPORT_DATE"))) {
            reasons.add("简历里没有投递日期，暂用导入当天 " + date.value() + "，请确认");
            forced = true;
        } else {
            check("日期", date, threshold, false, null, reasons);
        }
        if (doc.blocks().isEmpty()) {
            reasons.add(0, "没有读到文字内容");
        }
        // 与阈值无关、一定要人工确认的情况：字段识别不出、两个候选太接近、没读到文字、简历的部门是推测的
        boolean blocked = doc.blocks().isEmpty() || forced
                || unknown(dept, TemplateConfig.UNKNOWN_DEPT) || unknown(type, TemplateConfig.OTHER_TYPE) || date.value() == null
                || ambiguous(dept) || ambiguous(type);
        double overall = Math.min(dept.confidence(), Math.min(type.confidence(), date.confidence()));
        return new RecognitionResult(dept, type, date, title, extras, keywords,
                Math.round(overall * 1000) / 1000.0, !reasons.isEmpty(), blocked, reasons, RegionSplitter.describe(regions),
                resume.resume() ? resume.position() : null);
    }

    /** 标题本身就是“个人简历”这类通用词 */
    private static final Pattern GENERIC_RESUME_TITLE = Pattern.compile("^(个人|求职|应聘)?(简历|履历表?|求职信)$");

    /** 简历的标题：“个人简历”这种标题没有区分度，改成“姓名（应聘岗位）” */
    private static FieldResult resumeTitle(RecognitionContext ctx, String department) {
        ResumeDetector.Info r = ctx.resume();
        if (r.name() != null) {
            String t = r.position() == null ? r.name() : r.name() + "（" + r.position() + "）";
            return FieldResult.single(t, 0.80, "RESUME_NAME", "姓名" + (r.position() == null ? "" : "、应聘岗位"));
        }
        FieldResult t = TitleRecognizer.recognize(ctx, department);
        if (r.position() != null && (t.value() == null || GENERIC_RESUME_TITLE.matcher(t.value().strip()).matches())) {
            return FieldResult.single(r.position() + "求职", 0.60, "RESUME_POSITION", "应聘岗位");
        }
        return t;
    }

    /**
     * 简历的部门要不要确认、为什么（v1.8）。返回 true 表示一定要人工确认（与阈值无关）：
     * 部门是根据应聘岗位推测的（规则或小R），用户要求每次都提醒“此人是不是这个部门”。
     */
    private static boolean resumeDept(FieldResult dept, ResumeDetector.Info r, boolean ai, List<String> reasons) {
        String pos = r.position();
        if (unknown(dept, TemplateConfig.UNKNOWN_DEPT)) {
            reasons.add(pos == null
                    ? "简历里没有找到应聘岗位，无法推测部门，请手动选择"
                    : "应聘岗位“" + pos + "”没有匹配的岗位规则，请手动选择部门；也可以在“模板设置 → 岗位规则”里添加");
            return true;
        }
        boolean inferred = !dept.evidence().isEmpty()
                && dept.evidence().stream().allMatch(e -> e.signal().equals("POSITION") || e.signal().equals("AI_SUGGEST"));
        if (!inferred) {
            // 文件名或“应聘部门”写明了部门：按普通字段的规则判断
            return ambiguous(dept);
        }
        String also = dept.candidates().size() > 1 ? "（也可能是" + dept.candidates().get(1).value() + "）" : "";
        if (ai) {
            reasons.add("应聘岗位“" + pos + "”没有匹配的岗位规则，小R推测是" + dept.value() + also + "，请确认此人是否属于" + dept.value());
        } else if (ambiguous(dept)) {
            reasons.add("应聘岗位“" + pos + "”同时符合" + dept.value() + "和" + dept.candidates().get(1).value() + "的岗位规则，请确认部门");
        } else {
            reasons.add("根据应聘岗位“" + pos + "”推测是" + dept.value() + also + "，请确认此人是否属于" + dept.value());
        }
        return true;
    }

    private static boolean unknown(FieldResult r, String unknown) {
        return r.value() == null || r.value().equals(unknown);
    }

    private static boolean ambiguous(FieldResult r) {
        return r.candidates().size() > 1 && r.margin() < MIN_MARGIN;
    }

    private static void check(String name, FieldResult r, double threshold, boolean checkMargin, String unknown, List<String> reasons) {
        if (r.value() == null || r.value().equals(unknown)) {
            reasons.add(name + "未能识别");
        } else if (r.confidence() < threshold) {
            reasons.add(String.format("%s置信度 %.2f 低于阈值 %.2f", name, r.confidence(), threshold));
        } else if (checkMargin && r.candidates().size() > 1 && r.margin() < MIN_MARGIN) {
            reasons.add(String.format("%s有两个接近的候选：%s、%s", name, r.candidates().get(0).value(), r.candidates().get(1).value()));
        }
    }
}
