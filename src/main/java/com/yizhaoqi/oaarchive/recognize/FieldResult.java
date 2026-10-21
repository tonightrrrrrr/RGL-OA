package com.yizhaoqi.oaarchive.recognize;

import java.util.List;

/**
 * 单个字段的识别结果（技术文档 6.5.1）。
 *
 * @param value      识别值；无法识别时为模板中的兜底值
 * @param confidence 置信度 0–1
 * @param margin     与第二名候选的得分差；只有一个候选时等于 confidence
 * @param evidence   判断依据，按权重从高到低
 * @param candidates 候选值及得分，供界面下拉选择
 */
public record FieldResult(String value, double confidence, double margin,
                          List<Evidence> evidence, List<Candidate> candidates) {

    public record Evidence(String signal, String snippet, double weight) {
    }

    public record Candidate(String value, double score) {
    }

    public static FieldResult none(String fallback) {
        return new FieldResult(fallback, 0, 0, List.of(), List.of());
    }

    public static FieldResult single(String value, double confidence, String signal, String snippet) {
        return new FieldResult(value, confidence, confidence,
                List.of(new Evidence(signal, snippet, confidence)), List.of(new Candidate(value, confidence)));
    }
}
