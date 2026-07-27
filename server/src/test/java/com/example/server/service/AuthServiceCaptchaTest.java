package com.example.server.service;

import com.example.server.dto.AuthRequest;
import com.example.server.dto.AuthResponse;
import com.example.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceCaptchaTest {

    @Test
    void registerStopsBeforeDatabaseWhenCaptchaIsInvalid() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        CaptchaService captchaService = mock(CaptchaService.class);
        AuthService service = new AuthService(redisTemplate, userMapper, captchaService);
        AuthRequest request = new AuthRequest(
                "new_user", "password123", "新用户", "captcha-id", "ABCD");
        when(captchaService.verifyAndConsume("captcha-id", "ABCD")).thenReturn(false);

        AuthResponse response = service.register(request);

        assertThat(response.code()).isEqualTo(400);
        assertThat(response.msg()).contains("验证码错误或已过期");
        verify(captchaService).verifyAndConsume("captcha-id", "ABCD");
        verifyNoInteractions(userMapper);
    }
}
