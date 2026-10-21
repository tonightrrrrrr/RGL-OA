package com.yizhaoqi.oaarchive.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 按行抽取 PDF 文字，并记录坐标和字号。开启按坐标排序，修复“第三条出现在第二条前面”的问题（技术文档 3.2 F6）。
 */
class PdfLineStripper extends PDFTextStripper {

    private final List<Block> blocks = new ArrayList<>();
    private final List<Integer> charsPerPage = new ArrayList<>();
    private final StringBuilder line = new StringBuilder();
    private final List<TextPosition> linePositions = new ArrayList<>();
    private int pageChars;
    private float pageW = 1;
    private float pageH = 1;

    PdfLineStripper() throws IOException {
        setSortByPosition(true);
    }

    List<Block> extract(PDDocument doc) throws IOException {
        getText(doc);
        return blocks;
    }

    /** 每页非空白字符数，用于判断是否为扫描页 */
    List<Integer> charsPerPage() {
        return charsPerPage;
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
        PDRectangle box = page.getCropBox();
        pageW = Math.max(box.getWidth(), 1);
        pageH = Math.max(box.getHeight(), 1);
        pageChars = 0;
        super.startPage(page);
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        flushLine();
        charsPerPage.add(pageChars);
        super.endPage(page);
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        line.append(text);
        linePositions.addAll(positions);
        pageChars += text.replaceAll("\\s", "").length();
    }

    @Override
    protected void writeWordSeparator() {
        line.append(' ');
    }

    @Override
    protected void writeLineSeparator() {
        flushLine();
    }

    private void flushLine() {
        String text = line.toString().strip();
        if (!text.isEmpty() && !linePositions.isEmpty()) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = 0, maxY = 0, size = 0;
            String font = null;
            boolean bold = false;
            for (TextPosition tp : linePositions) {
                float h = tp.getHeightDir();
                float top = tp.getYDirAdj() - h;
                minX = Math.min(minX, tp.getXDirAdj());
                maxX = Math.max(maxX, tp.getXDirAdj() + tp.getWidthDirAdj());
                minY = Math.min(minY, top);
                maxY = Math.max(maxY, tp.getYDirAdj());
                size = Math.max(size, tp.getFontSizeInPt());
                if (font == null && tp.getFont() != null) {
                    font = tp.getFont().getName();
                }
            }
            if (font != null) {
                bold = font.toLowerCase().contains("bold");
            }
            blocks.add(new Block(text, getCurrentPageNo(), round(size), font, bold,
                    minX / pageW, minY / pageH, (maxX - minX) / pageW, (maxY - minY) / pageH,
                    1f, Block.Source.TEXT_LAYER));
        }
        line.setLength(0);
        linePositions.clear();
    }

    private static float round(float v) {
        return Math.round(v * 2) / 2f; // 取到 0.5pt，避免浮点误差影响“字号最大”的比较
    }
}
