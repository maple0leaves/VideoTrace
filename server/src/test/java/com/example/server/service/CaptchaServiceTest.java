package com.example.server.service;

import com.example.server.dto.CaptchaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptchaServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private CaptchaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        service = new CaptchaService(redisTemplate, 180);
    }

    @Test
    void createCaptchaStoresAnswerWithTtlAndReturnsRasterizedSvg() {
        CaptchaResponse response = service.createCaptcha();

        UUID.fromString(response.captchaId());
        assertThat(response.expiresInSeconds()).isEqualTo(180);
        assertThat(response.image()).startsWith("data:image/svg+xml;base64,");

        String svg = new String(
                Base64.getDecoder().decode(
                        response.image().substring("data:image/svg+xml;base64,".length())),
                StandardCharsets.UTF_8);
        assertThat(svg)
                .contains("<svg")
                .contains("data:image/png;base64,")
                .doesNotContain("<text");

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(values).set(
                eq("auth:captcha:" + response.captchaId()),
                answer.capture(),
                eq(Duration.ofSeconds(180)));
        assertThat(answer.getValue()).matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}");
    }

    @Test
    void verifyConsumesCaptchaAndIgnoresCaseAndWhitespace() {
        when(values.getAndDelete("auth:captcha:captcha-id")).thenReturn("A2CD");

        assertThat(service.verifyAndConsume(" captcha-id ", " a2cd ")).isTrue();
        verify(values).getAndDelete("auth:captcha:captcha-id");
    }

    @Test
    void verifyRejectsExpiredOrIncorrectCaptcha() {
        when(values.getAndDelete("auth:captcha:expired")).thenReturn(null);
        when(values.getAndDelete("auth:captcha:wrong")).thenReturn("ABCD");

        assertThat(service.verifyAndConsume("expired", "ABCD")).isFalse();
        assertThat(service.verifyAndConsume("wrong", "WXYZ")).isFalse();
        assertThat(service.verifyAndConsume(null, null)).isFalse();
    }
}
