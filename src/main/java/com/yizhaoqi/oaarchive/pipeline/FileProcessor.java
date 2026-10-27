package com.yizhaoqi.oaarchive.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.archive.TargetPlanner;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.common.Hashes;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.parser.ParserRegistry;
import com.yizhaoqi.oaarchive.recognize.FieldResult;
import com.yizhaoqi.oaarchive.recognize.RecognitionEngine;
import com.yizhaoqi.oaarchive.recognize.RecognitionResult;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单个文件的处理流程（技术文档 4.2）：复制到工作区 → SHA-256 去重 → 解析（带超时）→ 识别 → 待归档 / 待确认。
 * 所有异常都在这里接住并写入 error_message，一个文件失败不影响其他文件（技术文档 6.1.4）。
 */
@Slf4j
@Component
public class FileProcessor {

    private final FileRecordRepository files;
    private final LedgerEntryRepository ledger;
    private final ParserRegistry parsers;
    private final RecognitionEngine engine;
    private final StepRecorder steps;
    private final ProgressHub hub;
    private final OaProperties props;
    private final ObjectMapper mapper;
    private final ExecutorService parseExecutor;
    /** 简历的岗位没有匹配的岗位规则时问小R（v1.8）；AI 没开时它直接返回 null */
    private final com.yizhaoqi.oaarchive.recognize.PositionAdvisor positionAdvisor;
    /** 每个批次已出现的哈希，用于批次内去重 */
    private final Map<Long, Set<String>> seenInBatch = new ConcurrentHashMap<>();

    public FileProcessor(FileRecordRepository files, LedgerEntryRepository ledger, ParserRegistry parsers,
                         RecognitionEngine engine, StepRecorder steps, ProgressHub hub, OaProperties props,
                         ObjectMapper mapper, @Qualifier("parseExecutor") ExecutorService parseExecutor,
                         com.yizhaoqi.oaarchive.recognize.PositionAdvisor positionAdvisor) {
        this.positionAdvisor = positionAdvisor;
        this.files = files;
        this.ledger = ledger;
        this.parsers = parsers;
        this.engine = engine;
        this.steps = steps;
        this.hub = hub;
        this.props = props;
        this.mapper = mapper;
        this.parseExecutor = parseExecutor;
    }

    public void process(Long fileId, TemplateConfig tpl, Path batchRoot) {
        FileRecord f = files.findById(fileId).orElse(null);
        if (f == null) {
            return;
        }
        try {
            update(f, FileRecord.Status.PARSING);
            Path work = copyToWork(f);
            String sha = Hashes.sha256(work);
            f.setSha256(sha);
            f.setWorkPath(work.toString());
            steps.record(f.getId(), "IMPORT", "复制到工作区，SHA-256 " + sha.substring(0, 12) + "…", 0);

            boolean duplicate = false;
            var existing = ledger.findFirstBySha256AndUndoneFalse(sha);
            if (existing.isPresent()) {
                duplicate = true;
                f.setDuplicateOf(existing.get().getId());
                steps.record(f.getId(), "IMPORT", "台账中已有内容相同的文件：" + existing.get().getArchiveNo(), 0);
            } else if (!seenInBatch.computeIfAbsent(f.getBatchId(), k -> ConcurrentHashMap.newKeySet()).add(sha)) {
                duplicate = true;
                steps.record(f.getId(), "IMPORT", "本批次中已有内容相同的文件", 0);
            }

            long t0 = System.currentTimeMillis();
            DocModel doc = parseWithTimeout(work, f.getOriginalName());
            long parseMs = System.currentTimeMillis() - t0;
            f.setPageCount(doc.pageCount());
            f.setOcrUsed(doc.ocrUsed());
            steps.record(f.getId(), doc.ocrUsed() ? "OCR" : "PARSE",
                    String.format("解析 %s，%d 页，%d 段文字%s", doc.format(), doc.pageCount(), doc.blocks().size(),
                            doc.notes().isEmpty() ? "" : "；" + String.join("；", doc.notes())), parseMs);

            t0 = System.currentTimeMillis();
            RecognitionResult r = engine.recognize(doc, tpl, positionAdvisor);
            applyRecognition(f, r, doc);
            steps.record(f.getId(), "RECOGNIZE", summary(r), System.currentTimeMillis() - t0);

            if (duplicate) {
                f.setStatus(FileRecord.Status.DUPLICATE);
            } else {
                f.setStatus(r.needsReview() ? FileRecord.Status.REVIEW : FileRecord.Status.READY);
            }
            planName(f, tpl, batchRoot);
            f.setErrorMessage(null);
            save(f);
        } catch (Exception e) {
            String msg = e instanceof BizException ? e.getMessage() : describe(e);
            log.warn("处理失败 {}：{}", f.getOriginalName(), msg, e instanceof BizException ? null : e);
            f.setStatus(FileRecord.Status.FAILED);
            f.setErrorMessage(msg);
            steps.record(f.getId(), "PARSE", "处理失败：" + msg, 0);
            save(f);
        }
    }

    /** 批次结束时释放批次内去重用的内存 */
    public void forgetBatch(Long batchId) {
        seenInBatch.remove(batchId);
    }

    /** 根据当前字段计算预览用的新文件名和目标路径（序号在真正归档时才分配） */
    public void planName(FileRecord f, TemplateConfig tpl, Path batchRoot) {
        TargetPlanner.Plan p = TargetPlanner.plan(f, tpl, batchRoot, 0);
        f.setNewName(p.fileName());
        f.setTargetPath(p.relative().toString().replace('\\', '/'));
    }

    private Path copyToWork(FileRecord f) throws IOException {
        Path src = Path.of(f.getOriginalPath());
        Path dir = props.workRoot().resolve(String.valueOf(f.getBatchId()));
        Files.createDirectories(dir);
        String ext = f.getExt() == null || f.getExt().isEmpty() ? "" : "." + f.getExt();
        Path work = dir.resolve(f.getId() + ext);
        if (src.normalize().equals(work.normalize())) {
            return work; // 上传的文件已经在工作区
        }
        try {
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (FileSystemException e) {
            throw new BizException("文件被占用或无法读取（如正在 Word 中打开），请关闭后重试");
        }
        return work;
    }

    private DocModel parseWithTimeout(Path work, String name) throws Exception {
        Future<DocModel> fut = parseExecutor.submit(() -> parsers.parse(work, name));
        try {
            return fut.get(props.getPipeline().getFileTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fut.cancel(true);
            throw new BizException("解析超时（超过 " + props.getPipeline().getFileTimeoutSeconds() + " 秒）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BizException be) {
                throw be;
            }
            throw new BizException("文件无法解析，可能已损坏或格式不受支持（" + describe(cause) + "）");
        }
    }

    private void applyRecognition(FileRecord f, RecognitionResult r, DocModel doc) throws JsonProcessingException {
        f.setDepartment(r.department().value());
        f.setDocType(r.docType().value());
        f.setDocDate(r.date().value() == null ? null : LocalDate.parse(r.date().value()));
        f.setTitle(r.title().value());
        f.setDeptConf(r.department().confidence());
        f.setTypeConf(r.docType().confidence());
        f.setDateConf(r.date().confidence());
        f.setOverallConf(r.overall());
        f.setKeywords(String.join("、", r.keywords()));
        f.setReviewReasons(String.join("；", r.reviewReasons()));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("department", r.department());
        ev.put("docType", r.docType());
        ev.put("date", r.date());
        ev.put("title", r.title());
        ev.put("regions", r.regionLines());
        ev.put("notes", doc.notes());
        f.setEvidenceJson(mapper.writeValueAsString(ev));
        f.setExtraJson(mapper.writeValueAsString(r.extras()));
        String text = doc.fullText();
        f.setTextExcerpt(text.length() > 4000 ? text.substring(0, 4000) : text);
    }

    private static String summary(RecognitionResult r) {
        return String.format("部门 %s（%s）；类型 %s（%s）；日期 %s（%s）%s",
                r.department().value(), top(r.department()), r.docType().value(), top(r.docType()),
                r.date().value(), top(r.date()),
                r.needsReview() ? "；待确认：" + String.join("、", r.reviewReasons()) : "；置信度达标");
    }

    private static String top(FieldResult r) {
        String basis = r.evidence().isEmpty() ? "无依据" : r.evidence().get(0).snippet();
        return String.format("%.2f，依据：%s", r.confidence(), basis);
    }

    private void update(FileRecord f, FileRecord.Status s) {
        f.setStatus(s);
        save(f);
    }

    private void save(FileRecord f) {
        f.setUpdatedAt(LocalDateTime.now());
        files.save(f);
        hub.publish(f.getBatchId(), "file", Map.of("id", f.getId(), "status", f.getStatus().name()));
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : "：" + t.getMessage());
    }
}
