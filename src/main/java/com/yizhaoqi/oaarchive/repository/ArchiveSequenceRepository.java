package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.ArchiveSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ArchiveSequenceRepository extends JpaRepository<ArchiveSequence, String> {

    /** 加行锁读取，保证并发归档时档号不重复 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ArchiveSequence s where s.seqKey = ?1")
    Optional<ArchiveSequence> lockByKey(String key);
}
