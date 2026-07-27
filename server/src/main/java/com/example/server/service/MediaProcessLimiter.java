package com.example.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
public class MediaProcessLimiter {

    private final Semaphore permits;

    public MediaProcessLimiter(
            @Value("${video.ffmpeg.max-concurrent:2}") int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("FFmpeg concurrency must be positive");
        }
        this.permits = new Semaphore(maxConcurrent, true);
    }

    public void acquire() throws InterruptedException {
        permits.acquire();
    }

    public void release() {
        permits.release();
    }
}
