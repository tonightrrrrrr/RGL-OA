package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ArchiveBatchRepository extends JpaRepository<ArchiveBatch, Long> {

    List<ArchiveBatch> findTop50ByOrderByIdDesc();

    /** 启动恢复：上次没处理完的批次标为处理完成 */
    @Modifying
    @Query("update ArchiveBatch b set b.status = com.yizhaoqi.oaarchive.model.ArchiveBatch.Status.DONE, b.finishedAt = ?1 "
            + "where b.status = com.yizhaoqi.oaarchive.model.ArchiveBatch.Status.RUNNING")
    int finishRunning(LocalDateTime now);
}
