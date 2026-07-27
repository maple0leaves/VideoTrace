package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.utils.AliyunAsrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class SegmentedTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SegmentedTranscriptionService.class);
    private static final long SEGMENT_MS = 60_000L;

    private final AliyunAsrUtils aliyunAsrUtils;
    private final AgentTelemetry telemetry;
    private final AsyncTaskExecutor asrExecutor;
    private final int parallelism;
    private final MediaProcessLimiter mediaProcessLimiter;

    public SegmentedTranscriptionService(
            AliyunAsrUtils aliyunAsrUtils,
            AgentTelemetry telemetry,
            @Qualifier("asrExecutor") AsyncTaskExecutor asrExecutor,
            MediaProcessLimiter mediaProcessLimiter,
            @Value("${video.asr.segment-parallelism:2}") int parallelism) {
        this.aliyunAsrUtils = aliyunAsrUtils;
        this.telemetry = telemetry;
        this.asrExecutor = asrExecutor;
        this.mediaProcessLimiter = mediaProcessLimiter;
        if (parallelism < 1) throw new IllegalArgumentException("ASR parallelism must be positive");
        this.parallelism = parallelism;
    }

    public List<TranscriptSegment> transcribe(String videoPath, Path audioDir, String traceId) throws Exception {
        Files.createDirectories(audioDir);
        Path outputPattern = audioDir.resolve("audio_%03d.mp3");
        runFfmpeg(videoPath, outputPattern);

        List<Path> audioFiles;
        try (var paths = Files.list(audioDir)) {
            audioFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        TranscriptSegment[] ordered = new TranscriptSegment[audioFiles.size()];
        int failedSegments = 0;
        for (int batchStart = 0; batchStart < audioFiles.size(); batchStart += parallelism) {
            int batchEnd = Math.min(audioFiles.size(), batchStart + parallelism);
            List<Future<SegmentResult>> futures = new ArrayList<>();
            for (int i = batchStart; i < batchEnd; i++) {
                int segmentIndex = i;
                Path audioFile = audioFiles.get(i);
                futures.add(asrExecutor.submit(
                        () -> transcribeSegment(segmentIndex, audioFile, traceId)));
            }
            try {
                for (Future<SegmentResult> future : futures) {
                    SegmentResult segmentResult = future.get();
                    if (segmentResult.error() != null) {
                        failedSegments++;
                    } else if (segmentResult.segment() != null) {
                        ordered[segmentResult.index()] = segmentResult.segment();
                    }
                }
            } catch (InterruptedException e) {
                futures.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                futures.forEach(future -> future.cancel(true));
                throw new IllegalStateException("ASR segment worker failed", e.getCause());
            }
        }
        List<TranscriptSegment> result = java.util.Arrays.stream(ordered)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (result.isEmpty() && failedSegments > 0) {
            throw new IllegalStateException("所有 ASR 分片均处理失败");
        }
        return result;
    }

    private SegmentResult transcribeSegment(int index, Path audioFile, String traceId) {
        try {
            telemetry.increment(traceId, "asrCalls", 1);
            String text = aliyunAsrUtils.audioToText(audioFile.toString());
            TranscriptSegment segment = text == null || text.isBlank()
                    ? null
                    : new TranscriptSegment(index * SEGMENT_MS, (index + 1) * SEGMENT_MS, text);
            return new SegmentResult(index, segment, null);
        } catch (RuntimeException e) {
            telemetry.increment(traceId, "asrSegmentFailures", 1);
            log.warn("asr_segment_failed segment={} file={}", index, audioFile.getFileName(), e);
            return new SegmentResult(index, null, e);
        }
    }

    public String transcribeToText(String videoPath) {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "transcription-" + UUID.randomUUID());
        try {
            return transcribe(videoPath, workDir, null).stream()
                    .map(TranscriptSegment::text)
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("视频转写失败", e);
        } finally {
            deleteDirectory(workDir);
        }
    }

    private void runFfmpeg(String videoPath, Path outputPattern) throws Exception {
        mediaProcessLimiter.acquire();
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "ffmpeg", "-y", "-threads", "1", "-i", videoPath,
                    "-vn", "-acodec", "libmp3lame",
                    "-f", "segment", "-segment_time", "60", "-reset_timestamps", "1",
                    outputPattern.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 执行失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            mediaProcessLimiter.release();
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("transcription_temporary_file_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("transcription_temporary_directory_cleanup_failed path={}", directory, e);
        }
    }

    private record SegmentResult(int index, TranscriptSegment segment, RuntimeException error) {
    }
}
