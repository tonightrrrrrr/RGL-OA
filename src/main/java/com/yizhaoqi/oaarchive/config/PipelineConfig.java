package com.yizhaoqi.oaarchive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 处理线程池（技术文档 4.4）：pipeline 跑每个文件的完整流程；parse 单独一个池，用来给解析加超时。
 */
@Configuration
public class PipelineConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService pipelineExecutor(OaProperties props) {
        int n = Math.max(1, Math.min(props.getPipeline().getThreads(), Runtime.getRuntime().availableProcessors()));
        return new ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), named("oa-pipeline"));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService parseExecutor() {
        return new ThreadPoolExecutor(0, 16, 60, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
                named("oa-parse"), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
