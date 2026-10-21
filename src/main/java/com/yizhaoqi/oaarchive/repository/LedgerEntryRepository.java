package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long>, JpaSpecificationExecutor<LedgerEntry> {

    Optional<LedgerEntry> findFirstBySha256AndUndoneFalse(String sha256);

    boolean existsByArchiveNo(String archiveNo);

    /** 已撤销的台账记录对应的文件已经删除，删除批次时一并清掉 */
    @Modifying
    @Query("delete from LedgerEntry e where e.batchId = ?1 and e.undone = true")
    int deleteUndoneByBatch(Long batchId);

    @Modifying
    @Query("delete from LedgerEntry e where e.fileRecordId = ?1 and e.undone = true")
    int deleteUndoneByFileRecord(Long fileRecordId);

    long countByBatchIdAndUndoneFalse(Long batchId);

    Optional<LedgerEntry> findFirstByFileRecordIdAndUndoneFalse(Long fileRecordId);

    List<LedgerEntry> findByUndoneFalseOrderByIdAsc();

    long countByUndoneFalse();
}
