package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

@Service
public class DefaultMediaProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMediaProvisioningService.class);

    private final MediaFileMapper mediaFileMapper;
    private final MediaService mediaService;
    private final MinioUtils minioUtils;
    private final boolean enabled;
    private final Path defaultFile;
    private final String displayFilename;
    private final String systemKey;
    private final String objectPrefix;
    private final Object templateLock = new Object();

    private volatile DefaultTemplate template;

    public DefaultMediaProvisioningService(
            MediaFileMapper mediaFileMapper,
            MediaService mediaService,
            MinioUtils minioUtils,
            @Value("${app.default-media.enabled:true}") boolean enabled,
            @Value("${app.default-media.file-path:default-media/experiment-one.mp4}")
            String defaultFilePath,
            @Value("${app.default-media.filename:实验一讲解视频.mp4}") String displayFilename,
            @Value("${app.default-media.system-key:experiment-one-tutorial}") String systemKey,
            @Value("${app.default-media.object-prefix:system-defaults}") String objectPrefix) {
        this.mediaFileMapper = mediaFileMapper;
        this.mediaService = mediaService;
        this.minioUtils = minioUtils;
        this.enabled = enabled;
        this.defaultFile = Path.of(defaultFilePath).toAbsolutePath().normalize();
        this.displayFilename = mediaService.normalizeVideoFilename(displayFilename);
        this.systemKey = normalizeSystemKey(systemKey);
        this.objectPrefix = normalizeObjectPrefix(objectPrefix);
    }

    public boolean enabled() {
        return enabled;
    }

    public void initializeTemplate() {
        if (enabled) {
            ensureTemplate();
        }
    }

    @Transactional
    public MediaFile provisionUser(Long userId) {
        if (!enabled) return null;
        if (userId == null) throw new IllegalArgumentException("默认视频缺少用户 ID");

        DefaultTemplate defaultTemplate = ensureTemplate();
        MediaFile existing = findExisting(userId);
        if (existing != null) {
            return synchronizeExisting(existing, defaultTemplate, userId);
        }

        MediaFile mediaFile = new MediaFile();
        mediaFile.setUserId(userId);
        mediaFile.setFilename(defaultTemplate.filename());
        mediaFile.setStatus("COMPLETED");
        mediaFile.setFilePath(defaultTemplate.fileUrl());
        mediaFile.setContentHash(defaultTemplate.contentHash());
        mediaFile.setSystemKey(systemKey);
        mediaFile.setUploadTime(LocalDateTime.now());
        try {
            mediaFileMapper.insert(mediaFile);
        } catch (DuplicateKeyException duplicate) {
            MediaFile concurrent = findExisting(userId);
            if (concurrent != null) {
                return synchronizeExisting(concurrent, defaultTemplate, userId);
            }
            throw duplicate;
        }

        mediaService.rememberContentHash(mediaFile.getId(), defaultTemplate.contentHash());
        mediaService.invalidateUserList(userId);
        log.info("default_media_provisioned userId={} mediaId={} systemKey={}",
                userId, mediaFile.getId(), systemKey);
        return mediaFile;
    }

    private MediaFile synchronizeExisting(
            MediaFile existing,
            DefaultTemplate defaultTemplate,
            Long userId) {
        boolean changed = !Objects.equals(existing.getFilename(), defaultTemplate.filename())
                || !Objects.equals(existing.getStatus(), "COMPLETED")
                || !Objects.equals(existing.getFilePath(), defaultTemplate.fileUrl())
                || !Objects.equals(existing.getContentHash(), defaultTemplate.contentHash());
        if (!changed) return existing;

        existing.setFilename(defaultTemplate.filename());
        existing.setStatus("COMPLETED");
        existing.setFilePath(defaultTemplate.fileUrl());
        existing.setContentHash(defaultTemplate.contentHash());
        mediaFileMapper.updateById(existing);
        mediaService.rememberContentHash(existing.getId(), defaultTemplate.contentHash());
        mediaService.invalidateUserList(userId);
        log.info("default_media_synchronized userId={} mediaId={} systemKey={}",
                userId, existing.getId(), systemKey);
        return existing;
    }

    private DefaultTemplate ensureTemplate() {
        DefaultTemplate current = template;
        if (current != null) return current;

        synchronized (templateLock) {
            current = template;
            if (current != null) return current;
            if (!Files.isRegularFile(defaultFile)) {
                throw new IllegalStateException("默认视频文件不存在：" + defaultFile);
            }

            try {
                File file = defaultFile.toFile();
                String contentHash = mediaService.calculateMd5(file);
                String suffix = fileSuffix(displayFilename);
                String objectName = objectPrefix + "/" + systemKey + "-" + contentHash + suffix;
                if (!minioUtils.objectExists(objectName)) {
                    try (InputStream input = Files.newInputStream(defaultFile)) {
                        minioUtils.uploadObject(
                                objectName,
                                input,
                                Files.size(defaultFile),
                                "video/mp4");
                    }
                    log.info("default_media_template_uploaded object={} size={}",
                            objectName, Files.size(defaultFile));
                }
                current = new DefaultTemplate(
                        displayFilename,
                        minioUtils.objectUrl(objectName),
                        contentHash);
                template = current;
                return current;
            } catch (Exception error) {
                throw new IllegalStateException("默认视频模板初始化失败", error);
            }
        }
    }

    private MediaFile findExisting(Long userId) {
        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("system_key", systemKey).last("LIMIT 1");
        return mediaFileMapper.selectOne(query);
    }

    private String normalizeSystemKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("默认视频 system-key 格式无效");
        }
        return normalized;
    }

    private String normalizeObjectPrefix(String value) {
        String normalized = value == null
                ? ""
                : value.trim().replaceAll("^/+|/+$", "");
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || normalized.contains("..")) {
            throw new IllegalArgumentException("默认视频对象前缀格式无效");
        }
        return normalized;
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? ".mp4" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private record DefaultTemplate(String filename, String fileUrl, String contentHash) {
    }
}
