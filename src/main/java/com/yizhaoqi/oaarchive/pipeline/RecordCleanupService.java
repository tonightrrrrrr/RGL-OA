package com.yizhaoqi.oaarchive.pipeline;

import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import com.yizhaoqi.oaarchive.repository.OperationLogRepository;
import com.yizhaoqi.oaarchive.repository.ProcessStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 删除导入记录（不是删除文件）：
 * 只清理程序自己的记录（批次、文件处理记录、处理步骤、操作日志）和工作区里的副本。
 * 原文件、归档目录里的文件、未撤销的台账记录一律不动。
 */
@Slf4j
@Service
public class RecordCleanupService {

    private final ArchiveBatchRepository batches;
    private final FileRecordRepository files;
    private final ProcessStepRepository steps;
    private final OperationLogRepository ops;
    private final LedgerEntryRepository ledger;
    private final BatchService batchService;
    private final OaProperties props;

    public RecordCleanupService(ArchiveBatchRepository batches, FileRecordRepository files, ProcessStepRepository steps,
                                OperationLogRepository ops, LedgerEntryRepository ledger, BatchService batchService,
                                OaProperties props) {
        this.batches = batches;
        this.files = files;
        this.steps = steps;
        this.ops = ops;
        this.ledger = ledger;
        this.batchService = batchService;
        this.props = props;
    }

    public record Result(int deletedFiles, long keptLedger, String message) {
    }

    /** 删除整个批次的记录 */
    @Transactional
    public Result deleteBatch(Long batchId) {
        ArchiveBatch b = batches.findById(batchId).orElseThrow(() -> new BizException("批次不存在"));
        if (batchService.isRunning(batchId)) {
            throw new BizException("批次还在识别中，请等处理完成后再删除");
        }
        List<FileRecord> list = files.findByBatchIdOrderByIdAsc(batchId);
        long kept = ledger.countByBatchIdAndUndoneFalse(batchId);
        if (!list.isEmpty()) {
            steps.deleteByFileRecordIds(list.stream().map(FileRecord::getId).toList());
        }
        ops.deleteByBatch(batchId);
        ledger.deleteUndoneByBatch(batchId);
        files.deleteByBatch(batchId);
        batches.delete(b);
        deleteWorkDir(batchId);
        String msg = kept > 0
                ? "已删除批次记录。已归档的 " + kept + " 个文件和台账记录保留不变，但这个批次不能再撤销"
                : "已删除批次记录";
        return new Result(list.size(), kept, msg);
    }

    /** 从批次中移除一个还没归档的文件 */
    @Transactional
    public void deleteFile(Long fileId) {
        FileRecord f = files.findById(fileId).orElseThrow(() -> new BizException("文件不存在"));
        switch (f.getStatus()) {
            case ARCHIVED -> throw new BizException("文件已归档，请先撤销该批次再移除");
            case QUEUED, PARSING -> throw new BizException("文件还在识别中，请稍候");
            default -> {
                // 其他状态都可以移除
            }
        }
        steps.deleteByFileRecordIds(List.of(fileId));
        ops.deleteByFileRecord(fileId);
        ledger.deleteUndoneByFileRecord(fileId);
        files.delete(f);
        if (f.getWorkPath() != null) {
            Path work = Path.of(f.getWorkPath()).normalize();
            if (work.startsWith(props.workRoot())) {
                try {
                    Files.deleteIfExists(work);
                } catch (IOException e) {
                    log.warn("工作区副本删除失败：{}", work);
                }
            }
        }
    }

    /** 只删工作区 data/work/{批次号}，不会碰其他目录 */
    private void deleteWorkDir(Long batchId) {
        Path dir = props.workRoot().resolve(String.valueOf(batchId)).normalize();
        if (!dir.startsWith(props.workRoot()) || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("工作区文件删除失败：{}", p);
                }
            });
        } catch (IOException e) {
            log.warn("工作区目录删除失败：{}", dir);
        }
    }
}
