package com.yizhaoqi.oaarchive.parser;

import java.util.regex.Pattern;

/**
 * 文本清洗（技术文档 6.2.3）：全角数字字母转半角，删除中文与中文、数字、字母之间多余的空格。
 * WPS 导出的 PDF 常见“2026 年 01 月 05 日”“XXX 有限公司”这类多余空格。
 */
public final class TextCleaner {

    private static final String CJK = "\\u3400-\\u9fff\\uf900-\\ufaff\\u3000-\\u303f\\uff00-\\uffef";
    private static final Pattern CJK_GAP = Pattern.compile("(?<=[" + CJK + "])[ \\t\\u00a0]+(?=[" + CJK + "0-9A-Za-z])");
    private static final Pattern GAP_CJK = Pattern.compile("(?<=[0-9A-Za-z])[ \\t\\u00a0]+(?=[" + CJK + "])");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00a0]{2,}");

    private TextCleaner() {
    }

    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = toHalfWidth(s);
        t = CJK_GAP.matcher(t).replaceAll("");
        t = GAP_CJK.matcher(t).replaceAll("");
        t = MULTI_SPACE.matcher(t).replaceAll(" ");
        return t.strip();
    }

    /** 只把全角数字、字母和空格转半角；中文标点（如“：”）保留，锚点匹配时两种冒号都兼容 */
    static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '０' && c <= '９' || c >= 'Ａ' && c <= 'Ｚ' || c >= 'ａ' && c <= 'ｚ') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
