package com.yizhaoqi.oaarchive.parser;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 旧版 doc（Word 97–2003）解析：读取段落文字和字号。
 */
@Component
public class DocParser implements DocumentParser {

    @Override
    public boolean supports(String ext) {
        return "doc".equals(ext);
    }

    @Override
    public DocModel parse(Path file, String originalName) throws Exception {
        try (InputStream in = Files.newInputStream(file); HWPFDocument doc = new HWPFDocument(in)) {
            Range range = doc.getRange();
            List<Block> blocks = new ArrayList<>();
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph p = range.getParagraph(i);
                // 去掉段落标记、单元格结束符等控制字符
                String text = p.text().replaceAll("[\\r\\n\\u0007\\u000b\\u000c]", " ").strip();
                if (text.isEmpty()) {
                    continue;
                }
                float size = 0;
                String font = null;
                boolean bold = false;
                for (int j = 0; j < p.numCharacterRuns(); j++) {
                    CharacterRun r = p.getCharacterRun(j);
                    if (r.text().isBlank()) {
                        continue;
                    }
                    size = Math.max(size, r.getFontSize() / 2f); // HWPF 字号单位是半磅
                    if (font == null) {
                        font = r.getFontName();
                    }
                    bold |= r.isBold();
                }
                Block.Source src = p.isInTable() ? Block.Source.TABLE_CELL : Block.Source.TEXT_LAYER;
                blocks.add(new Block(text, 1, size, font, bold, -1, -1, -1, -1, 1f, src));
            }
            return new DocModel(originalName, "doc", 1, blocks, Map.of(), false, new ArrayList<>());
        }
    }
}
