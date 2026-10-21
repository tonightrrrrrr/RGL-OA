package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.FileRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {

    List<FileRecord> findByBatchIdOrderByIdAsc(Long batchId);

    List<FileRecord> findByBatchIdAndStatusInOrderByIdAsc(Long batchId, Collection<FileRecord.Status> statuses);

    List<FileRecord> findByStatusOrderByIdDesc(FileRecord.Status status);

    long countByStatus(FileRecord.Status status);

    @Query("select f.status, count(f) from FileRecord f where f.batchId = ?1 group by f.status")
    List<Object[]> countByStatus(Long batchId);

    @Modifying
    @Query("delete from FileRecord f where f.batchId = ?1")
    int deleteByBatch(Long batchId);
}
