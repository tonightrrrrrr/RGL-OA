package com.yizhaoqi.oaarchive.template;

import com.yizhaoqi.oaarchive.archive.FileNameSanitizer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板占位符渲染（技术文档 6.6.2）：{部门} {部门代码} {类型} {类型代码} {标题} {日期} {年} {月} {序号4} {档号} {原文件名}。
 */
public final class NamingRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private NamingRenderer() {
    }

    /** 渲染所需的字段值 */
    public record Fields(String department, String docType, LocalDate date, String title, String originalName) {
    }

    public static Map<String, String> variables(Fields f, TemplateConfig tpl, int seq) {
        Map<String, String> v = new LinkedHashMap<>();
        TemplateConfig.DeptDef dept = tpl.findDept(f.department());
        TemplateConfig.TypeDef type = tpl.findType(f.docType());
        v.put("部门", blankTo(f.department(), TemplateConfig.UNKNOWN_DEPT));
        v.put("部门代码", dept != null && dept.getCode() != null ? dept.getCode() : "WZ");
        v.put("类型", blankTo(f.docType(), TemplateConfig.OTHER_TYPE));
        v.put("类型代码", type != null && type.getCode() != null ? type.getCode() : "QT");
        v.put("标题", blankTo(f.title(), "未命名"));
        if (f.date() != null) {
            v.put("日期", f.date().format(DateTimeFormatter.ofPattern(blankTo(tpl.getDateFormat(), "yyyy-MM-dd"))));
            v.put("年", String.valueOf(f.date().getYear()));
            v.put("月", String.format("%02d", f.date().getMonthValue()));
        } else {
            v.put("日期", "无日期");
            v.put("年", "未知年份");
            v.put("月", "00");
        }
        v.put("序号4", seq > 0 ? String.format("%04d", seq) : "0000");
        String base = f.originalName() == null ? "" : f.originalName();
        int dot = base.lastIndexOf('.');
        v.put("原文件名", dot > 0 ? base.substring(0, dot) : base);
        v.put("档号", render(blankTo(tpl.getArchiveNoPattern(), "{年}-{部门代码}-{类型代码}-{序号4}"), v));
        return v;
    }

    public static String render(String pattern, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(pattern == null ? "" : pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1).strip(), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 新文件名（不含扩展名），已清洗 */
    public static String fileName(TemplateConfig tpl, Map<String, String> vars) {
        return FileNameSanitizer.sanitize(render(tpl.getNamingPattern(), vars));
    }

    /** 归档目录的各层名称，已清洗 */
    public static List<String> dirSegments(TemplateConfig tpl, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (String seg : render(tpl.getPathPattern(), vars).split("[/\\\\]+")) {
            if (!seg.isBlank()) {
                out.add(FileNameSanitizer.sanitize(seg));
            }
        }
        return out;
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
