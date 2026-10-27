package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.archive.ArchiveService;
import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.pipeline.ProgressHub;
import com.yizhaoqi.oaarchive.pipeline.RecordCleanupService;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.rollback.ProjectOps;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入与归档接口（技术文档 8.1、8.2）。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService service;
    private final ArchiveService archive;
    private final ArchiveBatchRepository batches;
    private final FileRecordRepository files;
    private final ProgressHub hub;
    private final ProjectOps ops;

    public BatchController(BatchService service, ArchiveService archive, ArchiveBatchRepository batches,
                           FileRecordRepository files, ProgressHub hub, ProjectOps ops) {
        this.service = service;
        this.archive = archive;
        this.batches = batches;
        this.files = files;
        this.hub = hub;
        this.ops = ops;
    }

    public record CreateRequest(String sourcePath, String targetRoot, Long templateId) {
    }

    public record ArchiveRequest(List<Long> fileIds, boolean includeReview) {
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest req) throws IOException {
        return ApiResponse.ok(view(service.createFromFolder(req.sourcePath(), req.targetRoot(), req.templateId())));
    }

    @PostMapping("/inbox")
    public ApiResponse<Map<String, Object>> fromInbox(@RequestBody CreateRequest req) throws IOException {
        return ApiResponse.ok(view(service.createFromInbox(req.targetRoot(), req.templateId())));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(@RequestParam("files") List<MultipartFile> uploads,
                                                   @RequestParam(value = "targetRoot", required = false) String targetRoot,
                                                   @RequestParam(value = "templateId", required = false) Long templateId) throws IOException {
        return ApiResponse.ok(view(service.createFromUpload(uploads, targetRoot, templateId)));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(batches.findTop50ByOrderByIdDesc().stream().map(this::view).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.ok(view(batch(id)));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) {
        batch(id);
        return hub.subscribe(id);
    }

    @GetMapping("/{id}/files")
    public ApiResponse<List<FileRecord>> files(@PathVariable Long id, @RequestParam(required = false) List<FileRecord.Status> status) {
        batch(id);
        List<FileRecord> list = status == null || status.isEmpty()
                ? files.findByBatchIdOrderByIdAsc(id)
                : files.findByBatchIdAndStatusInOrderByIdAsc(id, EnumSet.copyOf(status));
        return ApiResponse.ok(list.stream().map(BatchController::slim).toList());
    }

    @PostMapping("/{id}/preview")
    public ApiResponse<List<ArchiveService.PreviewItem>> preview(@PathVariable Long id,
                                                                 @RequestParam(defaultValue = "false") boolean includeReview) {
        return ApiResponse.ok(archive.preview(id, includeReview));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<ArchiveService.ArchiveSummary> archive(@PathVariable Long id, @RequestBody(required = false) ArchiveRequest req) {
        ArchiveRequest r = req == null ? new ArchiveRequest(null, false) : req;
        return ApiResponse.ok(ops.archive(id, r.fileIds(), r.includeReview(), "UI"));
    }

    /** 重试批次里所有失败的文件，返回重试的个数 */
    @PostMapping("/{id}/retry-failed")
    public ApiResponse<Map<String, Integer>> retryFailed(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("retried", service.retryFailed(id)));
    }

    @PostMapping("/{id}/undo")
    public ApiResponse<ArchiveService.UndoSummary> undo(@PathVariable Long id) {
        return ApiResponse.ok(ops.undo(id, "UI"));
    }

    /** 删除批次记录：不删除原文件，也不删除已归档的文件和台账 */
    @DeleteMapping("/{id}")
    public ApiResponse<RecordCleanupService.Result> delete(@PathVariable Long id) {
        return ApiResponse.ok(ops.deleteBatch(id, "UI"));
    }

    private ArchiveBatch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> new BizException("批次不存在"));
    }

    private Map<String, Object> view(ArchiveBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("sourceType", b.getSourceType());
        m.put("sourcePath", b.getSourcePath());
        m.put("targetRoot", b.getTargetRoot());
        m.put("templateId", b.getTemplateId());
        m.put("templateName", b.getTemplateName());
        m.put("status", b.getStatus());
        m.put("running", service.isRunning(b.getId()));
        m.put("skippedCount", b.getSkippedCount());
        m.put("skippedSummary", b.getSkippedSummary());
        m.put("createdAt", b.getCreatedAt());
        m.put("finishedAt", b.getFinishedAt());
        m.put("archivedAt", b.getArchivedAt());
        m.put("stats", service.stats(b.getId()));
        return m;
    }

    /** 列表里不带正文和判断依据，减小响应体积 */
    static FileRecord slim(FileRecord f) {
        FileRecord c = new FileRecord();
        org.springframework.beans.BeanUtils.copyProperties(f, c, "textExcerpt", "evidenceJson");
        return c;
    }
}
