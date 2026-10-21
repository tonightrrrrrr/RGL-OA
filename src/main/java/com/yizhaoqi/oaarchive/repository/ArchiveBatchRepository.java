package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArchiveBatchRepository extends JpaRepository<ArchiveBatch, Long> {

    List<ArchiveBatch> findTop50ByOrderByIdDesc();
}
