package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.ProcessStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProcessStepRepository extends JpaRepository<ProcessStep, Long> {

    List<ProcessStep> findByFileRecordIdOrderBySeqAsc(Long fileRecordId);

    long countByFileRecordId(Long fileRecordId);

    @Modifying
    @Query("delete from ProcessStep s where s.fileRecordId in ?1")
    int deleteByFileRecordIds(Collection<Long> fileRecordIds);
}
