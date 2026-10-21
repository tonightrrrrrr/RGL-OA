package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateConfig.TypeDef;

import java.util.List;

/**
 * 文件类型识别（技术文档 6.5.4）：标题后缀为主，文件名、内容特征、表格特征为辅。
 * 后缀取“最靠近结尾且最长”的那个，例如“……工作总结暨……规划报告”判为报告，“……会议纪要”判为会议纪要而不是“纪要”。
 */
final class DocTypeRecognizer {

    private static final String[] TABLE_HINTS = {"合计", "人数", "人次", "金额", "率", "数量", "单价", "小计", "序号"};

    private DocTypeRecognizer() {
    }

    static FieldResult recognize(RecognitionContext ctx) {
        List<TypeDef> types = ctx.template().getDocTypes();
        ScoreBoard sb = new ScoreBoard();

        String title = stripTail(ctx.regions().titleText());
        if (!title.isEmpty()) {
            Match end = suffixAtEnd(types, title);
            if (end != null) {
                sb.add(end.type.getName(), "TITLE_SUFFIX", title, 0.80);
            } else {
                Match in = suffixInside(types, title);
                if (in != null) {
                    sb.add(in.type.getName(), "TITLE_CONTAINS", title, 0.55);
                }
            }
        }

        String name = String.join("", ctx.fileName().segments());
        if (!name.isEmpty()) {
            Match end = suffixAtEnd(types, name);
            if (end != null) {
                sb.add(end.type.getName(), "FILENAME", ctx.fileName().original(), 0.70);
            } else {
                Match in = suffixInside(types, name);
                if (in != null) {
                    sb.add(in.type.getName(), "FILENAME_CONTAINS", ctx.fileName().original(), 0.50);
                }
            }
        }

        String text = ctx.fullText();
        for (TypeDef t : types) {
            List<String> hits = t.getFeatures().stream().filter(text::contains).toList();
            if (!hits.isEmpty()) {
                sb.add(t.getName(), "CONTENT_FEATURE", "含“" + String.join("、", hits) + "”", hits.size() >= 2 ? 0.55 : 0.35);
            }
        }

        String format = ctx.doc().format();
        if ("xlsx".equals(format) || "xls".equals(format) || "ods".equals(format)) {
            String head = ctx.doc().blocks().isEmpty() ? "" : ctx.doc().blocks().get(0).text();
            long hints = java.util.Arrays.stream(TABLE_HINTS).filter(text::contains).count();
            TypeDef report = ctx.template().findType("报表");
            if (report != null) {
                sb.add(report.getName(), "TABLE_FEATURE", "表格文件，表头：" + head, hints >= 2 ? 0.65 : 0.50);
            }
        }
        return sb.result(TemplateConfig.OTHER_TYPE);
    }

    /** 去掉标题末尾的括号说明和标点，如“……通知（第二版）” */
    static String stripTail(String s) {
        return s.replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]$", "").replaceAll("[。．.！!？?\\s]+$", "").strip();
    }

    private record Match(TypeDef type, String suffix, int end) {
    }

    private static Match suffixAtEnd(List<TypeDef> types, String s) {
        Match best = null;
        for (TypeDef t : types) {
            for (String suf : t.getSuffixes()) {
                if (s.endsWith(suf) && (best == null || suf.length() > best.suffix.length())) {
                    best = new Match(t, suf, s.length());
                }
            }
        }
        return best;
    }

    private static Match suffixInside(List<TypeDef> types, String s) {
        Match best = null;
        for (TypeDef t : types) {
            for (String suf : t.getSuffixes()) {
                int idx = s.lastIndexOf(suf);
                if (idx < 0) {
                    continue;
                }
                int end = idx + suf.length();
                if (best == null || end > best.end || end == best.end && suf.length() > best.suffix.length()) {
                    best = new Match(t, suf, end);
                }
            }
        }
        return best;
    }
}
