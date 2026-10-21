package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.launcher.DesktopSupport;
import com.yizhaoqi.oaarchive.ledger.LedgerExporter;
import com.yizhaoqi.oaarchive.ledger.LedgerService;
import com.yizhaoqi.oaarchive.model.LedgerEntry;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 台账查询、Excel 导出、打开文件（技术文档 8.2）。
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService service;
    private final LedgerExporter exporter;
    private final LedgerEntryRepository repo;
    private final OaProperties props;

    public LedgerController(LedgerService service, LedgerExporter exporter, LedgerEntryRepository repo, OaProperties props) {
        this.service = service;
        this.exporter = exporter;
        this.repo = repo;
        this.props = props;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> search(
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String department,
            @RequestParam(required = false) String docType, @RequestParam(required = false) String format,
            @RequestParam(required = false) String recognizeMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sort, @RequestParam(defaultValue = "false") boolean asc) {
        var q = new LedgerService.Query(keyword, department, docType, dateFrom, dateTo, format, recognizeMode, false);
        Page<LedgerEntry> p = service.search(q, page, size, sort, asc);
        return ApiResponse.ok(Map.of("items", p.getContent(), "total", p.getTotalElements(), "page", page, "size", size));
    }

    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String department,
            @RequestParam(required = false) String docType, @RequestParam(required = false) String format,
            @RequestParam(required = false) String recognizeMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            HttpServletResponse resp) throws IOException {
        var q = new LedgerService.Query(keyword, department, docType, dateFrom, dateTo, format, recognizeMode, false);
        String name = "文件目录台账_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        exporter.write(service.all(q), resp.getOutputStream());
    }

    /** 用系统程序打开台账里的文件，folder=true 时打开所在文件夹并选中。只接受台账记录 id，不接受任意路径 */
    @PostMapping("/{id}/open")
    public ApiResponse<Void> open(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean folder) {
        if (props.isDocker()) {
            throw new BizException("Docker 版无法在服务器上打开文件，请到挂载的归档目录中查看");
        }
        LedgerEntry e = repo.findById(id).orElseThrow(() -> new BizException("台账记录不存在"));
        Path p = Path.of(e.getArchivePath());
        if (!Files.exists(p)) {
            throw new BizException("文件已不存在：" + p);
        }
        if (folder) {
            DesktopSupport.reveal(p.toFile());
        } else {
            DesktopSupport.open(p.toFile());
        }
        return ApiResponse.ok();
    }
}
