package com.yizhaoqi.oaarchive.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 导入批次（技术文档 7.1 archive_batch）。
 */
@Getter
@Setter
@Entity
@Table(name = "archive_batch")
public class ArchiveBatch {

    public enum SourceType { FOLDER, UPLOAD, INBOX }

    public enum Status { RUNNING, DONE, ARCHIVED, UNDONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private SourceType sourceType;

    @Column(length = 1024)
    private String sourcePath;

    @Column(length = 1024)
    private String targetRoot;

    private Long templateId;

    @Column(length = 100)
    private String templateName;

    /** 模板 JSON 快照：之后修改模板不影响这个批次 */
    @Lob
    private String templateSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status;

    /** 扫描时被过滤掉的文件数（临时文件、隐藏文件、空文件、超大文件） */
    private int skippedCount;

    /** 被过滤文件的说明，如“~$xx.docx（Office 临时文件）” */
    @Column(length = 2000)
    private String skippedSummary;

    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private LocalDateTime archivedAt;
}
