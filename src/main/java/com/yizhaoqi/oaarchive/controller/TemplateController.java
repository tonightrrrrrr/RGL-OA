package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.archive.TargetPlanner;
import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.template.TemplateAnalyzer;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateService;
import com.yizhaoqi.oaarchive.template.TemplatePreviewService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板管理接口（技术文档 8.3）。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService service;
    private final TemplatePreviewService previews;

    public TemplateController(TemplateService service, TemplatePreviewService previews) {
        this.service = service;
        this.previews = previews;
    }

    /** name 为模板名称，content 为模板 JSON 文本；新建时 content 为空则基于 baseId（为空时用空白模板） */
    public record SaveRequest(String name, String content, Long baseId) {
    }

    /** content 为正在编辑的模板 JSON，baseContent 为对比基准（可选） */
    public record AnalyzeRequest(String content, String baseContent) {
    }

    /** batchId 为空时使用内置示例；renamedSamples 为 true 时示例文件名改成“文档N”；negativeSamples 为 true 时用反面案例 */
    public record PreviewRequest(Long batchId, boolean renamedSamples, boolean negativeSamples, String content,
                                 String baseContent, TemplatePreviewService.Presets presets) {
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list().stream().map(TemplateController::summary).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        TemplateEntity t = service.get(id);
        Map<String, Object> m = new LinkedHashMap<>(summary(t));
        m.put("content", t.getContentJson());
        m.put("config", service.parse(t.getContentJson()));
        return ApiResponse.ok(m);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody SaveRequest req) {
        if (req.content() == null || req.content().isBlank()) {
            return ApiResponse.ok(summary(service.createFromBase(req.name(), req.baseId())));
        }
        return ApiResponse.ok(summary(service.create(req.name(), service.parse(req.content()))));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody SaveRequest req) {
        return ApiResponse.ok(summary(service.update(id, req.name(), service.parse(req.content()))));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<Map<String, Object>> copy(@PathVariable Long id) {
        return ApiResponse.ok(summary(service.copy(id)));
    }

    @GetMapping("/{id}/export")
    public void export(@PathVariable Long id, HttpServletResponse resp) throws IOException {
        TemplateEntity t = service.get(id);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(t.getName() + ".json", StandardCharsets.UTF_8).replace("+", "%20"));
        resp.getOutputStream().write(t.getContentJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 智能检查与对比（不保存）：问题列表、与基准模板的差异、示例文件在两个模板下的命名效果。
     */
    @PostMapping("/analyze")
    public ApiResponse<Map<String, Object>> analyze(@RequestBody AnalyzeRequest req) {
        TemplateConfig target = service.parse(req.content());
        TemplateConfig base = req.baseContent() == null || req.baseContent().isBlank() ? null : service.parse(req.baseContent());
        List<TemplateAnalyzer.Issue> issues = TemplateAnalyzer.lint(target);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issues", issues);
        m.put("counts", Map.of(
                "error", issues.stream().filter(i -> i.level() == TemplateAnalyzer.Level.ERROR).count(),
                "warning", issues.stream().filter(i -> i.level() == TemplateAnalyzer.Level.WARNING).count(),
                "info", issues.stream().filter(i -> i.level() == TemplateAnalyzer.Level.INFO).count()));
        m.put("diff", base == null ? null : TemplateAnalyzer.diff(base, target));
        List<Map<String, String>> samples = new ArrayList<>();
        for (FileRecord f : samples()) {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("original", f.getOriginalName());
            s.put("fields", f.getDepartment() + " / " + f.getDocType() + " / " + f.getDocDate());
            s.put("target", plan(f, target));
            s.put("targetNo", archiveNo(f, target));
            if (base != null) {
                s.put("base", plan(f, base));
                s.put("baseNo", archiveNo(f, base));
            }
            samples.add(s);
        }
        m.put("samples", samples);
        return ApiResponse.ok(m);
    }

    /**
     * 实时预览（不修改任何数据）：用内置示例（batchId 为空）或某个已导入批次的真实文件，
     * 按当前模板和基准模板分别识别、命名，返回逐个文件的对比、重名、阈值分析和常用写法的效果。
     */
    @PostMapping("/preview")
    public ApiResponse<TemplatePreviewService.Result> preview(@RequestBody PreviewRequest req) {
        TemplateConfig target = service.parse(req.content());
        TemplateConfig base = req.baseContent() == null || req.baseContent().isBlank() ? null : service.parse(req.baseContent());
        TemplatePreviewService.SampleSet set = req.negativeSamples() ? TemplatePreviewService.SampleSet.NEGATIVE
                : req.renamedSamples() ? TemplatePreviewService.SampleSet.RENAMED : TemplatePreviewService.SampleSet.ORIGINAL;
        return ApiResponse.ok(previews.preview(req.batchId(), set, target, base, req.presets()));
    }

    private static String plan(FileRecord f, TemplateConfig cfg) {
        try {
            return TargetPlanner.plan(f, cfg, Path.of("D:/归档"), 1).relative().toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return "（规则有误，无法生成）";
        }
    }

    private static String archiveNo(FileRecord f, TemplateConfig cfg) {
        try {
            return TargetPlanner.plan(f, cfg, Path.of("D:/归档"), 1).archiveNo();
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private static List<FileRecord> samples() {
        FileRecord a = new FileRecord();
        a.setOriginalName("扫描件001.pdf");
        a.setExt("pdf");
        a.setDepartment("人力资源部");
        a.setDocType("通知");
        a.setDocDate(LocalDate.of(2026, 3, 15));
        a.setTitle("关于张明等同志职务任免的通知");
        FileRecord b = new FileRecord();
        b.setOriginalName("合同-终版.docx");
        b.setExt("docx");
        b.setDepartment("销售部");
        b.setDocType("合同");
        b.setDocDate(LocalDate.of(2025, 12, 25));
        b.setTitle("智能工业检测设备销售合同");
        return List.of(a, b);
    }

    private static Map<String, Object> summary(TemplateEntity t) {
        return Map.of("id", t.getId(), "name", t.getName(), "builtin", t.isBuiltin(),
                "updatedAt", t.getUpdatedAt() == null ? "" : t.getUpdatedAt().toString());
    }
}
