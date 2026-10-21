package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {

    List<OperationLog> findByBatchIdAndUndoneAtIsNullOrderByIdDesc(Long batchId);

    @Modifying
    @Query("delete from OperationLog o where o.batchId = ?1")
    int deleteByBatch(Long batchId);

    @Modifying
    @Query("delete from OperationLog o where o.fileRecordId = ?1")
    int deleteByFileRecord(Long fileRecordId);
}
