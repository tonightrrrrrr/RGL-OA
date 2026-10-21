package com.yizhaoqi.oaarchive.recognize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多信号打分（技术文档 6.5.2）：同一候选值、同一种信号只取最高权重，
 * 不同信号之间用 1 − ∏(1 − wᵢ) 合成。这样正文里重复出现同一个词不会无限抬高分数。
 */
final class ScoreBoard {

    private static final int SNIPPET_MAX = 60;
    private final Map<String, Map<String, FieldResult.Evidence>> byValue = new LinkedHashMap<>();

    void add(String value, String signal, String snippet, double weight) {
        if (value == null || value.isBlank() || weight <= 0) {
            return;
        }
        String s = snippet == null ? "" : snippet.length() > SNIPPET_MAX ? snippet.substring(0, SNIPPET_MAX) + "…" : snippet;
        byValue.computeIfAbsent(value, k -> new LinkedHashMap<>())
                .merge(signal, new FieldResult.Evidence(signal, s, weight), (a, b) -> a.weight() >= b.weight() ? a : b);
    }

    boolean isEmpty() {
        return byValue.isEmpty();
    }

    FieldResult result(String fallback) {
        if (byValue.isEmpty()) {
            return FieldResult.none(fallback);
        }
        List<FieldResult.Candidate> candidates = new ArrayList<>();
        for (var e : byValue.entrySet()) {
            double miss = 1;
            for (FieldResult.Evidence ev : e.getValue().values()) {
                miss *= 1 - ev.weight();
            }
            candidates.add(new FieldResult.Candidate(e.getKey(), round(1 - miss)));
        }
        candidates.sort(Comparator.comparingDouble(FieldResult.Candidate::score).reversed());
        FieldResult.Candidate top = candidates.get(0);
        double second = candidates.size() > 1 ? candidates.get(1).score() : 0;
        List<FieldResult.Evidence> evidence = new ArrayList<>(byValue.get(top.value()).values());
        evidence.sort(Comparator.comparingDouble(FieldResult.Evidence::weight).reversed());
        return new FieldResult(top.value(), top.score(), round(top.score() - second), evidence,
                candidates.subList(0, Math.min(5, candidates.size())));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
