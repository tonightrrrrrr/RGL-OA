package com.yizhaoqi.oaarchive.archive;

import com.yizhaoqi.oaarchive.model.ArchiveSequence;
import com.yizhaoqi.oaarchive.repository.ArchiveSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 档号流水号：按“年-部门代码-类型代码”计数。单进程内用 synchronized，数据库层再加行锁。
 */
@Service
public class ArchiveSequenceService {

    private final ArchiveSequenceRepository repo;

    public ArchiveSequenceService(ArchiveSequenceRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized int next(String key) {
        ArchiveSequence s = repo.lockByKey(key).orElseGet(() -> repo.saveAndFlush(new ArchiveSequence(key)));
        s.setCurrentValue(s.getCurrentValue() + 1);
        repo.save(s);
        return s.getCurrentValue();
    }
}
