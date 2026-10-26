package com.xeditarchive.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Limits applied to every media fetch and FFmpeg run.
 *
 * @param maxDownloadBytes        hard cap on bytes written to disk for one media file
 * @param maxVideoDurationSeconds videos longer than this (per provider metadata) are skipped before download
 * @param connectTimeout          connect timeout for media and provider HTTP calls
 * @param downloadTimeout         wall-clock budget for one complete download, including redirects and body
 * @param ffmpegTimeout           wall-clock budget for one FFmpeg extraction
 * @param allowedHosts            exact host names media may be downloaded from (SSRF guard)
 * @param allowInsecureHttp       permit plain http downloads; intended only for local test stubs
 * @param tempDir                 base directory for per-job temporary workspaces; blank means the JVM temp dir
 */
@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        @DefaultValue("104857600") long maxDownloadBytes,
        @DefaultValue("600") int maxVideoDurationSeconds,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("60s") Duration downloadTimeout,
        @DefaultValue("60s") Duration ffmpegTimeout,
        @DefaultValue("video.twimg.com") List<String> allowedHosts,
        @DefaultValue("false") boolean allowInsecureHttp,
        String tempDir
) {
    public static MediaProperties defaults() {
        return new MediaProperties(104_857_600L, 600, Duration.ofSeconds(10), Duration.ofSeconds(60),
                Duration.ofSeconds(60), List.of("video.twimg.com"), false, null);
    }
}
