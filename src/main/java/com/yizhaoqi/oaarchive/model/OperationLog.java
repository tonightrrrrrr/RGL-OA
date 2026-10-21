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

import java.time.LocalDateTime;

/**
 * 归档操作日志，用于撤销（技术文档 6.7.1）。
 */
@Getter
@Setter
@Entity
@Table(name = "operation_log", indexes = @Index(name = "idx_op_batch", columnList = "batchId"))
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long batchId;
    private Long fileRecordId;

    /** COPY 或 MOVE */
    @Column(length = 8)
    private String action;

    @Column(length = 1024)
    private String sourcePath;

    @Column(length = 1024)
    private String targetPath;

    @Column(length = 64)
    private String sha256;

    private LocalDateTime createdAt;
    private LocalDateTime undoneAt;
}
