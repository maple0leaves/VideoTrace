package com.example.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CaptchaResponse(
        @JsonProperty("captcha_id") String captchaId,
        String image,
        @JsonProperty("expires_in_seconds") int expiresInSeconds) {
}
