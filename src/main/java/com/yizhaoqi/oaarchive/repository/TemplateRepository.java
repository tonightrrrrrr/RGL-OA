package com.yizhaoqi.oaarchive.repository;

import com.yizhaoqi.oaarchive.model.TemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository extends JpaRepository<TemplateEntity, Long> {

    Optional<TemplateEntity> findByName(String name);

    List<TemplateEntity> findAllByOrderByBuiltinDescIdAsc();
}
