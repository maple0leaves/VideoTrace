package com.example.server.service;

import com.example.server.dto.CaptchaResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class CaptchaService {

    private static final String CAPTCHA_PREFIX = "auth:captcha:";
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CAPTCHA_LENGTH = 4;
    private static final int IMAGE_WIDTH = 160;
    private static final int IMAGE_HEIGHT = 52;

    private final StringRedisTemplate redisTemplate;
    private final int expiresInSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public CaptchaService(
            StringRedisTemplate redisTemplate,
            @Value("${auth.captcha.expires-seconds:180}") int expiresInSeconds) {
        this.redisTemplate = redisTemplate;
        this.expiresInSeconds = Math.max(1, expiresInSeconds);
    }

    public CaptchaResponse createCaptcha() {
        String captchaId = UUID.randomUUID().toString();
        String answer = randomAnswer();
        redisTemplate.opsForValue().set(
                key(captchaId), answer, Duration.ofSeconds(expiresInSeconds));
        return new CaptchaResponse(captchaId, createSvgDataUri(answer), expiresInSeconds);
    }

    public boolean verifyAndConsume(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank()
                || captchaCode == null || captchaCode.isBlank()) {
            return false;
        }

        String expected = redisTemplate.opsForValue().getAndDelete(key(captchaId.trim()));
        if (expected == null) {
            return false;
        }
        return expected.equals(captchaCode.trim().toUpperCase(Locale.ROOT));
    }

    private String randomAnswer() {
        StringBuilder answer = new StringBuilder(CAPTCHA_LENGTH);
        for (int i = 0; i < CAPTCHA_LENGTH; i++) {
            answer.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return answer.toString();
    }

    private String createSvgDataUri(String answer) {
        BufferedImage image = new BufferedImage(
                IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(241, 247, 252));
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            for (int i = 0; i < 7; i++) {
                graphics.setColor(randomColor(135, 205));
                graphics.drawLine(
                        secureRandom.nextInt(IMAGE_WIDTH),
                        secureRandom.nextInt(IMAGE_HEIGHT),
                        secureRandom.nextInt(IMAGE_WIDTH),
                        secureRandom.nextInt(IMAGE_HEIGHT));
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            for (int i = 0; i < answer.length(); i++) {
                int x = 17 + i * 34;
                int y = 37 + secureRandom.nextInt(-3, 4);
                AffineTransform original = graphics.getTransform();
                graphics.rotate(
                        Math.toRadians(secureRandom.nextInt(-12, 13)),
                        x + 10,
                        y - 12);
                graphics.setColor(randomColor(25, 105));
                graphics.drawString(String.valueOf(answer.charAt(i)), x, y);
                graphics.setTransform(original);
            }
        } finally {
            graphics.dispose();
        }

        String pngBase64;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("当前运行环境无法生成验证码图片");
            }
            pngBase64 = Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException("生成验证码图片失败", error);
        }

        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
                  <image width="%d" height="%d" href="data:image/png;base64,%s"/>
                </svg>
                """.formatted(
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
                pngBase64);
        return "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    private Color randomColor(int min, int max) {
        int range = max - min + 1;
        return new Color(
                min + secureRandom.nextInt(range),
                min + secureRandom.nextInt(range),
                min + secureRandom.nextInt(range));
    }

    private String key(String captchaId) {
        return CAPTCHA_PREFIX + captchaId;
    }
}
