package com.yizhaoqi.oaarchive.pipeline;

import com.yizhaoqi.oaarchive.model.ProcessStep;
import com.yizhaoqi.oaarchive.repository.ProcessStepRepository;
import org.springframework.stereotype.Component;

/**
 * 记录每个文件的处理步骤（技术文档 2.3“智能体”过程展示）。
 */
@Component
public class StepRecorder {

    private final ProcessStepRepository repo;

    public StepRecorder(ProcessStepRepository repo) {
        this.repo = repo;
    }

    public void record(Long fileId, String step, String detail, long durationMs) {
        if (fileId == null) {
            return;
        }
        int seq = (int) repo.countByFileRecordId(fileId) + 1;
        repo.save(new ProcessStep(fileId, seq, step, detail, durationMs));
    }
}
