package com.example.server.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OcrTextSanitizerTest {

    @Test
    void removesTesseractResolutionDiagnosticsFromEvidenceText() {
        String value = "Warning: Invalid resolution 0 dpi. Using 70 instead. Estimating resolution as 477.\n"
                + "与非门逻辑功能测试";

        assertThat(OcrTextSanitizer.sanitize(value)).isEqualTo("与非门逻辑功能测试");
    }

    @Test
    void keepsActualTextThatSharesTheSameOcrOutput() {
        assertThat(OcrTextSanitizer.sanitize("Estimating resolution as 477.\n实验一：基本门电路"))
                .isEqualTo("实验一：基本门电路");
    }
}
