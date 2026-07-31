package com.example.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class OcrUtils {

    private static final Logger log = LoggerFactory.getLogger(OcrUtils.class);

    private final String ocrCommand;
    private final long timeoutSeconds;

    public OcrUtils(@Value("${tool.ocr.command:tesseract}") String ocrCommand,
                    @Value("${tool.ocr.timeout-seconds:30}") long timeoutSeconds) {
        this.ocrCommand = ocrCommand;
        if (timeoutSeconds < 1) throw new IllegalArgumentException("OCR timeout must be positive");
        this.timeoutSeconds = timeoutSeconds;
    }

    public String recognize(File image) {
        if (image == null || !image.isFile()) throw new IllegalArgumentException("OCR image does not exist");
        Process process = null;
        Path standardOutput = null;
        Path errorOutput = null;
        try {
            standardOutput = Files.createTempFile("vidotrace-ocr-stdout-", ".txt");
            errorOutput = Files.createTempFile("vidotrace-ocr-stderr-", ".txt");
            process = new ProcessBuilder(
                    ocrCommand, image.getAbsolutePath(), "stdout", "-l", "chi_sim+eng")
                    .redirectOutput(standardOutput.toFile())
                    .redirectError(errorOutput.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("OCR execution timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("OCR process failed with exit code "
                        + process.exitValue() + errorSuffix(errorOutput));
            }
            return OcrTextSanitizer.sanitize(
                    Files.readString(standardOutput, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR execution interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("OCR failed for " + image.getName(), e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteTemporaryOutput(standardOutput);
            deleteTemporaryOutput(errorOutput);
        }
    }

    private String errorSuffix(Path errorOutput) {
        try {
            String error = Files.readString(errorOutput, StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            return error.isBlank() ? "" : ": " + error.substring(0, Math.min(error.length(), 500));
        } catch (Exception ignored) {
            return "";
        }
    }

    private void deleteTemporaryOutput(Path output) {
        if (output == null) return;
        try {
            Files.deleteIfExists(output);
        } catch (Exception e) {
            log.warn("ocr_output_cleanup_failed path={}", output, e);
        }
    }
}
