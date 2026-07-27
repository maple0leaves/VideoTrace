package com.example.server.consumer;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentLoopService;
import com.example.server.service.AiService;
import com.example.server.service.AnalysisTaskControlService;
import com.example.server.service.FailedAnalysisTaskService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-analysis:video-analysis-topic}",
        consumerGroup = "${rocketmq.consumer.group:video-analysis-group}",
        consumeThreadNumber = 2,
        consumeThreadMax = 2)
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);
    private static final int MAX_DELIVERY_ATTEMPTS = 3;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final AiService aiService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final AgentCheckpointService checkpointService;
    private final RocketMQTemplate rocketMQTemplate;
    private final FailedAnalysisTaskService failedTaskService;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final AnalysisTaskControlService taskControlService;
    private final AsyncTaskExecutor analysisTaskExecutor;
    private final String deadLetterTopic;

    public VideoAnalysisConsumer(
            AiService aiService,
            RedissonClient redissonClient,
            StringRedisTemplate redisTemplate,
            AgentCheckpointService checkpointService,
            RocketMQTemplate rocketMQTemplate,
            FailedAnalysisTaskService failedTaskService,
            MediaService mediaService,
            TaskEventService taskEventService,
            AnalysisTaskControlService taskControlService,
            @Qualifier("analysisTaskExecutor") AsyncTaskExecutor analysisTaskExecutor,
            @Value("${rocketmq.topic.video-analysis-dead:video-analysis-dead-topic}")
            String deadLetterTopic) {
        this.aiService = aiService;
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.checkpointService = checkpointService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.failedTaskService = failedTaskService;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.taskControlService = taskControlService;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        if (!isValid(msg)) {
            log.warn("invalid_video_analysis_message_discarded");
            return;
        }

        Long mediaId = msg.getMediaId();
        String goal = msg.getUserGoal();
        String runId = msg.getRunId();
        String contentHash = AnalysisTaskKeys.normalizeContentHash(mediaId, msg.getContentHash());
        String goalDigest = AnalysisTaskKeys.goalDigest(goal);
        String lockKey = AnalysisTaskKeys.lock(contentHash, goalDigest);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        String completedKey = AnalysisTaskKeys.completed(contentHash, goalDigest);
        String attemptsKey = AnalysisTaskKeys.attempts(runId);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        boolean retrying = false;
        long attempt = 0;

        try {
            acquired = lock.tryLock();
            if (!acquired) {
                log.info("video_analysis_lock_busy mediaId={} runId={}", mediaId, runId);
                throw new IllegalStateException("analysis lock is busy");
            }
            if (!runId.equals(redisTemplate.opsForValue().get(activeKey))
                    || !taskControlService.isCurrent(mediaId, goal, runId)) {
                log.info("stale_video_analysis_message_acked mediaId={} runId={}", mediaId, runId);
                return;
            }
            if (!mediaService.exists(mediaId)) {
                log.info("video_analysis_discarded_deleted_media mediaId={} runId={}", mediaId, runId);
                return;
            }
            if (taskControlService.stopReason(runId).isPresent()) {
                completeStoppedTask(mediaId, goal, runId, msg.isRevision());
                return;
            }
            if (remainingMillis(msg) <= 0) {
                taskControlService.requestTimeout(runId);
                completeStoppedTask(mediaId, goal, runId, msg.isRevision());
                return;
            }

            Long currentAttempt = redisTemplate.opsForValue().increment(attemptsKey);
            attempt = currentAttempt == null ? 1 : currentAttempt;
            redisTemplate.expire(attemptsKey, ACTIVE_TTL);
            publish(mediaId, goal, TaskStatus.State.PROCESSING,
                    "视频分析任务开始执行", TaskStage.CONSUMING);

            if (msg.isRevision()) {
                checkpointService.beginStagedRevision(mediaId, goal);
                redisTemplate.delete(completedKey);
            } else if (reuseCompletedResult(mediaId, goal, completedKey)) {
                return;
            }

            saveStage(mediaId, goal, TaskStage.CONSUMING);
            runWithDeadline(msg);
            taskControlService.abortIfRequested(runId);
            if (!runId.equals(redisTemplate.opsForValue().get(activeKey))
                    || !taskControlService.isCurrent(mediaId, goal, runId)) {
                throw new AnalysisTaskControlService.TaskAbortedException(
                        AnalysisTaskControlService.StopReason.CANCELLED);
            }
            if (!mediaService.exists(mediaId)) {
                mediaService.purgeRuntimeArtifacts(mediaId);
                log.info("video_analysis_cleanup_after_media_deleted mediaId={}", mediaId);
                return;
            }
            if (!taskControlService.beginCommit(mediaId, goal, runId)) {
                throw new AnalysisTaskControlService.TaskAbortedException(
                        taskControlService.stopReason(runId)
                                .orElse(AnalysisTaskControlService.StopReason.CANCELLED));
            }
            AgentState completed = aiService.persistCheckpointResult(mediaId, goal);

            redisTemplate.opsForValue().set(
                    completedKey, String.valueOf(mediaId), Duration.ofDays(7));
            if (msg.isRevision()) checkpointService.completeStagedRevision(mediaId, goal);
            if (completed != null && completed.result() != null) {
                taskEventService.publishAnalysis(
                        mediaId,
                        goal,
                        TaskStatus.completed(completed.result().toMarkdown()),
                        TaskStage.COMPLETED);
            }
        } catch (AnalysisTaskControlService.TaskAbortedException e) {
            completeStoppedTask(mediaId, goal, runId, msg.isRevision());
            log.info("video_analysis_stopped mediaId={} runId={} reason={}",
                    mediaId, runId, e.reason());
            return;
        } catch (AgentLoopService.BudgetExceededException e) {
            saveStage(mediaId, goal, TaskStage.BUDGET_EXHAUSTED);
            publish(mediaId, goal, TaskStatus.State.FAILED,
                    e.getMessage(), TaskStage.BUDGET_EXHAUSTED);
            log.warn("video_analysis_budget_exhausted mediaId={} reason={}", mediaId, e.getMessage());
            return;
        } catch (Exception e) {
            if (taskControlService.stopReason(runId).isPresent()) {
                completeStoppedTask(mediaId, goal, runId, msg.isRevision());
                return;
            }
            if (acquired && attempt > 0 && attempt < MAX_DELIVERY_ATTEMPTS) {
                retrying = true;
                redisTemplate.expire(activeKey, ACTIVE_TTL);
                saveStage(mediaId, goal, TaskStage.RETRYING);
                publish(mediaId, goal, TaskStatus.State.PROCESSING,
                        "本次执行失败，等待消息队列自动重试", TaskStage.RETRYING);
                log.warn("video_analysis_retry_scheduled mediaId={} runId={} attempt={}",
                        mediaId, runId, attempt, e);
                throw new IllegalStateException("视频分析消费失败，交由 RocketMQ 重试", e);
            }
            if (acquired && attempt >= MAX_DELIVERY_ATTEMPTS) {
                try {
                    try {
                        failedTaskService.record(msg, attempt, e);
                    } catch (RuntimeException recordError) {
                        e.addSuppressed(recordError);
                        log.error("failed_analysis_record_write_failed mediaId={}", mediaId, recordError);
                    }
                    rocketMQTemplate.convertAndSend(deadLetterTopic, msg);
                    saveStage(mediaId, goal, TaskStage.DEAD_LETTERED);
                    publish(mediaId, goal, TaskStatus.State.FAILED,
                            "分析失败，已进入人工处理队列", TaskStage.DEAD_LETTERED);
                    log.error("video_analysis_dead_lettered mediaId={} attempts={}", mediaId, attempt, e);
                    return;
                } catch (RuntimeException deadLetterError) {
                    retrying = true;
                    deadLetterError.addSuppressed(e);
                    throw deadLetterError;
                }
            }
            throw new IllegalStateException("视频分析消费失败", e);
        } finally {
            taskControlService.clearCommit(runId);
            if (acquired) {
                if (!retrying) {
                    taskControlService.compareAndDelete(activeKey, runId);
                    taskControlService.clearCurrent(mediaId, goal, runId);
                    redisTemplate.delete(attemptsKey);
                }
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private void runWithDeadline(AnalysisTaskMsg msg) throws Exception {
        String runId = msg.getRunId();
        Future<?> future = analysisTaskExecutor.submit(
                () -> aiService.asyncAnalyze(msg.getMediaId(), msg.getUserGoal(), runId));
        taskControlService.register(runId, future);
        try {
            long remainingMs = remainingMillis(msg);
            if (remainingMs <= 0) throw new TimeoutException("analysis deadline expired");
            future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            taskControlService.requestTimeout(runId);
            future.cancel(true);
            throw new AnalysisTaskControlService.TaskAbortedException(
                    AnalysisTaskControlService.StopReason.TIMED_OUT);
        } catch (CancellationException e) {
            throw new AnalysisTaskControlService.TaskAbortedException(
                    taskControlService.stopReason(runId)
                            .orElse(AnalysisTaskControlService.StopReason.CANCELLED));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AnalysisTaskControlService.TaskAbortedException aborted) {
                throw aborted;
            }
            if (cause instanceof AgentLoopService.BudgetExceededException budgetExceeded) {
                throw budgetExceeded;
            }
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("analysis worker failed", cause);
        } finally {
            taskControlService.unregister(runId, future);
        }
    }

    private boolean reuseCompletedResult(Long mediaId, String goal, String completedKey) {
        String completedMediaId = redisTemplate.opsForValue().get(completedKey);
        if (completedMediaId == null) return false;
        Long sourceMediaId = parseMediaId(completedMediaId, completedKey);
        AgentState reusable = sourceMediaId == null ? null
                : checkpointService.loadResult(sourceMediaId, goal);
        if (reusable != null && reusable.result() != null
                && aiService.reuseResult(mediaId, sourceMediaId, reusable)) {
            taskEventService.publishAnalysis(
                    mediaId,
                    goal,
                    TaskStatus.completed(reusable.result().toMarkdown()),
                    TaskStage.COMPLETED_REUSED);
            log.info("video_analysis_reused mediaId={} sourceMediaId={}", mediaId, sourceMediaId);
            return true;
        }
        redisTemplate.delete(completedKey);
        return false;
    }

    private void completeStoppedTask(
            Long mediaId, String goal, String runId, boolean revision) {
        AnalysisTaskControlService.StopReason reason = taskControlService.stopReason(runId)
                .orElse(AnalysisTaskControlService.StopReason.CANCELLED);
        if (revision) {
            checkpointService.restoreStagedRevision(mediaId, goal);
        } else {
            checkpointService.clearGoalProgress(mediaId, goal);
        }
        if (reason == AnalysisTaskControlService.StopReason.TIMED_OUT) {
            saveStage(mediaId, goal, TaskStage.TIMED_OUT);
            publish(mediaId, goal, TaskStatus.State.FAILED,
                    "分析超过总时限，已自动终止；可稍后重试",
                    TaskStage.TIMED_OUT);
        } else {
            saveStage(mediaId, goal, TaskStage.CANCELLED);
            publish(mediaId, goal, TaskStatus.State.CANCELLED,
                    "分析任务已取消", TaskStage.CANCELLED);
        }
    }

    private long remainingMillis(AnalysisTaskMsg msg) {
        long deadline = msg.getDeadlineAtEpochMs();
        return deadline <= 0 ? 0 : deadline - System.currentTimeMillis();
    }

    private boolean isValid(AnalysisTaskMsg msg) {
        return msg != null
                && msg.getMediaId() != null
                && msg.getUserGoal() != null
                && !msg.getUserGoal().isBlank()
                && msg.hasSupportedAction()
                && msg.getRunId() != null
                && !msg.getRunId().isBlank()
                && msg.getDeadlineAtEpochMs() > 0;
    }

    private Long parseMediaId(String value, String completedKey) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            redisTemplate.delete(completedKey);
            log.warn("invalid_completed_media_reference key={} value={}", completedKey, value);
            return null;
        }
    }

    private void publish(
            Long mediaId,
            String goal,
            TaskStatus.State state,
            String message,
            TaskStage stage) {
        taskEventService.publishAnalysis(mediaId, goal, TaskStatus.of(state, message), stage);
    }

    private void saveStage(Long mediaId, String goal, TaskStage stage) {
        try {
            checkpointService.saveStage(mediaId, goal, stage);
        } catch (RuntimeException e) {
            log.warn("analysis_stage_checkpoint_failed mediaId={} stage={}", mediaId, stage, e);
        }
    }
}
