package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 字段识别引擎（技术文档 6.5）：区域切分 → 锚点 → 部门 / 类型 / 日期 / 标题 → 扩展字段 → 是否需要人工确认。
 */
@Component
public class RecognitionEngine {

    /** 第一名与第二名得分差小于该值时也进入待确认 */
    static final double MIN_MARGIN = 0.15;

    public RecognitionResult recognize(DocModel doc, TemplateConfig template) {
        Regions regions = RegionSplitter.split(doc);
        RecognitionContext ctx = new RecognitionContext(doc, regions, Anchors.of(regions), template, FileNameInfo.of(doc.fileName()));

        FieldResult dept = DepartmentRecognizer.recognize(ctx);
        FieldResult type = DocTypeRecognizer.recognize(ctx);
        FieldResult date = DateRecognizer.recognize(ctx);
        FieldResult title = TitleRecognizer.recognize(ctx, dept.value());
        Map<String, String> extras = ExtraFieldExtractor.extract(ctx);
        List<String> keywords = ExtraFieldExtractor.keywords(ctx);

        double threshold = template.getThreshold();
        List<String> reasons = new ArrayList<>();
        check("部门", dept, threshold, true, TemplateConfig.UNKNOWN_DEPT, reasons);
        check("类型", type, threshold, true, TemplateConfig.OTHER_TYPE, reasons);
        check("日期", date, threshold, false, null, reasons);
        if (doc.blocks().isEmpty()) {
            reasons.add(0, "没有读到文字内容");
        }
        // 与阈值无关、一定要人工确认的情况：字段识别不出、两个候选太接近、没读到文字
        boolean blocked = doc.blocks().isEmpty()
                || unknown(dept, TemplateConfig.UNKNOWN_DEPT) || unknown(type, TemplateConfig.OTHER_TYPE) || date.value() == null
                || ambiguous(dept) || ambiguous(type);
        double overall = Math.min(dept.confidence(), Math.min(type.confidence(), date.confidence()));
        return new RecognitionResult(dept, type, date, title, extras, keywords,
                Math.round(overall * 1000) / 1000.0, !reasons.isEmpty(), blocked, reasons, RegionSplitter.describe(regions));
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
