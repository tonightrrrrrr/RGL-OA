package com.yizhaoqi.oaarchive.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xlsx / xls 解析：每一行变成一个文本块，单元格之间用 “ | ” 分隔。
 * 只有一个非空单元格的行（如“落款：人力资源部经理：郑晓”）当作普通文本，方便落款识别。
 */
@Component
public class ExcelParser implements DocumentParser {

    private static final int MAX_ROWS = 2000;

    @Override
    public boolean supports(String ext) {
        return "xlsx".equals(ext) || "xls".equals(ext);
    }

    @Override
    public DocModel parse(Path file, String originalName) throws Exception {
        try (Workbook wb = WorkbookFactory.create(file.toFile(), null, true)) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            List<Block> blocks = new ArrayList<>();
            Map<String, String> meta = new LinkedHashMap<>();
            List<String> sheetNames = new ArrayList<>();
            int rows = 0;
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                sheetNames.add(sheet.getSheetName());
                for (Row row : sheet) {
                    if (++rows > MAX_ROWS) {
                        break;
                    }
                    List<String> cells = new ArrayList<>();
                    float size = 0;
                    boolean bold = false;
                    for (Cell cell : row) {
                        String v = formatCell(fmt, eval, cell);
                        if (v.isBlank()) {
                            continue;
                        }
                        cells.add(v.strip());
                        Font f = wb.getFontAt(cell.getCellStyle().getFontIndex());
                        size = Math.max(size, f.getFontHeightInPoints());
                        bold |= f.getBold();
                    }
                    if (cells.isEmpty()) {
                        continue;
                    }
                    if (cells.size() == 1) {
                        blocks.add(Block.text(cells.get(0), s + 1, size, null, bold));
                    } else {
                        blocks.add(new Block(String.join(" | ", cells), s + 1, size, null, bold,
                                -1, -1, -1, -1, 1f, Block.Source.TABLE_CELL));
                    }
                }
            }
            meta.put("sheets", String.join(",", sheetNames));
            String ext = originalName.toLowerCase().endsWith(".xls") ? "xls" : "xlsx";
            return new DocModel(originalName, ext, wb.getNumberOfSheets(), blocks, meta, false, new ArrayList<>());
        }
    }

    private static String formatCell(DataFormatter fmt, FormulaEvaluator eval, Cell cell) {
        try {
            return fmt.formatCellValue(cell, eval);
        } catch (Exception e) {
            // 公式无法计算时退回到原始显示值
            return fmt.formatCellValue(cell);
        }
    }
}
