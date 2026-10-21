package com.yizhaoqi.oaarchive.recognize;

import com.hankcs.hanlp.HanLP;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扩展字段（技术文档 6.5.6）：合同编号、文号、甲乙方、金额、编制人等，只写入台账，不参与命名。
 */
final class ExtraFieldExtractor {

    private static final Pattern DOC_NO = Pattern.compile("[\\u4e00-\\u9fa5]{1,8}[〔\\[［(（]\\s*(19|20)\\d{2}\\s*[〕\\]］)）]\\s*\\d{1,4}\\s*号");
    private static final Pattern AMOUNT = Pattern.compile("(?:合同)?总金额[：:]?\\s*(?:人民币)?\\s*([\\d,.]+\\s*(?:万元|元|亿元))");

    private ExtraFieldExtractor() {
    }

    static Map<String, String> extract(RecognitionContext ctx) {
        Anchors a = ctx.anchors();
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "合同编号", a.firstValue("合同编号", "合同号", "编号"));
        put(out, "甲方", a.firstValue("甲方"));
        put(out, "乙方", a.firstValue("乙方"));
        put(out, "签订地点", a.firstValue("签订地点"));
        put(out, "编制人", a.firstValue("编制人", "制表人", "起草人"));
        put(out, "记录人", a.firstValue("记录人"));
        put(out, "审核人", a.firstValue("审核人"));
        put(out, "主持人", a.firstValue("主持人"));
        String text = ctx.fullText();
        Matcher no = DOC_NO.matcher(text);
        if (no.find()) {
            out.put("文号", no.group());
        }
        Matcher amt = AMOUNT.matcher(text);
        if (amt.find()) {
            out.put("金额", amt.group(1).replace(" ", ""));
        }
        return out;
    }

    /** HanLP 提取关键词，去掉部门名、类型名和纯数字，最多 5 个 */
    static List<String> keywords(RecognitionContext ctx) {
        String text = ctx.regions().titleText() + "\n" + Regions.join(ctx.regions().body());
        if (text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            for (String w : HanLP.extractKeyword(text.length() > 6000 ? text.substring(0, 6000) : text, 12)) {
                if (w.length() < 2 || w.matches("[\\d.%]+") || out.contains(w)) {
                    continue;
                }
                boolean isDict = ctx.template().getDepartments().stream().anyMatch(d -> d.getName().equals(w))
                        || ctx.template().getDocTypes().stream().anyMatch(t -> t.getName().equals(w));
                if (!isDict) {
                    out.add(w);
                }
                if (out.size() >= 5) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // 关键词只是辅助信息，提取失败不影响识别
        }
        return out;
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank() && value.length() <= 80) {
            m.put(key, value.strip());
        }
    }
}
