package com.yizhaoqi.oaarchive.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 档号流水号：按“年-部门代码-类型代码”分别计数，在事务内加锁递增。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "archive_sequence")
public class ArchiveSequence {

    @Id
    @Column(length = 64)
    private String seqKey;

    private int currentValue;

    public ArchiveSequence(String seqKey) {
        this.seqKey = seqKey;
    }
}
