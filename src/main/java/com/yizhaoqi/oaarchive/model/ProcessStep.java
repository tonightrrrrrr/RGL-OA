package com.yizhaoqi.oaarchive.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 处理步骤记录：界面上展示“感知 → 推理 → 执行”的过程（技术文档 2.3）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "process_step", indexes = @Index(name = "idx_step_file", columnList = "fileRecordId"))
public class ProcessStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fileRecordId;
    private int seq;

    /** IMPORT / PARSE / OCR / RECOGNIZE / REVIEW / ARCHIVE / UNDO */
    @Column(length = 16)
    private String step;

    @Column(length = 2000)
    private String detail;

    private long durationMs;
    private LocalDateTime createdAt;

    public ProcessStep(Long fileRecordId, int seq, String step, String detail, long durationMs) {
        this.fileRecordId = fileRecordId;
        this.seq = seq;
        this.step = step;
        this.detail = detail != null && detail.length() > 2000 ? detail.substring(0, 2000) : detail;
        this.durationMs = durationMs;
        this.createdAt = LocalDateTime.now();
    }
}
