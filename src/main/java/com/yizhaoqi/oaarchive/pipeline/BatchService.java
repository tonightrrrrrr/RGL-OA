package com.yizhaoqi.oaarchive.pipeline;

import com.yizhaoqi.oaarchive.archive.FileNameSanitizer;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.importer.ImportScanner;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.parser.ParserRegistry;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批次管理：新建批次（文件夹、上传、收件箱）、提交处理、人工修正、重试。
 */
@Service
public class BatchService {

    private final ArchiveBatchRepository batches;
    private final FileRecordRepository files;
    private final TemplateService templates;
    private final FileProcessor processor;
    private final StepRecorder steps;
    private final ProgressHub hub;
    private final OaProperties props;
    private final ExecutorService pipeline;
    private final Map<Long, AtomicInteger> remaining = new ConcurrentHashMap<>();

    public BatchService(ArchiveBatchRepository batches, FileRecordRepository files, TemplateService templates,
                        FileProcessor processor, StepRecorder steps, ProgressHub hub, OaProperties props,
                        @Qualifier("pipelineExecutor") ExecutorService pipeline) {
        this.batches = batches;
        this.files = files;
        this.templates = templates;
        this.processor = processor;
        this.steps = steps;
        this.hub = hub;
        this.props = props;
        this.pipeline = pipeline;
    }

    public ArchiveBatch createFromFolder(String sourcePath, String targetRoot, Long templateId) throws IOException {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new BizException("请选择要导入的文件夹");
        }
        Path src = Path.of(sourcePath.strip()).toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) {
            throw new BizException("文件夹不存在：" + src);
        }
        Path target = resolveTarget(targetRoot);
        // 归档目录不能是源目录或其子目录，否则下次导入会把归档结果再扫一遍（技术文档 6.7.3）
        if (target.startsWith(src)) {
            throw new BizException("归档目录不能设在要导入的文件夹里面，请换一个归档目录");
        }
        ImportScanner.Result scan = ImportScanner.scan(src, props.getImportConfig().getMaxDepth(), maxBytes());
        if (scan.accepted().isEmpty()) {
            throw new BizException("文件夹里没有可以导入的文件" + skippedNote(scan.skipped()));
        }
        ArchiveBatch b = newBatch(ArchiveBatch.SourceType.FOLDER, src.toString(), target, templateId, scan.skipped());
        List<Long> ids = new ArrayList<>();
        for (ImportScanner.Scanned s : scan.accepted()) {
            ids.add(newRecord(b, s.path(), s.path().getFileName().toString(), s.size()).getId());
        }
        submit(b, ids);
        return b;
    }

    public ArchiveBatch createFromInbox(String targetRoot, Long templateId) throws IOException {
        if (props.getInbox() == null) {
            throw new BizException("当前模式没有配置收件箱目录");
        }
        Files.createDirectories(Path.of(props.getInbox()));
        return createFromFolder(props.getInbox(), targetRoot, templateId);
    }

    public ArchiveBatch createFromUpload(List<MultipartFile> uploads, String targetRoot, Long templateId) throws IOException {
        if (uploads == null || uploads.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        Path target = resolveTarget(targetRoot);
        List<ImportScanner.Skipped> skipped = new ArrayList<>();
        ArchiveBatch b = newBatch(ArchiveBatch.SourceType.UPLOAD, null, target, templateId, List.of());
        Path dir = props.workRoot().resolve(String.valueOf(b.getId())).resolve("upload");
        Files.createDirectories(dir);
        List<Long> ids = new ArrayList<>();
        int n = 0;
        for (MultipartFile u : uploads) {
            String name = FileNameSanitizer.sanitize(Path.of(u.getOriginalFilename() == null ? "未命名" : u.getOriginalFilename())
                    .getFileName().toString());
            String reason = ImportScanner.skipReason(name, u.getSize(), maxBytes(), false);
            if (reason != null) {
                skipped.add(new ImportScanner.Skipped(name, reason));
                continue;
            }
            Path saved = dir.resolve((++n) + "_" + name);
            try (InputStream in = u.getInputStream()) {
                Files.copy(in, saved, StandardCopyOption.REPLACE_EXISTING);
            }
            ids.add(newRecord(b, saved, name, u.getSize()).getId());
        }
        b.setSkippedCount(skipped.size());
        b.setSkippedSummary(summarize(skipped));
        batches.save(b);
        if (ids.isEmpty()) {
            throw new BizException("没有可以导入的文件" + skippedNote(skipped));
        }
        submit(b, ids);
        return b;
    }
    private void submit(ArchiveBatch b, List<Long> ids) {
        TemplateConfig tpl = templates.parse(b.getTemplateSnapshot());
        Path root = Path.of(b.getTargetRoot());
        AtomicInteger left = remaining.computeIfAbsent(b.getId(), k -> new AtomicInteger());
        left.addAndGet(ids.size());
        for (Long id : ids) {
            pipeline.submit(() -> {
                try {
                    processor.process(id, tpl, root);
                } finally {
                    if (left.decrementAndGet() == 0) {
                        finish(b.getId());
                    }
                }
            });
        }
    }

    private void finish(Long batchId) {
        remaining.remove(batchId);
        processor.forgetBatch(batchId);
        batches.findById(batchId).ifPresent(b -> {
            if (b.getStatus() == ArchiveBatch.Status.RUNNING) {
                b.setStatus(ArchiveBatch.Status.DONE);
                b.setFinishedAt(LocalDateTime.now());
                batches.save(b);
            }
        });
        hub.publish(batchId, "done", stats(batchId));
    }

    public Map<FileRecord.Status, Long> stats(Long batchId) {
        Map<FileRecord.Status, Long> m = new EnumMap<>(FileRecord.Status.class);
        for (FileRecord.Status s : FileRecord.Status.values()) {
            m.put(s, 0L);
        }
        for (Object[] row : files.countByStatus(batchId)) {
            m.put((FileRecord.Status) row[0], (Long) row[1]);
        }
        return m;
    }

    public boolean isRunning(Long batchId) {
        return remaining.containsKey(batchId);
    }

    /** 人工修正或确认（技术文档 6.11 待确认页）；字段有变化时标记为“人工修正” */
    public FileRecord correct(Long fileId, String department, String docType, LocalDate docDate, String title) {
        FileRecord f = files.findById(fileId).orElseThrow(() -> new BizException("文件不存在"));
        if (f.getStatus() == FileRecord.Status.ARCHIVED) {
            throw new BizException("文件已归档，请先撤销该批次再修改");
        }
        if (f.getStatus() == FileRecord.Status.QUEUED || f.getStatus() == FileRecord.Status.PARSING) {
            throw new BizException("文件还在处理中，请稍候");
        }
        ArchiveBatch b = batches.findById(f.getBatchId()).orElseThrow(() -> new BizException("批次不存在"));
        TemplateConfig tpl = templates.parse(b.getTemplateSnapshot());
        if (department != null && tpl.findDept(department) == null) {
            throw new BizException("部门不在模板中：" + department);
        }
        if (docType != null && tpl.findType(docType) == null) {
            throw new BizException("类型不在模板中：" + docType);
        }
        StringBuilder changes = new StringBuilder();
        if (department != null && !department.equals(f.getDepartment())) {
            changes.append("部门 ").append(f.getDepartment()).append("→").append(department).append("；");
            f.setDepartment(department);
        }
        if (docType != null && !docType.equals(f.getDocType())) {
            changes.append("类型 ").append(f.getDocType()).append("→").append(docType).append("；");
            f.setDocType(docType);
        }
        if (docDate != null && !docDate.equals(f.getDocDate())) {
            changes.append("日期 ").append(f.getDocDate()).append("→").append(docDate).append("；");
            f.setDocDate(docDate);
        }
        if (title != null && !title.isBlank() && !title.strip().equals(f.getTitle())) {
            changes.append("标题已修改；");
            f.setTitle(title.strip());
        }
        if (f.getDocDate() == null) {
            throw new BizException("请填写文件日期");
        }
        if (!changes.isEmpty()) {
            f.setCorrected(true);
        }
        if (f.getStatus() != FileRecord.Status.DUPLICATE) {
            f.setStatus(FileRecord.Status.READY);
        }
        f.setReviewReasons(null);
        processor.planName(f, tpl, Path.of(b.getTargetRoot()));
        f.setUpdatedAt(LocalDateTime.now());
        files.save(f);
        steps.record(f.getId(), "REVIEW", changes.isEmpty() ? "人工确认识别结果" : "人工修正：" + changes, 0);
        hub.publish(f.getBatchId(), "file", Map.of("id", f.getId(), "status", f.getStatus().name()));
        return f;
    }

    public void retry(Long fileId) {
        FileRecord f = files.findById(fileId).orElseThrow(() -> new BizException("文件不存在"));
        if (f.getStatus() != FileRecord.Status.FAILED) {
            throw new BizException("只有处理失败的文件可以重试");
        }
        ArchiveBatch b = batches.findById(f.getBatchId()).orElseThrow(() -> new BizException("批次不存在"));
        f.setStatus(FileRecord.Status.QUEUED);
        f.setErrorMessage(null);
        files.save(f);
        steps.record(f.getId(), "IMPORT", "重新处理", 0);
        submit(b, List.of(f.getId()));
    }

    private ArchiveBatch newBatch(ArchiveBatch.SourceType type, String source, Path target, Long templateId,
                                  List<ImportScanner.Skipped> skipped) {
        TemplateEntity t = templates.getOrDefault(templateId);
        ArchiveBatch b = new ArchiveBatch();
        b.setSourceType(type);
        b.setSourcePath(source);
        b.setTargetRoot(target.toString());
        b.setTemplateId(t.getId());
        b.setTemplateName(t.getName());
        b.setTemplateSnapshot(t.getContentJson());
        b.setStatus(ArchiveBatch.Status.RUNNING);
        b.setSkippedCount(skipped.size());
        b.setSkippedSummary(summarize(skipped));
        b.setCreatedAt(LocalDateTime.now());
        return batches.save(b);
    }

    private FileRecord newRecord(ArchiveBatch b, Path path, String name, long size) {
        FileRecord f = new FileRecord();
        f.setBatchId(b.getId());
        f.setOriginalPath(path.toString());
        f.setOriginalName(name);
        f.setExt(ParserRegistry.extension(name));
        f.setSize(size);
        f.setStatus(FileRecord.Status.QUEUED);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        return files.save(f);
    }

    private Path resolveTarget(String targetRoot) {
        String t = targetRoot == null || targetRoot.isBlank() ? props.getArchive().getDefaultRoot() : targetRoot.strip();
        Path p = Path.of(t).toAbsolutePath().normalize();
        if (p.getParent() == null) {
            throw new BizException("归档目录不能是磁盘根目录，请选择一个文件夹");
        }
        return p;
    }

    private long maxBytes() {
        return props.getImportConfig().getMaxFileSizeMb() * 1024L * 1024L;
    }

    private static String summarize(List<ImportScanner.Skipped> skipped) {
        StringBuilder sb = new StringBuilder();
        for (ImportScanner.Skipped s : skipped) {
            String item = s.name() + "（" + s.reason() + "）；";
            if (sb.length() + item.length() > 1900) {
                sb.append("……");
                break;
            }
            sb.append(item);
        }
        return sb.toString();
    }

    private static String skippedNote(List<ImportScanner.Skipped> skipped) {
        return skipped.isEmpty() ? "" : "（已跳过 " + skipped.size() + " 个：" + summarize(skipped) + "）";
    }
}