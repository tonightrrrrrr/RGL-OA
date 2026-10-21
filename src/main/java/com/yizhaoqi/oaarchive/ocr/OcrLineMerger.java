package com.yizhaoqi.oaarchive.ocr;

import com.yizhaoqi.oaarchive.parser.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把 OCR 文字框按行合并（技术文档 6.3.6 后处理）：
 * 纵向中心距离小于半个行高的框视为同一行，行内按横坐标排序后拼接。
 */
public final class OcrLineMerger {

    private OcrLineMerger() {
    }

    public static List<Block> merge(List<Block> boxes) {
        List<Block> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(b -> b.y() + b.h() / 2));
        List<List<Block>> lines = new ArrayList<>();
        for (Block b : sorted) {
            float center = b.y() + b.h() / 2;
            List<Block> last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (last != null) {
                Block ref = last.get(0);
                float refCenter = ref.y() + ref.h() / 2;
                float tolerance = Math.min(ref.h(), b.h()) / 2;
                if (Math.abs(center - refCenter) <= tolerance) {
                    last.add(b);
                    continue;
                }
            }
            List<Block> line = new ArrayList<>();
            line.add(b);
            lines.add(line);
        }
        List<Block> merged = new ArrayList<>();
        for (List<Block> line : lines) {
            line.sort(Comparator.comparingDouble(Block::x));
            merged.add(join(line));
        }
        return merged;
    }

    private static Block join(List<Block> line) {
        if (line.size() == 1) {
            return line.get(0);
        }
        StringBuilder text = new StringBuilder();
        float minX = 1, minY = 1, maxX = 0, maxY = 0, maxFont = 0, confSum = 0;
        int chars = 0;
        for (Block b : line) {
            if (text.length() > 0 && needsSpace(text.charAt(text.length() - 1), b.text().charAt(0))) {
                text.append(' ');
            }
            text.append(b.text());
            minX = Math.min(minX, b.x());
            minY = Math.min(minY, b.y());
            maxX = Math.max(maxX, b.x() + b.w());
            maxY = Math.max(maxY, b.y() + b.h());
            maxFont = Math.max(maxFont, b.fontSize());
            confSum += b.confidence() * b.text().length();
            chars += b.text().length();
        }
        Block first = line.get(0);
        return new Block(text.toString(), first.page(), maxFont, null, false,
                minX, minY, maxX - minX, maxY - minY,
                chars == 0 ? first.confidence() : confSum / chars, Block.Source.OCR);
    }

    /** 中文之间不加空格；英文、数字之间加空格 */
    private static boolean needsSpace(char prev, char next) {
        return isAsciiWord(prev) && isAsciiWord(next);
    }

    private static boolean isAsciiWord(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }
}
