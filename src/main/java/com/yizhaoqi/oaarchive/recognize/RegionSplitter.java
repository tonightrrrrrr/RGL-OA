package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.Block;
import com.yizhaoqi.oaarchive.parser.DocModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 区域切分（技术文档 6.4.1）：
 * 标题 = 文档开头字号明显大于正文的连续行；落款 = 从末尾往上的“日期/落款/盖章/xx人”等行；
 * 抬头 = 标题之后、正文开始之前的“字段名：字段值”行。
 */
public final class RegionSplitter {

    private static final int HEADER_MAX = 12;
    private static final int FOOTER_SCAN = 15;
    private static final Pattern LABEL_LINE = Pattern.compile("^[^：:，。；]{2,12}[：:]");
    private static final Pattern BODY_START = Pattern.compile(
            "^([一二三四五六七八九十]+[、.．]|第[一二三四五六七八九十]+[条章]|\\d+[、.．]|本次|根据|为了|为适配|经研究|现将|按照)");
    private static final Pattern FOOTER_LINE = Pattern.compile(
            "^(落款|日期|签订日期|盖章|公司盖章|盖章处|签字|签名|(制表|编制|记录|审核|审批|批准|经办|负责|起草)人|"
                    + "[^：:]{0,8}(负责人|经理|总监|主任|主管)[：:])");

    private RegionSplitter() {
    }

    public static Regions split(DocModel doc) {
        List<Block> blocks = doc.blocks();
        if (blocks.isEmpty()) {
            return new Regions(List.of(), List.of(), List.of(), List.of());
        }
        int titleEnd = findTitleEnd(blocks);
        int footerStart = findFooterStart(blocks, titleEnd);
        int headerEnd = titleEnd;
        while (headerEnd < footerStart && headerEnd - titleEnd < HEADER_MAX) {
            String t = blocks.get(headerEnd).text();
            if (BODY_START.matcher(t).find() || t.length() > 60) {
                break;
            }
            if (!LABEL_LINE.matcher(t).find() && headerEnd > titleEnd) {
                break;
            }
            headerEnd++;
        }
        return new Regions(
                List.copyOf(blocks.subList(0, titleEnd)),
                List.copyOf(blocks.subList(titleEnd, headerEnd)),
                List.copyOf(blocks.subList(headerEnd, footerStart)),
                List.copyOf(blocks.subList(footerStart, blocks.size())));
    }

    /** 返回标题区结束位置（不含）；没有标题时返回 0 */
    private static int findTitleEnd(List<Block> blocks) {
        float body = dominantFontSize(blocks);
        if (body <= 0) {
            return 0;
        }
        // 标题必须在文档最开头（前 3 行内），前面不能有正文
        int start = -1;
        for (int i = 0; i < Math.min(3, blocks.size()); i++) {
            if (isTitleCandidate(blocks.get(i), body)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return 0;
        }
        // 标题可能折成两三行（如“……全体总经理”+“会议纪要”），字号相近的连续行合并
        float size = blocks.get(start).fontSize();
        int end = start + 1;
        while (end < Math.min(start + 3, blocks.size())) {
            Block b = blocks.get(end);
            if (!isTitleCandidate(b, body) || Math.abs(b.fontSize() - size) > size * 0.15f) {
                break;
            }
            end++;
        }
        return end;
    }

    private static boolean isTitleCandidate(Block b, float body) {
        // 文字层字号单位是 pt：比正文大 1.5pt 即可（如 18pt 标题 + 16pt 正文）；
        // OCR 的“字号”是文字框像素高度，有几像素误差，按比例判断
        float min = b.source() == Block.Source.OCR ? body * 1.15f : body + 1.5f;
        return b.page() == 1 && b.source() != Block.Source.TABLE_CELL
                && b.fontSize() >= min
                && b.text().length() <= 80 && !LABEL_LINE.matcher(b.text()).find();
    }

    /** 按字数加权取出现最多的字号，作为正文字号 */
    static float dominantFontSize(List<Block> blocks) {
        Map<Float, Integer> weight = new HashMap<>();
        for (Block b : blocks) {
            if (b.fontSize() > 0) {
                weight.merge(b.fontSize(), b.text().length(), Integer::sum);
            }
        }
        return weight.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0f);
    }

    private static int findFooterStart(List<Block> blocks, int titleEnd) {
        int start = blocks.size();
        int misses = 0;
        for (int i = blocks.size() - 1; i >= Math.max(titleEnd, blocks.size() - FOOTER_SCAN); i--) {
            String t = blocks.get(i).text();
            if (FOOTER_LINE.matcher(t).find()) {
                start = i;
                misses = 0;
            } else if (t.length() <= 20 && ++misses <= 3) {
                // 公司名、印章文字等短行夹在落款中间，继续往上找
                continue;
            } else {
                break;
            }
        }
        return start;
    }

    /** 测试和调试用：列出各区域的行 */
    public static List<String> describe(Regions r) {
        List<String> out = new ArrayList<>();
        r.title().forEach(b -> out.add("[标题] " + b.text()));
        r.header().forEach(b -> out.add("[抬头] " + b.text()));
        r.footer().forEach(b -> out.add("[落款] " + b.text()));
        return out;
    }
}
