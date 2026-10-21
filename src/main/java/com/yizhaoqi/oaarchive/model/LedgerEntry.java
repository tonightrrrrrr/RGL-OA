package com.yizhaoqi.oaarchive.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 文件目录台账（技术文档 6.8.1）。
 */
@Getter
@Setter
@Entity
@Table(name = "ledger_entry", indexes = {
        @Index(name = "uk_ledger_no", columnList = "archiveNo", unique = true),
        @Index(name = "idx_ledger_dept", columnList = "department"),
        @Index(name = "idx_ledger_type", columnList = "docType"),
        @Index(name = "idx_ledger_date", columnList = "docDate"),
        @Index(name = "idx_ledger_sha", columnList = "sha256")})
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fileRecordId;
    private Long batchId;

    @Column(length = 64)
    private String archiveNo;

    @Column(length = 512)
    private String title;

    @Column(length = 64)
    private String department;

    @Column(length = 64)
    private String docType;

    private LocalDate docDate;

    @Column(length = 512)
    private String originalName;

    @Column(length = 512)
    private String newName;

    /** 归档后的绝对路径 */
    @Column(length = 1024)
    private String archivePath;

    /** 相对于归档根目录的路径 */
    @Column(length = 1024)
    private String relativePath;

    @Column(length = 16)
    private String format;

    private Long size;
    private Integer pageCount;

    @Column(length = 512)
    private String keywords;

    @Column(length = 128)
    private String contractNo;

    @Column(length = 128)
    private String docNo;

    @Column(length = 256)
    private String partyA;

    @Column(length = 256)
    private String partyB;

    @Column(length = 64)
    private String amount;

    @Column(length = 16)
    private String sealed;

    @Column(length = 256)
    private String sealUnit;

    /** 自动 / 人工确认 / 人工修正 */
    @Column(length = 16)
    private String recognizeMode;

    private Double confidence;
    private boolean ocrUsed;

    @Column(length = 64)
    private String sha256;

    private LocalDateTime archivedAt;
    private boolean undone;
}
