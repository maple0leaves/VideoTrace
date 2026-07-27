package com.example.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class ThreadPoolConfig {

    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor(
            @Value("${app.executor.ai.core-size:2}") int coreSize,
            @Value("${app.executor.ai.max-size:4}") int maxSize,
            @Value("${app.executor.ai.queue-capacity:50}") int queueCapacity) {
        return executor("AI-Thread-", coreSize, maxSize, queueCapacity);
    }

    @Bean("analysisTaskExecutor")
    public AsyncTaskExecutor analysisTaskExecutor(
            @Value("${app.executor.analysis.workers:2}") int workers) {
        return executor("Analysis-Task-", workers, workers, 0);
    }

    @Bean("contextExecutor")
    public AsyncTaskExecutor contextExecutor(
            @Value("${app.executor.context.core-size:4}") int coreSize,
            @Value("${app.executor.context.max-size:4}") int maxSize,
            @Value("${app.executor.context.queue-capacity:4}") int queueCapacity) {
        return executor("Context-Branch-", coreSize, maxSize, queueCapacity);
    }

    @Bean("asrExecutor")
    public AsyncTaskExecutor asrExecutor(
            @Value("${app.executor.asr.core-size:2}") int coreSize,
            @Value("${app.executor.asr.max-size:2}") int maxSize,
            @Value("${app.executor.asr.queue-capacity:16}") int queueCapacity) {
        return executor("ASR-Segment-", coreSize, maxSize, queueCapacity);
    }

    @Bean("ocrExecutor")
    public AsyncTaskExecutor ocrExecutor(
            @Value("${app.executor.ocr.core-size:2}") int coreSize,
            @Value("${app.executor.ocr.max-size:2}") int maxSize,
            @Value("${app.executor.ocr.queue-capacity:8}") int queueCapacity) {
        return executor("OCR-Frame-", coreSize, maxSize, queueCapacity);
    }

    private ThreadPoolTaskExecutor executor(
            String prefix, int coreSize, int maxSize, int queueCapacity) {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 0) {
            throw new IllegalArgumentException("Invalid executor configuration for " + prefix);
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
