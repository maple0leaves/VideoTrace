package com.example.server.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisTaskKeysTest {

    private static final String VIDEO_HASH = "0123456789abcdef0123456789abcdef";

    @Test
    void systemProvisionedMediaUsesItsOwnMediaScope() {
        assertThat(AnalysisTaskKeys.analysisScope(101L, VIDEO_HASH, "experiment-one-tutorial"))
                .isEqualTo("media-101");
        assertThat(AnalysisTaskKeys.analysisScope(202L, VIDEO_HASH, "experiment-one-tutorial"))
                .isEqualTo("media-202");
    }

    @Test
    void userUploadedMediaKeepsContentBasedScope() {
        assertThat(AnalysisTaskKeys.analysisScope(101L, VIDEO_HASH, null))
                .isEqualTo(VIDEO_HASH);
    }
}
