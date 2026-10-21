package com.yizhaoqi.oaarchive.parser;

import java.util.List;
import java.util.Map;

/**
 * 统一文档模型：所有格式解析后都转换成这个结构，识别逻辑只写一套（技术文档 6.2.1）。
 *
 * @param fileName  原文件名
 * @param format    格式（docx、xlsx、pdf、image 等）
 * @param pageCount 页数（Word 无法准确分页时为 1）
 * @param blocks    按阅读顺序排列的文本块
 * @param meta      作者、创建时间等元数据
 * @param ocrUsed   是否用了 OCR
 * @param notes     解析过程中值得记录的信息，如“第 2 页走了 OCR”“OCR 不可用”
 */
public record DocModel(
        String fileName,
        String format,
        int pageCount,
        List<Block> blocks,
        Map<String, String> meta,
        boolean ocrUsed,
        List<String> notes) {

    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            sb.append(b.text()).append('\n');
        }
        return sb.toString();
    }
}
