package com.yizhaoqi.oaarchive.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 归档模板（技术文档 6.6）：内容是 TemplateConfig 的 JSON。内置模板只读。
 */
@Getter
@Setter
@Entity
@Table(name = "archive_template")
public class TemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100, unique = true)
    private String name;

    private boolean builtin;

    @Lob
    private String contentJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
