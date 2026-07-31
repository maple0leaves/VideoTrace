package com.example.server.service;

import com.example.server.dto.AuthRequest;
import com.example.server.dto.AuthResponse;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceCaptchaTest {

    @Test
    void successfulRegistrationProvisionsDefaultMedia() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        CaptchaService captchaService = mock(CaptchaService.class);
        DefaultMediaProvisioningService defaultMediaService = mock(
                DefaultMediaProvisioningService.class);
        AuthService service = new AuthService(
                redisTemplate, userMapper, captchaService, defaultMediaService);
        AuthRequest request = new AuthRequest(
                "new_user", "password123", "新用户", "captcha-id", "ABCD");
        when(captchaService.verifyAndConsume("captcha-id", "ABCD")).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            User inserted = invocation.getArgument(0);
            inserted.setId(123L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        AuthResponse response = service.register(request);

        assertThat(response.code()).isEqualTo(200);
        verify(defaultMediaService).provisionUser(123L);
    }

    @Test
    void registerStopsBeforeDatabaseWhenCaptchaIsInvalid() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        CaptchaService captchaService = mock(CaptchaService.class);
        DefaultMediaProvisioningService defaultMediaService = mock(DefaultMediaProvisioningService.class);
        AuthService service = new AuthService(
                redisTemplate, userMapper, captchaService, defaultMediaService);
        AuthRequest request = new AuthRequest(
                "new_user", "password123", "新用户", "captcha-id", "ABCD");
        when(captchaService.verifyAndConsume("captcha-id", "ABCD")).thenReturn(false);

        AuthResponse response = service.register(request);

        assertThat(response.code()).isEqualTo(400);
        assertThat(response.msg()).contains("验证码错误或已过期");
        verify(captchaService).verifyAndConsume("captcha-id", "ABCD");
        verifyNoInteractions(userMapper);
        verifyNoInteractions(defaultMediaService);
    }
}
