package com.example.server.service;

import com.example.server.utils.AnalysisTaskKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

class AnalysisTaskControlServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private AnalysisTaskControlService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        service = new AnalysisTaskControlService(redisTemplate);
    }

    @Test
    void cancellationPersistsTombstoneAndInterruptsRegisteredFuture() {
        Long mediaId = 42L;
        String goal = "summarize";
        String runId = "run-42";
        Future<?> future = mock(Future.class);
        when(values.get(AnalysisTaskKeys.control(runId))).thenReturn(null);
        doReturn(runId).when(redisTemplate).execute(
                any(), anyList(), any(), any(), any(), any());
        service.register(runId, future);

        assertThat(service.requestCancellation(mediaId, goal))
                .contains(AnalysisTaskControlService.StopReason.CANCELLED);
        verify(future).cancel(true);
    }

    @Test
    void timeoutTombstoneStopsLateCheckpointWork() {
        String runId = "timed-out-run";
        when(values.get(AnalysisTaskKeys.control(runId))).thenReturn("TIMED_OUT");

        assertThatThrownBy(() -> service.abortIfRequested(runId))
                .isInstanceOf(AnalysisTaskControlService.TaskAbortedException.class)
                .satisfies(error -> assertThat(
                        ((AnalysisTaskControlService.TaskAbortedException) error).reason())
                        .isEqualTo(AnalysisTaskControlService.StopReason.TIMED_OUT));
    }

    @Test
    void currentRunFenceRejectsAnOlderRun() {
        Long mediaId = 7L;
        String goal = "notes";
        String currentKey = AnalysisTaskKeys.current(
                mediaId, AnalysisTaskKeys.goalDigest(goal));
        when(values.get(currentKey)).thenReturn("new-run");

        assertThat(service.isCurrent(mediaId, goal, "old-run")).isFalse();
        assertThat(service.isCurrent(mediaId, goal, "new-run")).isTrue();
    }

    @Test
    void beginCommitUsesAtomicFence() {
        doReturn(1L).when(redisTemplate).execute(
                any(), anyList(), any(), any(), any(), any());

        assertThat(service.beginCommit(9L, "report", "run-9")).isTrue();
    }
}
