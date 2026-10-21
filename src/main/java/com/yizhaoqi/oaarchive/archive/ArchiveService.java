package com.yizhaoqi.oaarchive.archive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.common.Hashes;
import com.yizhaoqi.oaarchive.ledger.LedgerExporter;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.LedgerEntry;
import com.yizhaoqi.oaarchive.model.OperationLog;
import com.yizhaoqi.oaarchive.pipeline.StepRecorder;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import com.yizhaoqi.oaarchive.repository.OperationLogRepository;
import com.yizhaoqi.oaarchive.template.NamingRenderer;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归档执行与撤销（技术文档 6.7）：先预览，确认后逐个文件“写临时文件 → 校验哈希 → 原子改名”，
 * 每个文件写一条台账和一条操作日志；撤销只删除哈希与日志一致的文件。
 */
@Slf4j
@Service
public class ArchiveService {

    private final FileRecordRepository files;
    private final ArchiveBatchRepository batches;
    private final LedgerEntryRepository ledger;
    private final OperationLogRepository ops;
    private final ArchiveSequenceService sequences;
    private final TemplateService templates;
    private final LedgerExporter exporter;
    private final StepRecorder steps;
    private final ObjectMapper mapper;

    public ArchiveService(FileRecordRepository files, ArchiveBatchRepository batches, LedgerEntryRepository ledger,
                          OperationLogRepository ops, ArchiveSequenceService sequences, TemplateService templates,
                          LedgerExporter exporter, StepRecorder steps, ObjectMapper mapper) {
        this.files = files;
        this.batches = batches;
        this.ledger = ledger;
        this.ops = ops;
        this.sequences = sequences;
        this.templates = templates;
        this.exporter = exporter;
        this.steps = steps;
        this.mapper = mapper;
    }

    public record PreviewItem(Long fileId, String originalName, String status, String newName, String relativePath,
                              String conflict) {
    }

    public record ArchiveSummary(int archived, int skipped, int failed, List<String> messages, String ledgerFile) {
    }

    /** 预览：列出新文件名、目标路径和冲突（重名、目标已存在） */
    public List<PreviewItem> preview(Long batchId, boolean includeReview) {
        ArchiveBatch batch = batch(batchId);
        TemplateConfig tpl = templates.parse(batch.getTemplateSnapshot());
        Path root = Path.of(batch.getTargetRoot());
        Map<String, Integer> seen = new HashMap<>();
        List<PreviewItem> out = new ArrayList<>();
        for (FileRecord f : files.findByBatchIdAndStatusInOrderByIdAsc(batchId, archivable(includeReview, true))) {
            TargetPlanner.Plan p = TargetPlanner.plan(f, tpl, root, 0);
            String key = p.relative().toString().toLowerCase();
            String conflict = null;
            if (seen.merge(key, 1, Integer::sum) > 1) {
                conflict = "与本批次其他文件重名，归档时自动加序号";
            } else if (Files.exists(root.resolve(p.relative()))) {
                conflict = "目标位置已有同名文件，归档时自动加序号（内容相同则跳过）";
            }
            if (f.getStatus() == FileRecord.Status.DUPLICATE) {
                conflict = "台账中已有内容相同的文件";
            }
            out.add(new PreviewItem(f.getId(), f.getOriginalName(), f.getStatus().name(), p.fileName(),
                    p.relative().toString().replace('\\', '/'), conflict));
        }
        return out;
    }

    /**
     * 执行归档。
     *
     * @param fileIds       只归档这些文件；为空时归档批次内所有可归档文件
     * @param includeReview 是否同时归档“待确认”的文件
     */
    public synchronized ArchiveSummary archive(Long batchId, Collection<Long> fileIds, boolean includeReview) {
        ArchiveBatch batch = batch(batchId);
        TemplateConfig tpl = templates.parse(batch.getTemplateSnapshot());
        Path root = Path.of(batch.getTargetRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new BizException("无法创建归档目录：" + root);
        }
        Set<Long> only = fileIds == null || fileIds.isEmpty() ? null : new HashSet<>(fileIds);
        boolean explicit = only != null;
        int archived = 0, skipped = 0, failed = 0;
        List<String> messages = new ArrayList<>();
        for (FileRecord f : files.findByBatchIdAndStatusInOrderByIdAsc(batchId, archivable(includeReview || explicit, explicit))) {
            if (only != null && !only.contains(f.getId())) {
                continue;
            }
            try {
                if (archiveOne(batch, f, tpl, root)) {
                    archived++;
                } else {
                    skipped++;
                    messages.add(f.getOriginalName() + "：目标位置已有内容相同的文件，已跳过");
                }
            } catch (Exception e) {
                failed++;
                String msg = e instanceof BizException ? e.getMessage() : e.toString();
                messages.add(f.getOriginalName() + "：" + msg);
                steps.record(f.getId(), "ARCHIVE", "归档失败：" + msg, 0);
                log.warn("归档失败 {}", f.getOriginalName(), e);
            }
        }
        if (archived > 0) {
            batch.setStatus(ArchiveBatch.Status.ARCHIVED);
            batch.setArchivedAt(LocalDateTime.now());
            batches.save(batch);
        }
        String ledgerFile = exporter.refreshMasterLedger(root);
        return new ArchiveSummary(archived, skipped, failed, messages, ledgerFile);
    }
    /** 归档一个文件；目标位置已有内容相同的文件时返回 false */
    private boolean archiveOne(ArchiveBatch batch, FileRecord f, TemplateConfig tpl, Path root) throws IOException {
        Path work = Path.of(f.getWorkPath());
        if (!Files.exists(work)) {
            throw new BizException("工作区副本不存在，请重新导入");
        }
        String sha = f.getSha256();
        Map<String, String> vars = NamingRenderer.variables(new NamingRenderer.Fields(f.getDepartment(), f.getDocType(),
                f.getDocDate(), f.getTitle(), f.getOriginalName()), tpl, 0);
        int seq = sequences.next(vars.get("年") + "-" + vars.get("部门代码") + "-" + vars.get("类型代码"));
        TargetPlanner.Plan plan = TargetPlanner.plan(f, tpl, root, seq);

        Path target = root.resolve(plan.relative()).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("目标路径不在归档目录内");
        }
        Files.createDirectories(target.getParent());
        for (int n = 2; Files.exists(target); n++) {
            if (sha.equals(Hashes.sha256(target))) {
                steps.record(f.getId(), "ARCHIVE", "目标位置已有内容相同的文件，跳过：" + target, 0);
                return false;
            }
            target = target.resolveSibling(TargetPlanner.withSuffix(plan.fileName(), n));
        }

        long t0 = System.currentTimeMillis();
        Path tmp = target.resolveSibling(target.getFileName() + ".oa-tmp");
        Files.copy(work, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        if (!sha.equals(Hashes.sha256(tmp))) {
            Files.deleteIfExists(tmp);
            throw new BizException("复制后校验失败，文件可能被占用或磁盘异常");
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target);
        }
        boolean move = "MOVE".equals(tpl.getArchiveMode()) && batch.getSourceType() == ArchiveBatch.SourceType.FOLDER;
        if (move) {
            Path src = Path.of(f.getOriginalPath());
            // 原文件被改过时不删，避免误删用户的新内容
            if (Files.exists(src) && sha.equals(Hashes.sha256(src))) {
                Files.delete(src);
            } else {
                move = false;
            }
        }

        String mode = f.isCorrected() ? "人工修正"
                : f.getStatus() == FileRecord.Status.READY || f.getStatus() == FileRecord.Status.UNDONE ? "自动" : "人工确认";
        LedgerEntry e = new LedgerEntry();
        e.setFileRecordId(f.getId());
        e.setBatchId(batch.getId());
        e.setArchiveNo(uniqueArchiveNo(plan.archiveNo()));
        e.setTitle(f.getTitle());
        e.setDepartment(f.getDepartment());
        e.setDocType(f.getDocType());
        e.setDocDate(f.getDocDate());
        e.setOriginalName(f.getOriginalName());
        e.setNewName(target.getFileName().toString());
        e.setArchivePath(target.toString());
        e.setRelativePath(root.relativize(target).toString().replace('\\', '/'));
        e.setFormat(f.getExt());
        e.setSize(f.getSize());
        e.setPageCount(f.getPageCount());
        e.setKeywords(f.getKeywords());
        Map<String, String> extra = extras(f);
        e.setContractNo(extra.get("合同编号"));
        e.setDocNo(extra.get("文号"));
        e.setPartyA(extra.get("甲方"));
        e.setPartyB(extra.get("乙方"));
        e.setAmount(extra.get("金额"));
        e.setSealed("未检测");
        e.setRecognizeMode(mode);
        e.setConfidence(f.getOverallConf());
        e.setOcrUsed(f.isOcrUsed());
        e.setSha256(sha);
        e.setArchivedAt(LocalDateTime.now());
        ledger.save(e);

        OperationLog op = new OperationLog();
        op.setBatchId(batch.getId());
        op.setFileRecordId(f.getId());
        op.setAction(move ? "MOVE" : "COPY");
        op.setSourcePath(f.getOriginalPath());
        op.setTargetPath(target.toString());
        op.setSha256(sha);
        op.setCreatedAt(LocalDateTime.now());
        ops.save(op);

        f.setStatus(FileRecord.Status.ARCHIVED);
        f.setNewName(target.getFileName().toString());
        // 和预览时一样存相对路径，界面上展示一致；绝对路径记录在台账里
        f.setTargetPath(e.getRelativePath());
        f.setUpdatedAt(LocalDateTime.now());
        files.save(f);
        steps.record(f.getId(), "ARCHIVE", (move ? "移动" : "复制") + "到 " + e.getRelativePath() + "，档号 " + e.getArchiveNo(),
                System.currentTimeMillis() - t0);
        return true;
    }

    public record UndoSummary(int undone, int kept, List<String> messages) {
    }

    /** 撤销整个批次：只处理哈希与日志一致的文件；被用户改过的文件保留并提示 */
    public synchronized UndoSummary undo(Long batchId) {
        ArchiveBatch batch = batch(batchId);
        int undone = 0, kept = 0;
        List<String> messages = new ArrayList<>();
        Path root = null;
        for (OperationLog op : ops.findByBatchIdAndUndoneAtIsNullOrderByIdDesc(batchId)) {
            Path target = Path.of(op.getTargetPath());
            root = root == null ? Path.of(batch.getTargetRoot()) : root;
            try {
                if (Files.exists(target)) {
                    if (!op.getSha256().equals(Hashes.sha256(target))) {
                        kept++;
                        messages.add(target.getFileName() + "：归档后被修改过，未删除，请手动处理");
                        continue;
                    }
                    if ("MOVE".equals(op.getAction()) && !Files.exists(Path.of(op.getSourcePath()))) {
                        Files.createDirectories(Path.of(op.getSourcePath()).getParent());
                        Files.move(target, Path.of(op.getSourcePath()));
                    } else {
                        Files.delete(target);
                    }
                } else {
                    messages.add(target.getFileName() + "：文件已不存在");
                }
                op.setUndoneAt(LocalDateTime.now());
                ops.save(op);
                ledger.findFirstByFileRecordIdAndUndoneFalse(op.getFileRecordId()).ifPresent(le -> {
                    le.setUndone(true);
                    ledger.save(le);
                });
                files.findById(op.getFileRecordId()).ifPresent(f -> {
                    f.setStatus(FileRecord.Status.UNDONE);
                    f.setUpdatedAt(LocalDateTime.now());
                    files.save(f);
                    steps.record(f.getId(), "UNDO", "已撤销归档：" + target, 0);
                });
                undone++;
            } catch (IOException e) {
                kept++;
                messages.add(target.getFileName() + "：撤销失败 " + e.getMessage());
            }
        }
        if (undone > 0) {
            batch.setStatus(ArchiveBatch.Status.UNDONE);
            batches.save(batch);
        }
        if (root != null) {
            exporter.refreshMasterLedger(root);
        }
        return new UndoSummary(undone, kept, messages);
    }

    /** 档号唯一：撤销后重新归档时流水号会继续递增，这里只是兜底 */
    private String uniqueArchiveNo(String no) {
        String candidate = no;
        for (int i = 2; ledger.existsByArchiveNo(candidate); i++) {
            candidate = no + "-" + i;
        }
        return candidate;
    }

    private Map<String, String> extras(FileRecord f) {
        if (f.getExtraJson() == null || f.getExtraJson().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(f.getExtraJson(), new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static EnumSet<FileRecord.Status> archivable(boolean includeReview, boolean includeDuplicate) {
        EnumSet<FileRecord.Status> s = EnumSet.of(FileRecord.Status.READY, FileRecord.Status.UNDONE);
        if (includeReview) {
            s.add(FileRecord.Status.REVIEW);
        }
        if (includeDuplicate) {
            s.add(FileRecord.Status.DUPLICATE);
        }
        return s;
    }

    private ArchiveBatch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> new BizException("批次不存在"));
    }
}