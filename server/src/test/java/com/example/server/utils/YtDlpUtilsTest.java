package com.example.server.utils;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YtDlpUtilsTest {

    private static final YtDlpUtils.HostResolver PUBLIC_RESOLVER =
            host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};

    @Test
    void acceptsOnlyAdvertisedProviderVideoPages() throws Exception {
        YtDlpUtils utils = new YtDlpUtils("yt-dlp", "", PUBLIC_RESOLVER);

        assertThat(utils.validatePublicHttpUrl(
                "https://www.youtube.com/watch?v=video-id").getHost())
                .isEqualTo("www.youtube.com");
        assertThat(utils.validatePublicHttpUrl(
                "https://b23.tv/abc123").getHost())
                .isEqualTo("b23.tv");
        assertThat(utils.validatePublicHttpUrl(
                "https://www.douyin.com/video/123456").getHost())
                .isEqualTo("www.douyin.com");
    }

    @Test
    void rejectsAlibabaCloudMetadataAddress() {
        YtDlpUtils utils = new YtDlpUtils("yt-dlp", "", PUBLIC_RESOLVER);

        assertThatThrownBy(() -> utils.validatePublicHttpUrl(
                "http://100.100.100.200/latest/meta-data/instance-id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAllowedHostnameWhenDnsResolvesToMetadataNetwork() {
        YtDlpUtils utils = new YtDlpUtils(
                "yt-dlp",
                "",
                host -> new InetAddress[]{InetAddress.getByName("100.100.100.200")});

        assertThatThrownBy(() -> utils.validatePublicHttpUrl(
                "https://www.youtube.com/watch?v=video-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("元数据");
    }

    @Test
    void rejectsAttackerControlledHostnameBeforeDnsResolution() {
        YtDlpUtils utils = new YtDlpUtils(
                "yt-dlp",
                "",
                host -> {
                    throw new AssertionError("unsupported hosts must not be resolved");
                });

        assertThatThrownBy(() -> utils.validatePublicHttpUrl(
                "https://attacker.example/video"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YouTube");
    }

    @Test
    void rejectsProviderRedirectEndpointsAndNonStandardPorts() {
        YtDlpUtils utils = new YtDlpUtils("yt-dlp", "", PUBLIC_RESOLVER);

        assertThatThrownBy(() -> utils.validatePublicHttpUrl(
                "https://www.youtube.com/redirect?q=http://100.100.100.200/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> utils.validatePublicHttpUrl(
                "https://www.bilibili.com:8443/video/BV123"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
