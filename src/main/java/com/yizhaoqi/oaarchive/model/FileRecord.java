package com.yizhaoqi.oaarchive.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 文件处理记录（技术文档 7.1 file_record）。
 */
@Getter
@Setter
@Entity
@Table(name = "file_record", indexes = {
        @Index(name = "idx_file_batch", columnList = "batchId"),
        @Index(name = "idx_file_sha", columnList = "sha256")})
public class FileRecord {

    /** 技术文档 4.3 文件状态 */
    public enum Status { QUEUED, PARSING, READY, REVIEW, ARCHIVED, UNDONE, FAILED, DUPLICATE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long batchId;

    @Column(length = 1024)
    private String originalPath;

    @Column(length = 512)
    private String originalName;

    @Column(length = 1024)
    private String workPath;

    @Column(length = 16)
    private String ext;

    private Long size;

    @Column(length = 64)
    private String sha256;

    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status;

    private boolean ocrUsed;

    @Column(length = 512)
    private String title;

    @Column(length = 64)
    private String department;

    @Column(length = 64)
    private String docType;

    private LocalDate docDate;

    private Double deptConf;
    private Double typeConf;
    private Double dateConf;
    private Double overallConf;

    /** 各字段的判断依据和候选值（JSON） */
    @Lob
    private String evidenceJson;

    /** 扩展字段：合同编号、甲乙方、金额等（JSON） */
    @Lob
    private String extraJson;

    @Column(length = 512)
    private String keywords;

    @Column(length = 1000)
    private String reviewReasons;

    /** 清洗后的正文前 4000 字，用于预览 */
    @Lob
    private String textExcerpt;

    @Column(length = 512)
    private String newName;

    @Column(length = 1024)
    private String targetPath;

    /** 是否被人工修正过 */
    private boolean corrected;

    /** 重复文件对应的已有台账记录 */
    private Long duplicateOf;

    @Column(length = 2000)
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
