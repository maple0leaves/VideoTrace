package com.example.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthRequest(
        String username,
        String password,
        String nickname,
        @JsonProperty("captcha_id") String captchaId,
        @JsonProperty("captcha_code") String captchaCode) {
}
