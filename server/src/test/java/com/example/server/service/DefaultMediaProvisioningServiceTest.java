package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMediaProvisioningServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void provisionsOneIndependentRecordBackedBySharedObject() throws Exception {
        Path video = tempDir.resolve("experiment-one.mp4");
        Files.write(video, new byte[]{1, 2, 3, 4});
        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        MediaService mediaService = mock(MediaService.class);
        MinioUtils minioUtils = mock(MinioUtils.class);
        String objectName = "system-defaults/experiment-one-tutorial-abc123.mp4";
        String objectUrl = "http://minio:9000/videos/" + objectName;

        when(mediaService.normalizeVideoFilename("实验一讲解视频.mp4"))
                .thenReturn("实验一讲解视频.mp4");
        when(mediaService.calculateMd5(video.toFile())).thenReturn("abc123");
        when(minioUtils.objectExists(objectName)).thenReturn(false);
        when(minioUtils.objectUrl(objectName)).thenReturn(objectUrl);
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            MediaFile inserted = invocation.getArgument(0);
            inserted.setId(99L);
            return 1;
        }).when(mediaFileMapper).insert(any(MediaFile.class));

        DefaultMediaProvisioningService service = new DefaultMediaProvisioningService(
                mediaFileMapper,
                mediaService,
                minioUtils,
                true,
                video.toString(),
                "实验一讲解视频.mp4",
                "experiment-one-tutorial",
                "system-defaults");

        MediaFile provisioned = service.provisionUser(42L);

        assertThat(provisioned.getId()).isEqualTo(99L);
        assertThat(provisioned.getUserId()).isEqualTo(42L);
        assertThat(provisioned.getFilename()).isEqualTo("实验一讲解视频.mp4");
        assertThat(provisioned.getStatus()).isEqualTo("COMPLETED");
        assertThat(provisioned.getFilePath()).isEqualTo(objectUrl);
        assertThat(provisioned.getContentHash()).isEqualTo("abc123");
        assertThat(provisioned.getSystemKey()).isEqualTo("experiment-one-tutorial");
        assertThat(provisioned.getUploadTime()).isNotNull();
        verify(minioUtils).uploadObject(
                eq(objectName), any(InputStream.class), eq(4L), eq("video/mp4"));
        verify(mediaService).rememberContentHash(99L, "abc123");
        verify(mediaService).invalidateUserList(42L);
    }

    @Test
    void existingUserRecordMakesProvisioningIdempotent() throws Exception {
        Path video = tempDir.resolve("experiment-one.mp4");
        Files.write(video, new byte[]{1});
        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        MediaService mediaService = mock(MediaService.class);
        MinioUtils minioUtils = mock(MinioUtils.class);
        MediaFile existing = new MediaFile();
        existing.setId(7L);
        existing.setUserId(42L);
        existing.setFilename("实验一讲解视频.mp4");
        existing.setStatus("COMPLETED");
        existing.setFilePath("shared-url");
        existing.setContentHash("abc123");
        existing.setSystemKey("experiment-one-tutorial");

        when(mediaService.normalizeVideoFilename("实验一讲解视频.mp4"))
                .thenReturn("实验一讲解视频.mp4");
        when(mediaService.calculateMd5(video.toFile())).thenReturn("abc123");
        when(minioUtils.objectExists(
                "system-defaults/experiment-one-tutorial-abc123.mp4")).thenReturn(true);
        when(minioUtils.objectUrl(
                "system-defaults/experiment-one-tutorial-abc123.mp4")).thenReturn("shared-url");
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        DefaultMediaProvisioningService service = new DefaultMediaProvisioningService(
                mediaFileMapper,
                mediaService,
                minioUtils,
                true,
                video.toString(),
                "实验一讲解视频.mp4",
                "experiment-one-tutorial",
                "system-defaults");

        assertThat(service.provisionUser(42L)).isSameAs(existing);
        verify(mediaFileMapper, never()).insert(any(MediaFile.class));
        verify(mediaFileMapper, never()).updateById(any(MediaFile.class));
        verify(minioUtils, never()).uploadObject(
                any(String.class), any(InputStream.class), anyLong(), any(String.class));
        verify(mediaService, never()).rememberContentHash(anyLong(), any(String.class));
        verify(mediaService, never()).invalidateUserList(anyLong());
    }

    @Test
    void existingRecordIsSynchronizedWhenTemplateMetadataChanges() throws Exception {
        Path video = tempDir.resolve("experiment-one.mp4");
        Files.write(video, new byte[]{1});
        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        MediaService mediaService = mock(MediaService.class);
        MinioUtils minioUtils = mock(MinioUtils.class);
        MediaFile existing = new MediaFile();
        existing.setId(7L);
        existing.setUserId(42L);
        existing.setFilename("old-name.mp4");
        existing.setStatus("COMPLETED");
        existing.setFilePath("shared-url");
        existing.setContentHash("abc123");
        existing.setSystemKey("experiment-one-tutorial");

        when(mediaService.normalizeVideoFilename("实验一讲解视频.mp4"))
                .thenReturn("实验一讲解视频.mp4");
        when(mediaService.calculateMd5(video.toFile())).thenReturn("abc123");
        when(minioUtils.objectExists(
                "system-defaults/experiment-one-tutorial-abc123.mp4")).thenReturn(true);
        when(minioUtils.objectUrl(
                "system-defaults/experiment-one-tutorial-abc123.mp4")).thenReturn("shared-url");
        when(mediaFileMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        DefaultMediaProvisioningService service = new DefaultMediaProvisioningService(
                mediaFileMapper,
                mediaService,
                minioUtils,
                true,
                video.toString(),
                "实验一讲解视频.mp4",
                "experiment-one-tutorial",
                "system-defaults");

        MediaFile synchronizedRecord = service.provisionUser(42L);

        assertThat(synchronizedRecord.getFilename()).isEqualTo("实验一讲解视频.mp4");
        verify(mediaFileMapper).updateById(existing);
        verify(mediaService).rememberContentHash(7L, "abc123");
        verify(mediaService).invalidateUserList(42L);
    }

    @Test
    void disabledFeatureDoesNotTouchDatabaseOrObjectStorage() {
        MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
        MediaService mediaService = mock(MediaService.class);
        MinioUtils minioUtils = mock(MinioUtils.class);
        when(mediaService.normalizeVideoFilename("实验一讲解视频.mp4"))
                .thenReturn("实验一讲解视频.mp4");

        DefaultMediaProvisioningService service = new DefaultMediaProvisioningService(
                mediaFileMapper,
                mediaService,
                minioUtils,
                false,
                tempDir.resolve("missing.mp4").toString(),
                "实验一讲解视频.mp4",
                "experiment-one-tutorial",
                "system-defaults");

        assertThat(service.provisionUser(42L)).isNull();
        verify(mediaFileMapper, never()).selectOne(any(QueryWrapper.class));
        verify(mediaFileMapper, never()).insert(any(MediaFile.class));
        verify(minioUtils, never()).objectExists(any(String.class));
    }
}
