package com.yizhaoqi.oaarchive.ledger;

import com.yizhaoqi.oaarchive.model.LedgerEntry;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 台账 Excel 导出（技术文档 6.8.3）：流式写入，表头加粗冻结，开启筛选，归档路径写成超链接。
 * 每次归档后在归档根目录自动生成或更新“文件目录台账.xlsx”。
 */
@Slf4j
@Component
public class LedgerExporter {

    public static final String MASTER_FILE = "文件目录台账.xlsx";
    private static final String[] HEADERS = {"序号", "档号", "标题", "部门", "类型", "文件日期", "原文件名", "新文件名",
            "归档路径", "格式", "大小(KB)", "页数", "关键词", "合同编号", "文号", "甲方", "乙方", "金额",
            "识别方式", "综合置信度", "使用OCR", "归档时间", "SHA-256"};
    private static final int[] WIDTHS = {6, 18, 40, 12, 10, 12, 40, 50, 50, 7, 10, 6, 30, 22, 18, 22, 22, 12, 10, 10, 8, 20, 30};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LedgerEntryRepository repo;

    public LedgerExporter(LedgerEntryRepository repo) {
        this.repo = repo;
    }

    public void write(List<LedgerEntry> rows, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            SXSSFSheet sheet = wb.createSheet("文件目录台账");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            CellStyle link = wb.createCellStyle();
            Font blue = wb.createFont();
            blue.setUnderline(Font.U_SINGLE);
            blue.setColor(IndexedColors.BLUE.getIndex());
            link.setFont(blue);

            Row hr = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                hr.createCell(i).setCellValue(HEADERS[i]);
                hr.getCell(i).setCellStyle(head);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            int r = 1;
            for (LedgerEntry e : rows) {
                Row row = sheet.createRow(r);
                Object[] v = {r, e.getArchiveNo(), e.getTitle(), e.getDepartment(), e.getDocType(),
                        e.getDocDate() == null ? "" : e.getDocDate().toString(), e.getOriginalName(), e.getNewName(),
                        e.getRelativePath(), e.getFormat(), e.getSize() == null ? "" : Math.round(e.getSize() / 102.4) / 10.0,
                        e.getPageCount(), e.getKeywords(), e.getContractNo(), e.getDocNo(), e.getPartyA(), e.getPartyB(),
                        e.getAmount(), e.getRecognizeMode(), e.getConfidence(), e.isOcrUsed() ? "是" : "否",
                        e.getArchivedAt() == null ? "" : e.getArchivedAt().format(TIME), e.getSha256()};
                for (int i = 0; i < v.length; i++) {
                    var cell = row.createCell(i);
                    if (v[i] instanceof Integer n) {
                        cell.setCellValue(n); // 序号、页数按整数写入，避免显示成 1.0
                    } else if (v[i] instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else {
                        cell.setCellValue(v[i] == null ? "" : v[i].toString());
                    }
                }
                if (e.getArchivePath() != null) {
                    Hyperlink h = wb.getCreationHelper().createHyperlink(HyperlinkType.FILE);
                    h.setAddress(Path.of(e.getArchivePath()).toUri().toString());
                    row.getCell(8).setHyperlink(h);
                    row.getCell(8).setCellStyle(link);
                }
                r++;
            }
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(r - 1, 0), 0, HEADERS.length - 1));
            wb.write(out);
        }
    }

    /** 重新生成归档根目录下的总台账；文件被 Excel 打开时跳过并返回 null */
    public String refreshMasterLedger(Path root) {
        Path normRoot = root.toAbsolutePath().normalize();
        List<LedgerEntry> rows = repo.findByUndoneFalseOrderByIdAsc().stream()
                .filter(e -> e.getArchivePath() != null && Path.of(e.getArchivePath()).startsWith(normRoot))
                .toList();
        Path target = root.resolve(MASTER_FILE);
        Path tmp = root.resolve(MASTER_FILE + ".tmp");
        try {
            Files.createDirectories(root);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                write(rows, out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            log.warn("总台账更新失败（可能正被 Excel 打开）：{}", e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 忽略
            }
            return null;
        }
    }
}
