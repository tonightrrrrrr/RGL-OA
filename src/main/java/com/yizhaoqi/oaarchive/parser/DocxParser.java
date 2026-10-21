package com.yizhaoqi.oaarchive.parser;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * docx 解析：按文档顺序读取段落和表格，记录字号、字体、是否加粗。
 * 示例文件的标题都没有用标题样式，只能靠字号识别（技术文档 3.2 F4），所以字号是关键信息。
 */
@Component
public class DocxParser implements DocumentParser {

    @Override
    public boolean supports(String ext) {
        return "docx".equals(ext);
    }

    @Override
    public DocModel parse(Path file, String originalName) throws Exception {
        try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            float defaultSize = defaultFontSize(doc);
            List<Block> blocks = new ArrayList<>();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    addParagraph(blocks, p, defaultSize);
                } else if (el instanceof XWPFTable t) {
                    addTable(blocks, t, defaultSize);
                }
            }
            Map<String, String> meta = new LinkedHashMap<>();
            var core = doc.getProperties().getCoreProperties();
            if (core.getCreator() != null) {
                meta.put("author", core.getCreator());
            }
            if (core.getCreated() != null) {
                meta.put("created", core.getCreated().toInstant().toString());
            }
            int pages = doc.getProperties().getExtendedProperties().getPages();
            return new DocModel(originalName, "docx", Math.max(pages, 1), blocks, meta, false, new ArrayList<>());
        }
    }

    private static void addParagraph(List<Block> blocks, XWPFParagraph p, float defaultSize) {
        String text = p.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        float size = 0;
        String font = null;
        boolean bold = false;
        for (XWPFRun r : p.getRuns()) {
            String rt = r.text();
            if (rt == null || rt.isBlank()) {
                continue;
            }
            Double s = r.getFontSizeAsDouble();
            if (s != null && s > size) {
                size = s.floatValue();
            }
            if (font == null) {
                font = r.getFontFamily();
            }
            bold |= r.isBold();
        }
        blocks.add(Block.text(text, 1, size > 0 ? size : defaultSize, font, bold));
    }

    private static void addTable(List<Block> blocks, XWPFTable t, float defaultSize) {
        for (XWPFTableRow row : t.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String ct = cell.getText();
                if (ct != null && !ct.isBlank()) {
                    cells.add(ct.strip());
                }
            }
            if (!cells.isEmpty()) {
                blocks.add(Block.table(String.join(" | ", cells), 1, defaultSize));
            }
        }
    }

    private static float defaultFontSize(XWPFDocument doc) {
        try {
            Double s = doc.getStyles().getDefaultRunStyle().getFontSizeAsDouble();
            if (s != null && s > 0) {
                return s.floatValue();
            }
        } catch (Exception ignored) {
            // 没有默认样式时用 Word 的默认五号字
        }
        return 10.5f;
    }
}
