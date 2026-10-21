package com.yizhaoqi.oaarchive.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.ProcessStep;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.pipeline.RecordCleanupService;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.ProcessStepRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 文件详情、预览、人工修正和重试（技术文档 8.1）。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Set<String> BROWSER_IMAGES = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    private final FileRecordRepository files;
    private final ProcessStepRepository steps;
    private final BatchService batches;
    private final RecordCleanupService cleanup;
    private final ObjectMapper mapper;

    public FileController(FileRecordRepository files, ProcessStepRepository steps, BatchService batches,
                          RecordCleanupService cleanup, ObjectMapper mapper) {
        this.files = files;
        this.steps = steps;
        this.batches = batches;
        this.cleanup = cleanup;
        this.mapper = mapper;
    }

    public record CorrectRequest(String department, String docType, LocalDate docDate, String title) {
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) throws IOException {
        FileRecord f = file(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", BatchController.slim(f));
        m.put("evidence", f.getEvidenceJson() == null ? null : mapper.readTree(f.getEvidenceJson()));
        m.put("extras", f.getExtraJson() == null ? Map.of() : mapper.readTree(f.getExtraJson()));
        m.put("textExcerpt", f.getTextExcerpt());
        List<ProcessStep> s = steps.findByFileRecordIdOrderBySeqAsc(id);
        m.put("steps", s);
        return ApiResponse.ok(m);
    }

    /** 待确认列表：所有批次里状态为 REVIEW 的文件 */
    @GetMapping("/review")
    public ApiResponse<List<FileRecord>> review() {
        return ApiResponse.ok(files.findByStatusOrderByIdDesc(FileRecord.Status.REVIEW).stream().map(BatchController::slim).toList());
    }

    /** 原文预览：图片直接返回；PDF 按页渲染成 PNG；其他格式返回 404，由前端展示文字摘要 */
    @GetMapping("/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable Long id, @RequestParam(defaultValue = "1") int page) throws IOException {
        FileRecord f = file(id);
        if (f.getWorkPath() == null || !Files.exists(Path.of(f.getWorkPath()))) {
            return ResponseEntity.notFound().build();
        }
        Path work = Path.of(f.getWorkPath());
        String ext = f.getExt() == null ? "" : f.getExt();
        if (BROWSER_IMAGES.contains(ext)) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(Files.probeContentType(work) == null
                    ? "image/png" : Files.probeContentType(work))).body(Files.readAllBytes(work));
        }
        BufferedImage img = null;
        if ("pdf".equals(ext)) {
            try (PDDocument doc = PDDocument.load(work.toFile())) {
                int p = Math.min(Math.max(page, 1), doc.getNumberOfPages()) - 1;
                img = new PDFRenderer(doc).renderImageWithDPI(p, 110, ImageType.RGB);
            }
        } else if ("tif".equals(ext) || "tiff".equals(ext)) {
            img = ImageIO.read(work.toFile());
        }
        if (img == null) {
            return ResponseEntity.notFound().build();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(out.toByteArray());
    }

    @PutMapping("/{id}")
    public ApiResponse<FileRecord> correct(@PathVariable Long id, @RequestBody CorrectRequest req) {
        return ApiResponse.ok(BatchController.slim(batches.correct(id, req.department(), req.docType(), req.docDate(), req.title())));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable Long id) {
        batches.retry(id);
        return ApiResponse.ok();
    }

    /** 从批次中移除一个未归档的文件记录（不删除原文件） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        cleanup.deleteFile(id);
        return ApiResponse.ok();
    }

    private FileRecord file(Long id) {
        return files.findById(id).orElseThrow(() -> new BizException("文件不存在"));
    }
}
