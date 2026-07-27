package com.example.server.controller;

import com.example.server.dto.CaptchaResponse;
import com.example.server.service.CaptchaService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final CaptchaService captchaService;

    public AuthController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    @GetMapping("/captcha")
    public ResponseEntity<CaptchaResponse> captcha() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(captchaService.createCaptcha());
    }
}
