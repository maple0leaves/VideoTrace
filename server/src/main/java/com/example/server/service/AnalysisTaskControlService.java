package com.example.server.service;

import com.example.server.utils.AnalysisTaskKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

@Service
public class AnalysisTaskControlService {

    private static final Duration TERMINAL_MARKER_TTL = Duration.ofDays(1);
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] "
                            + "then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
    private static final DefaultRedisScript<String> REQUEST_CANCELLATION =
            new DefaultRedisScript<>(
                    "local runId = redis.call('get', KEYS[1]); "
                            + "if not runId then return '' end; "
                            + "if redis.call('exists', ARGV[1] .. runId) == 1 then return '' end; "
                            + "redis.call('set', ARGV[2] .. runId, ARGV[3], 'PX', ARGV[4]); "
                            + "return runId",
                    String.class);
    private static final DefaultRedisScript<Long> BEGIN_COMMIT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                            + "if redis.call('exists', ARGV[2] .. ARGV[1]) == 1 then return 0 end; "
                            + "redis.call('set', ARGV[3] .. ARGV[1], '1', 'PX', ARGV[4]); "
                            + "return 1",
                    Long.class);
    private static final String CONTROL_PREFIX = "analysis:control:";
    private static final String COMMIT_PREFIX = "analysis:commit:";

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public AnalysisTaskControlService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void registerCurrent(Long mediaId, String goal, String runId, Duration ttl) {
        redisTemplate.opsForValue().set(currentKey(mediaId, goal), runId, ttl);
    }

    public Optional<String> currentRunId(Long mediaId, String goal) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(currentKey(mediaId, goal)));
    }

    public boolean isCurrent(Long mediaId, String goal, String runId) {
        return runId != null && runId.equals(
                redisTemplate.opsForValue().get(currentKey(mediaId, goal)));
    }

    public Optional<StopReason> requestCancellation(Long mediaId, String goal) {
        String runId = redisTemplate.execute(
                REQUEST_CANCELLATION,
                List.of(currentKey(mediaId, goal)),
                COMMIT_PREFIX,
                CONTROL_PREFIX,
                StopReason.CANCELLED.name(),
                String.valueOf(TERMINAL_MARKER_TTL.toMillis()));
        if (runId == null || runId.isBlank()) return Optional.empty();
        Future<?> future = runningTasks.get(runId);
        if (future != null) future.cancel(true);
        return Optional.of(StopReason.CANCELLED);
    }

    public StopReason requestTimeout(String runId) {
        return requestStop(runId, StopReason.TIMED_OUT);
    }

    public Optional<StopReason> stopReason(String runId) {
        String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.control(runId));
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(StopReason.valueOf(value));
        } catch (IllegalArgumentException e) {
            redisTemplate.delete(AnalysisTaskKeys.control(runId));
            return Optional.empty();
        }
    }

    public void register(String runId, Future<?> future) {
        Future<?> existing = runningTasks.putIfAbsent(runId, future);
        if (existing != null && existing != future) {
            future.cancel(true);
            throw new IllegalStateException("analysis task is already running: " + runId);
        }
        stopReason(runId).ifPresent(reason -> future.cancel(true));
    }

    public void unregister(String runId, Future<?> future) {
        runningTasks.remove(runId, future);
    }

    public void abortIfRequested(String runId) {
        if (Thread.currentThread().isInterrupted()) {
            StopReason reason = stopReason(runId).orElse(StopReason.CANCELLED);
            throw new TaskAbortedException(reason);
        }
        stopReason(runId).ifPresent(reason -> {
            throw new TaskAbortedException(reason);
        });
    }

    public void clearCurrent(Long mediaId, String goal, String runId) {
        compareAndDelete(currentKey(mediaId, goal), runId);
    }

    public boolean beginCommit(Long mediaId, String goal, String runId) {
        Long result = redisTemplate.execute(
                BEGIN_COMMIT,
                List.of(currentKey(mediaId, goal)),
                runId,
                CONTROL_PREFIX,
                COMMIT_PREFIX,
                String.valueOf(TERMINAL_MARKER_TTL.toMillis()));
        return result != null && result == 1L;
    }

    public void clearCommit(String runId) {
        redisTemplate.delete(COMMIT_PREFIX + runId);
    }

    public void compareAndDelete(String key, String expectedValue) {
        redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), expectedValue);
    }

    private StopReason requestStop(String runId, StopReason reason) {
        redisTemplate.opsForValue().set(
                AnalysisTaskKeys.control(runId), reason.name(), TERMINAL_MARKER_TTL);
        Future<?> future = runningTasks.get(runId);
        if (future != null) future.cancel(true);
        return reason;
    }

    private String currentKey(Long mediaId, String goal) {
        return AnalysisTaskKeys.current(mediaId, AnalysisTaskKeys.goalDigest(goal));
    }

    public enum StopReason {
        CANCELLED,
        TIMED_OUT
    }

    public static class TaskAbortedException extends IllegalStateException {
        private final StopReason reason;

        public TaskAbortedException(StopReason reason) {
            super(reason == StopReason.TIMED_OUT
                    ? "analysis task exceeded its total deadline"
                    : "analysis task was cancelled");
            this.reason = reason;
        }

        public StopReason reason() {
            return reason;
        }
    }
}
