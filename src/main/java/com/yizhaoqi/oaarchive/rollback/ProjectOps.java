package com.yizhaoqi.oaarchive.rollback;

import com.yizhaoqi.oaarchive.archive.ArchiveService;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.common.Hashes;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.OperationLog;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.pipeline.RecordCleanupService;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.OperationLogRepository;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 会改动数据、可以回档的项目操作，界面和小R都从这里调用，保证每次都记录回档信息。
 * source：UI 表示在界面上操作，AI 表示小R执行（用户在确认卡片上点了确认）。
 */
@Service
public class ProjectOps {

    private final RollbackService rollback;
    private final ArchiveService archive;
    private final RecordCleanupService cleanup;
    private final BatchService batchService;
    private final TemplateService templates;
    private final ArchiveBatchRepository batches;
    private final FileRecordRepository files;
    private final OperationLogRepository ops;
    private final OaProperties props;
    private final JdbcTemplate jdbc;

    public ProjectOps(RollbackService rollback, ArchiveService archive, RecordCleanupService cleanup, BatchService batchService,
                      TemplateService templates, ArchiveBatchRepository batches, FileRecordRepository files,
                      OperationLogRepository ops, OaProperties props, JdbcTemplate jdbc) {
        this.rollback = rollback;
        this.archive = archive;
        this.cleanup = cleanup;
        this.batchService = batchService;
        this.templates = templates;
        this.batches = batches;
        this.files = files;
        this.ops = ops;
        this.props = props;
        this.jdbc = jdbc;
    }

    public ArchiveService.ArchiveSummary archive(Long batchId, Collection<Long> fileIds, boolean includeReview, String source) {
        ArchiveBatch b = batch(batchId);
        if (batchService.isRunning(batchId)) {
            throw new BizException("批次还在处理中，请等全部文件识别完成后再归档");
        }
        var op = new RollbackService.Op("ARCHIVE", "归档批次 #" + batchId, source,
                List.of("删除这次复制到归档目录的文件（移动的放回原位置）", "这些文件恢复为“待归档”，台账里去掉这次的记录"),
                List.of(batchId), List.of());
        return rollback.record(op, r -> {
            Long maxOp = jdbc.queryForObject("select coalesce(max(id), 0) from operation_log", Long.class);
            r.archiveAfter(batchId, maxOp);
            ArchiveService.ArchiveSummary s = archive.archive(batchId, fileIds, includeReview);
            if (s.archived() == 0) {
                r.skip();
            }
            r.done("归档了 " + s.archived() + " 个文件到 " + b.getTargetRoot()
                    + (s.skipped() > 0 ? "，跳过 " + s.skipped() + " 个" : "") + (s.failed() > 0 ? "，失败 " + s.failed() + " 个" : ""));
            return s;
        });
    }

    public ArchiveService.UndoSummary undo(Long batchId, String source) {
        batch(batchId);
        List<OperationLog> active = ops.findByBatchIdAndUndoneAtIsNullOrderByIdDesc(batchId);
        if (active.isEmpty()) {
            throw new BizException("这个批次没有可以撤销的归档");
        }
        long total = 0;
        for (OperationLog o : active) {
            try {
                Path t = Path.of(o.getTargetPath());
                total += Files.exists(t) ? Files.size(t) : 0;
            } catch (IOException ignored) {
                // 读不到大小时按 0 算
            }
        }
        boolean keepCopies = total <= RollbackService.HOLD_LIMIT;
        var op = new RollbackService.Op("UNDO", "撤销批次 #" + batchId + " 的归档", source,
                List.of("把撤销时删除的归档文件放回归档目录", "这些文件恢复为“已归档”，台账恢复原来的记录"),
                List.of(batchId), List.of());
        return rollback.record(op, r -> {
            if (!keepCopies) {
                r.cannotRollback("这次撤销涉及的文件超过 2 GB，没有备份，不能回档");
            }
            try {
                for (OperationLog o : active) {
                    Path t = Path.of(o.getTargetPath());
                    // 和撤销本身的判断一致：只有内容没变的文件才会被删除或移回
                    if (keepCopies && Files.exists(t) && o.getSha256().equals(Hashes.sha256(t))) {
                        Path src = o.getSourcePath() == null ? null : Path.of(o.getSourcePath());
                        // 移动归档且原位置空着时，撤销会把文件移回原位置，回档时再移过来；否则撤销直接删除，需要先备份
                        boolean moveBack = "MOVE".equals(o.getAction()) && src != null && !Files.exists(src);
                        r.holdUndo(t, src, o.getSha256(), moveBack);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ArchiveService.UndoSummary s = archive.undo(batchId);
            r.done("撤销了 " + s.undone() + " 个文件的归档" + (s.kept() > 0 ? "，" + s.kept() + " 个被修改过的文件保留" : ""));
            return s;
        });
    }

    public RecordCleanupService.Result deleteBatch(Long batchId, String source) {
        batch(batchId);
        if (batchService.isRunning(batchId)) {
            throw new BizException("批次还在识别中，请等处理完成后再删除");
        }
        var op = new RollbackService.Op("DELETE_BATCH", "删除批次 #" + batchId + " 的记录", source,
                List.of("恢复这个批次的导入记录、识别结果和处理步骤", "恢复工作区里的文件副本"),
                List.of(batchId), List.of());
        return rollback.record(op, r -> {
            try {
                r.holdWork(props.workRoot().resolve(String.valueOf(batchId)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            RecordCleanupService.Result res = cleanup.deleteBatch(batchId);
            r.done(res.message());
            return res;
        });
    }

    public void deleteFile(Long fileId, String source) {
        FileRecord f = files.findById(fileId).orElseThrow(() -> new BizException("文件不存在"));
        if (f.getStatus() == FileRecord.Status.ARCHIVED) {
            throw new BizException("文件已归档，请先撤销该批次再移除");
        }
        if (f.getStatus() == FileRecord.Status.QUEUED || f.getStatus() == FileRecord.Status.PARSING) {
            throw new BizException("文件还在识别中，请稍候");
        }
        var op = new RollbackService.Op("DELETE_FILE", "从批次 #" + f.getBatchId() + " 移除“" + f.getOriginalName() + "”", source,
                List.of("恢复这个文件的导入记录和识别结果"), List.of(f.getBatchId()), List.of());
        rollback.record(op, r -> {
            try {
                if (f.getWorkPath() != null) {
                    Path work = Path.of(f.getWorkPath()).normalize();
                    if (work.startsWith(props.workRoot())) {
                        r.holdWork(work);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            cleanup.deleteFile(fileId);
            r.done("移除了“" + f.getOriginalName() + "”（原文件没有删除）");
            return null;
        });
    }

    public void deleteTemplate(Long id, String source) {
        TemplateEntity t = templates.get(id);
        if (t.isBuiltin()) {
            throw new BizException("内置模板不能删除");
        }
        var op = new RollbackService.Op("DELETE_TEMPLATE", "删除模板“" + t.getName() + "”", source,
                List.of("恢复这个模板"), List.of(), List.of(id));
        rollback.record(op, r -> {
            templates.delete(id);
            r.done("删除了模板“" + t.getName() + "”");
            return null;
        });
    }

    public TemplateEntity updateTemplate(Long id, TemplateConfig cfg, String source) {
        TemplateEntity t = templates.get(id);
        var op = new RollbackService.Op("UPDATE_TEMPLATE", "修改模板“" + t.getName() + "”", source,
                List.of("模板恢复成修改前的规则"), List.of(), List.of(id));
        return rollback.record(op, r -> {
            TemplateEntity saved = templates.update(id, null, cfg);
            r.done("修改了模板“" + t.getName() + "”");
            return saved;
        });
    }

    public TemplateEntity createTemplate(String name, TemplateConfig cfg, String source) {
        var op = new RollbackService.Op("CREATE_TEMPLATE", "新建模板“" + name + "”", source,
                List.of("删除这个新建的模板"), List.of(), List.of());
        return rollback.record(op, r -> {
            TemplateEntity saved = templates.create(name, cfg);
            r.addTemplate(saved.getId());
            r.done("新建了模板“" + saved.getName() + "”");
            return saved;
        });
    }

    public FileRecord correct(Long fileId, String department, String docType, LocalDate date, String title, String source) {
        FileRecord f = files.findById(fileId).orElseThrow(() -> new BizException("文件不存在"));
        var op = new RollbackService.Op("CORRECT", "修正“" + f.getOriginalName() + "”的识别结果", source,
                List.of("识别结果恢复成修正前的样子"), List.of(f.getBatchId()), List.of());
        return rollback.record(op, r -> {
            FileRecord saved = batchService.correct(fileId, department, docType, date, title);
            r.done("修正了“" + f.getOriginalName() + "”的识别结果");
            return saved;
        });
    }

    public void retry(Long fileId, String source) {
        FileRecord f = files.findById(fileId).orElseThrow(() -> new BizException("文件不存在"));
        if (f.getStatus() != FileRecord.Status.FAILED) {
            throw new BizException("只有处理失败的文件可以重试");
        }
        var op = new RollbackService.Op("RETRY", "重新识别“" + f.getOriginalName() + "”", source,
                List.of("识别结果恢复成重试前的样子（失败状态）"), List.of(f.getBatchId()), List.of());
        rollback.record(op, r -> {
            batchService.retry(fileId);
            r.done("重新识别“" + f.getOriginalName() + "”");
            return null;
        });
    }

    public ArchiveBatch importFolder(String folder, String targetRoot, Long templateId, String source) {
        var op = new RollbackService.Op("IMPORT", "导入文件夹 " + folder, source,
                List.of("删除这次导入产生的批次记录（原文件不受影响）"), List.of(), List.of());
        return rollback.record(op, r -> {
            ArchiveBatch b;
            try {
                b = batchService.createFromFolder(folder, targetRoot, templateId);
            } catch (IOException e) {
                throw new BizException("导入失败：" + e.getMessage());
            }
            r.importWork(b.getId());
            r.done("导入了 " + folder + "，批次 #" + b.getId());
            return b;
        });
    }

    private ArchiveBatch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> new BizException("批次不存在"));
    }
}
