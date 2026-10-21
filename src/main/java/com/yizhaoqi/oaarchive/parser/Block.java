package com.yizhaoqi.oaarchive.parser;

/**
 * 统一文档模型中的一个文本块（一段或一行），见技术文档 6.2.1。
 *
 * @param text       文字内容
 * @param page       所在页码，从 1 开始
 * @param fontSize   字号（pt）；OCR 结果为文字框高度（像素），只用于同一文档内比较；无法获取时为 0
 * @param fontName   字体名
 * @param bold       是否加粗
 * @param x          归一化到 0–1 的左上角横坐标；没有坐标时为 -1
 * @param y          归一化到 0–1 的左上角纵坐标；没有坐标时为 -1
 * @param w          归一化宽度
 * @param h          归一化高度
 * @param confidence 文字层为 1.0；OCR 结果为实际置信度
 * @param source     来源
 */
public record Block(
        String text,
        int page,
        float fontSize,
        String fontName,
        boolean bold,
        float x, float y, float w, float h,
        float confidence,
        Source source) {

    public enum Source { TEXT_LAYER, OCR, TABLE_CELL, FILENAME }

    public static Block text(String text, int page, float fontSize, String fontName, boolean bold) {
        return new Block(text, page, fontSize, fontName, bold, -1, -1, -1, -1, 1f, Source.TEXT_LAYER);
    }

    public static Block table(String text, int page, float fontSize) {
        return new Block(text, page, fontSize, null, false, -1, -1, -1, -1, 1f, Source.TABLE_CELL);
    }

    public Block withText(String newText) {
        return new Block(newText, page, fontSize, fontName, bold, x, y, w, h, confidence, source);
    }

    public boolean hasPosition() {
        return x >= 0 && y >= 0;
    }
}
