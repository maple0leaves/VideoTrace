package com.example.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class YtDlpUtils {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUtils.class);
    private static final int MAX_URL_LENGTH = 2_048;

    private final String ytDlpPath;
    private final String ffmpegDir;
    private final HostResolver hostResolver;

    @Autowired
    public YtDlpUtils(@Value("${tool.ytdlp.path}") String ytDlpPath,
                      @Value("${tool.ffmpeg.dir}") String ffmpegDir) {
        this(ytDlpPath, ffmpegDir, InetAddress::getAllByName);
    }

    YtDlpUtils(String ytDlpPath, String ffmpegDir, HostResolver hostResolver) {
        this.ytDlpPath = ytDlpPath;
        this.ffmpegDir = ffmpegDir;
        this.hostResolver = hostResolver;
    }

    public File downloadVideo(String url) throws Exception {
        URI validatedUrl = validatePublicHttpUrl(url);
        Path outputPath = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".mp4");
        Path logPath = Files.createTempFile("yt-dlp-", ".log");
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--ignore-config");
        command.add("--use-extractors");
        command.add("default,-generic");
        command.add("--no-playlist");
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        command.add("--max-filesize");
        command.add("2048M");
        // Prefer the broadly supported H.264/AVC + AAC combination for imported
        // videos. Merely changing an AV1 file's container to MP4 does not make it
        // playable in Safari on every macOS and hardware combination.
        command.add("-f");
        command.add("bv*[vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/b[vcodec^=avc1][ext=mp4]/bv*[vcodec^=avc1]+ba[acodec^=mp4a]");
        command.add("--merge-output-format");
        command.add("mp4");
        command.add("--recode-video");
        command.add("mp4");
        if (ffmpegDir != null && !ffmpegDir.isBlank()) {
            command.add("--ffmpeg-location");
            command.add(ffmpegDir);
        }
        command.add("-o");
        command.add(outputPath.toString());
        command.add(validatedUrl.toASCIIString());

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("视频链接下载超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
                String logs = Files.readString(logPath);
                throw new IllegalStateException("yt-dlp 下载失败: " + tail(logs, 2_000));
            }
            log.info("url_video_downloaded host={} bytes={}", validatedUrl.getHost(), Files.size(outputPath));
            return outputPath.toFile();
        } catch (Exception e) {
            Files.deleteIfExists(outputPath);
            throw e;
        } finally {
            Files.deleteIfExists(logPath);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    URI validatePublicHttpUrl(String value) throws Exception {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("视频链接格式无效");
        }

        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String rawHost = uri.getHost();
        String host = rawHost == null ? null
                : IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        if (uri.getUserInfo() != null || !isStandardPort(uri)) {
            throw new IllegalArgumentException("视频链接不允许包含用户信息或非标准端口");
        }
        if (!isSupportedVideoPage(host, uri.getRawPath())) {
            throw new IllegalArgumentException("仅支持 YouTube、Bilibili 和 Douyin 的视频链接");
        }

        InetAddress[] addresses = hostResolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("视频链接域名无法解析");
        }
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) {
                throw new IllegalArgumentException("不允许访问本机、内网、保留网段或云实例元数据地址");
            }
        }
        return uri;
    }

    private boolean isStandardPort(URI uri) {
        int port = uri.getPort();
        if (port == -1) return true;
        return ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
    }

    private boolean isSupportedVideoPage(String host, String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        if ("youtu.be".equals(host)) {
            return hasPathValue(path);
        }
        if (isHostOrSubdomain(host, "youtube.com")) {
            return "/watch".equals(path)
                    || path.startsWith("/shorts/")
                    || path.startsWith("/live/")
                    || path.startsWith("/embed/");
        }
        if ("b23.tv".equals(host)) {
            return hasPathValue(path);
        }
        if (isHostOrSubdomain(host, "bilibili.com")) {
            return path.startsWith("/video/") || path.startsWith("/bangumi/play/");
        }
        if ("v.douyin.com".equals(host)) {
            return hasPathValue(path);
        }
        if (isHostOrSubdomain(host, "douyin.com")) {
            return path.startsWith("/video/");
        }
        return false;
    }

    private boolean hasPathValue(String path) {
        return path.length() > 1 && !path.startsWith("//");
    }

    private boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 224;
        }
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }

    private String tail(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(value.length() - maxLength);
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws Exception;
    }
}
