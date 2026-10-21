package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 锚点字段：形如“编制部门：销售部”的“字段名：字段值”（技术文档 附录 B）。
 * 字段值为空时（如“编制单位：”换行后才写单位），取下一行作为值。
 */
public final class Anchors {

    public enum Zone { TITLE, HEADER, BODY, FOOTER }

    public record Anchor(String label, String value, Zone zone, String raw) {
    }

    private static final Pattern LABEL = Pattern.compile("^([^：:，。；、]{2,12})[：:]\\s*(.*)$");
    private static final Pattern BRACKET = Pattern.compile("[（(][^）)]*[）)]");

    private final List<Anchor> items;

    private Anchors(List<Anchor> items) {
        this.items = items;
    }

    public static Anchors of(Regions r) {
        List<Anchor> out = new ArrayList<>();
        collect(r.header(), Zone.HEADER, out);
        collect(r.body(), Zone.BODY, out);
        collect(r.footer(), Zone.FOOTER, out);
        return new Anchors(out);
    }

    private static void collect(List<Block> blocks, Zone zone, List<Anchor> out) {
        for (int i = 0; i < blocks.size(); i++) {
            String text = blocks.get(i).text();
            Matcher m = LABEL.matcher(text);
            if (!m.find()) {
                continue;
            }
            String label = BRACKET.matcher(m.group(1)).replaceAll("").strip();
            String value = m.group(2).strip();
            if (value.isEmpty() && i + 1 < blocks.size() && !LABEL.matcher(blocks.get(i + 1).text()).find()) {
                value = blocks.get(i + 1).text().strip();
            }
            out.add(new Anchor(label, value, zone, text));
        }
    }

    public List<Anchor> all() {
        return items;
    }

    /** 字段名完全等于其中之一的锚点 */
    public List<Anchor> withLabel(String... labels) {
        List<Anchor> out = new ArrayList<>();
        for (Anchor a : items) {
            for (String l : labels) {
                if (a.label().equals(l)) {
                    out.add(a);
                    break;
                }
            }
        }
        return out;
    }

    /** 字段名以某个词结尾，如“……负责人”“……经理” */
    public List<Anchor> labelEndsWith(String... suffixes) {
        List<Anchor> out = new ArrayList<>();
        for (Anchor a : items) {
            for (String s : suffixes) {
                if (a.label().endsWith(s)) {
                    out.add(a);
                    break;
                }
            }
        }
        return out;
    }

    public String firstValue(String... labels) {
        return withLabel(labels).stream().map(Anchor::value).filter(v -> !v.isBlank()).findFirst().orElse(null);
    }
}
