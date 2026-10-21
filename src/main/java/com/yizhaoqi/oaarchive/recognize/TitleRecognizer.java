package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.template.TemplateConfig;

import java.util.regex.Pattern;

/**
 * 标题识别（技术文档 6.5.5）：取标题区文字，去掉公司名前缀；
 * 部门名放在开头时也去掉（命名规则里已有 {部门}），没有标题区时用文件名去掉部门、日期后的部分。
 */
final class TitleRecognizer {

    private static final int MAX_LEN = 60;
    private static final Pattern COMPANY_LIKE = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z*Ｘx]{2,20}(有限责任公司|股份有限公司|有限公司|集团|公司)");

    private TitleRecognizer() {
    }

    static FieldResult recognize(RecognitionContext ctx, String department) {
        TemplateConfig tpl = ctx.template();
        String raw = DocTypeRecognizer.stripTail(ctx.regions().titleText());
        if (!raw.isEmpty()) {
            String t = clean(raw, tpl, department);
            return FieldResult.single(limit(t), 0.90, "TITLE_REGION", raw);
        }
        FileNameInfo fn = ctx.fileName();
        String fromName = String.join("", fn.segments().stream()
                .filter(s -> tpl.getDepartments().stream().noneMatch(d -> d.getName().equals(s) || d.getAliases().contains(s)))
                .toList());
        if (!fromName.isBlank()) {
            double conf = fn.meaningful() ? 0.60 : 0.30;
            return FieldResult.single(limit(clean(fromName, tpl, department)), conf, "FILENAME", fn.original());
        }
        return FieldResult.single(limit(fn.base()), 0.20, "FILENAME", fn.original());
    }

    private static String clean(String title, TemplateConfig tpl, String department) {
        String t = title.strip();
        if (tpl.isStripCompanyPrefix()) {
            String company = tpl.getCompanyName();
            if (company != null && !company.isBlank() && t.startsWith(company) && t.length() - company.length() >= 4) {
                t = t.substring(company.length());
            } else {
                var m = COMPANY_LIKE.matcher(t);
                if (m.find() && t.length() - m.end() >= 4) {
                    t = t.substring(m.end());
                }
            }
        }
        if (department != null && t.startsWith(department) && t.length() - department.length() >= 4) {
            t = t.substring(department.length());
        }
        return t.strip();
    }

    private static String limit(String s) {
        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s;
    }
}
