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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批次管理：新建批次（文件夹、上传、收件箱）、提交处理、人工修正、重试。
 */
@Slf4j
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
    private final ImportPrefsService prefs;
    private final Map<Long, AtomicInteger> remaining = new ConcurrentHashMap<>();

    public BatchService(ArchiveBatchRepository batches, FileRecordRepository files, TemplateService templates,
                        FileProcessor processor, StepRecorder steps, ProgressHub hub, OaProperties props,
                        @Qualifier("pipelineExecutor") ExecutorService pipeline, ImportPrefsService prefs) {
        this.batches = batches;
        this.files = files;
        this.templates = templates;
        this.processor = processor;
        this.steps = steps;
        this.hub = hub;
        this.props = props;
        this.pipeline = pipeline;
        this.prefs = prefs;
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
        int maxFiles = props.getImportConfig().getMaxFiles();
        ImportScanner.Result scan = ImportScanner.scan(src, props.getImportConfig().getMaxDepth(), maxBytes(), maxFiles);
        if (scan.tooMany()) {
            throw new BizException("文件夹里的文件超过单次导入上限（" + maxFiles + " 个），请选择更具体的文件夹分批导入");
        }
        if (scan.accepted().isEmpty()) {
            throw new BizException("文件夹里没有可以导入的文件" + skippedNote(scan.skipped()));
        }
        ArchiveBatch b = newBatch(ArchiveBatch.SourceType.FOLDER, src.toString(), target, templateId, scan.skipped());
        List<Long> ids = new ArrayList<>();
        try {
            for (ImportScanner.Scanned s : scan.accepted()) {
                ids.add(newRecord(b, s.path(), s.path().getFileName().toString(), s.size()).getId());
            }
        } catch (RuntimeException e) {
            discard(b);
            throw e;
        }
        submit(b, ids);
        prefs.recordImport(b);
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
        if (uploads.size() > props.getImportConfig().getMaxFiles()) {
            throw new BizException("一次最多上传 " + props.getImportConfig().getMaxFiles() + " 个文件，请分批上传");
        }
        Path target = resolveTarget(targetRoot);
        List<ImportScanner.Skipped> skipped = new ArrayList<>();
        ArchiveBatch b = newBatch(ArchiveBatch.SourceType.UPLOAD, null, target, templateId, List.of());
        Path dir = props.workRoot().resolve(String.valueOf(b.getId())).resolve("upload");
        Files.createDirectories(dir);
        List<Long> ids = new ArrayList<>();
        int n = 0;
        try {
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
        } catch (IOException | RuntimeException e) {
            discard(b);
            throw e;
        }
        b.setSkippedCount(skipped.size());
        b.setSkippedSummary(summarize(skipped));
        batches.save(b);
        if (ids.isEmpty()) {
            discard(b);
            throw new BizException("没有可以导入的文件" + skippedNote(skipped));
        }
        submit(b, ids);
        prefs.recordImport(b);
        return b;
    }

    /** 建批次中途失败时删掉已写入的批次和文件记录，不留下一直显示“处理中”的空批次 */
    private void discard(ArchiveBatch b) {
        try {
            files.deleteByBatch(b.getId());
            batches.deleteById(b.getId());
        } catch (RuntimeException e) {
            log.warn("清理未完成的批次 {} 失败", b.getId(), e);
        }
        RecordCleanupService.deleteWorkDir(props, b.getId());
    }

    /**
     * 启动时恢复（技术难点 7.7）：上次运行时程序被关闭、断电或崩溃，排队中、解析中的文件不会再被处理，
     * 批次也一直显示“处理中”。这里把这些文件标为失败（可以点“重试”），批次标为处理完成。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterrupted() {
        if (!remaining.isEmpty()) {
            return; // 启动后已经有新批次在处理，不能把它们当成上次中断的
        }
        int n = files.failInterrupted(EnumSet.of(FileRecord.Status.QUEUED, FileRecord.Status.PARSING),
                "程序在处理过程中被关闭，请点“重试”重新处理", LocalDateTime.now());
        int b = batches.finishRunning(LocalDateTime.now());
        if (n > 0 || b > 0) {
            log.info("启动恢复：{} 个未处理完的文件标为失败，{} 个批次标为处理完成", n, b);
        }
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

    /** 重试批次里所有失败的文件（v1.8.1）：程序中途被关闭后，整批文件都会标为失败，一个个点重试太慢 */
    public int retryFailed(Long batchId) {
        ArchiveBatch b = batches.findById(batchId).orElseThrow(() -> new BizException("批次不存在"));
        List<FileRecord> failed = files.findByBatchIdAndStatusInOrderByIdAsc(batchId, EnumSet.of(FileRecord.Status.FAILED));
        if (failed.isEmpty()) {
            throw new BizException("这个批次没有处理失败的文件");
        }
        for (FileRecord f : failed) {
            f.setStatus(FileRecord.Status.QUEUED);
            f.setErrorMessage(null);
            files.save(f);
            steps.record(f.getId(), "IMPORT", "重新处理", 0);
        }
        if (b.getStatus() == ArchiveBatch.Status.DONE) {
            b.setStatus(ArchiveBatch.Status.RUNNING);
            batches.save(b);
        }
        submit(b, failed.stream().map(FileRecord::getId).toList());
        return failed.size();
    }

    private ArchiveBatch newBatch(ArchiveBatch.SourceType type, String source, Path target, Long templateId,
                                  List<ImportScanner.Skipped> skipped) {
        // 没指定模板时用上次导入用的模板（v1.7.2），上次的模板被删了就用默认模板
        TemplateEntity t = templates.getOrDefault(templateId != null ? templateId : prefs.templateId());
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

    /** 没指定归档目录时用记住的目录（v1.7.2）；所在磁盘不存在时在导入前就提示，不等到归档时才失败 */
    private Path resolveTarget(String targetRoot) {
        String t = targetRoot == null || targetRoot.isBlank() ? prefs.archiveRoot() : targetRoot.strip();
        Path p = Path.of(t).toAbsolutePath().normalize();
        if (p.getParent() == null) {
            throw new BizException("归档目录不能是磁盘根目录，请选择一个文件夹");
        }
        String reason = ImportPrefsService.unavailableReason(p.toString());
        if (reason != null) {
            throw new BizException(reason);
        }
        return p;
    }

    /** 记住的导入习惯，给小R等调用方取默认值 */
    public ImportPrefsService prefs() {
        return prefs;
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