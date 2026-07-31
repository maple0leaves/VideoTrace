package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisDispatchServiceTest {

    private static final String VIDEO_HASH = "0123456789abcdef0123456789abcdef";
    private static final String GOAL = "生成结构化分析报告";

    @Test
    void defaultVideoUsesASeparateTaskScopeForEachUser() {
        AiService aiService = mock(AiService.class);
        MediaService mediaService = mock(MediaService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter userOneLimiter = mock(RRateLimiter.class);
        RRateLimiter userTwoLimiter = mock(RRateLimiter.class);
        RRateLimiter globalLimiter = mock(RRateLimiter.class);
        TaskEventService taskEventService = mock(TaskEventService.class);
        AnalysisTaskControlService taskControlService = mock(AnalysisTaskControlService.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redissonClient.getRateLimiter("limit:ai:user:1")).thenReturn(userOneLimiter);
        when(redissonClient.getRateLimiter("limit:ai:user:2")).thenReturn(userTwoLimiter);
        when(redissonClient.getRateLimiter("limit:ai:global")).thenReturn(globalLimiter);
        when(userOneLimiter.tryAcquire()).thenReturn(true);
        when(userTwoLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        MediaFile firstUserMedia = defaultMedia(101L, 1L);
        MediaFile secondUserMedia = defaultMedia(202L, 2L);
        when(mediaService.analysisTaskScope(firstUserMedia)).thenReturn("media-101");
        when(mediaService.analysisTaskScope(secondUserMedia)).thenReturn("media-202");

        AnalysisDispatchService service = new AnalysisDispatchService(
                aiService, mediaService, redisTemplate, rocketMQTemplate, redissonClient,
                taskEventService, taskControlService, "video-analysis-topic", 480_000L);

        assertThat(service.submit(firstUserMedia, GOAL, null))
                .isEqualTo(AnalysisDispatchService.SubmissionResult.ACCEPTED);
        assertThat(service.submit(secondUserMedia, GOAL, null))
                .isEqualTo(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        ArgumentCaptor<AnalysisTaskMsg> messageCaptor =
                ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(rocketMQTemplate, times(2)).convertAndSend(
                eq("video-analysis-topic"), messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .extracting(AnalysisTaskMsg::getContentHash)
                .containsExactly("media-101", "media-202");
    }

    private MediaFile defaultMedia(Long mediaId, Long userId) {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(mediaId);
        mediaFile.setUserId(userId);
        mediaFile.setContentHash(VIDEO_HASH);
        mediaFile.setSystemKey("experiment-one-tutorial");
        return mediaFile;
    }
}
