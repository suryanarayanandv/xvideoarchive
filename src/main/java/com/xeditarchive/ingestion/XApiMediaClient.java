package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Fetches post media through the documented X API v2 Post lookup endpoint
 * ({@code GET /2/tweets/:id} with {@code expansions=attachments.media_keys} and
 * {@code media.fields=type,duration_ms,variants}), then downloads the smallest MP4 variant
 * through {@link BoundedMediaDownloader}.
 * <p>
 * Requires an app-only Bearer token from the X Developer Console in {@code X_BEARER_TOKEN}.
 * Enabled with {@code media.provider=x-api}.
 */
@Component
@ConditionalOnProperty(name = "media.provider", havingValue = "x-api")
public class XApiMediaClient implements XPostMediaClient {

    private static final Logger log = LoggerFactory.getLogger(XApiMediaClient.class);
    private static final String LOOKUP_QUERY =
            "?expansions=attachments.media_keys&media.fields=type,duration_ms,variants";

    private final MediaProperties properties;
    private final BoundedMediaDownloader downloader;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiBaseUrl;
    private final String bearerToken;

    public XApiMediaClient(MediaProperties properties,
                           BoundedMediaDownloader downloader,
                           ObjectMapper objectMapper,
                           @Value("${media.x-api.base-url:https://api.x.com}") String apiBaseUrl,
                           @Value("${media.x-api.bearer-token:}") String bearerToken) {
        this.properties = properties;
        this.downloader = downloader;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public PostMediaFetchResult fetchMedia(Ingestion ingestion) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return PostMediaFetchResult.transientFailure(
                    "media.provider=x-api is selected but X_BEARER_TOKEN is not configured.");
        }
        String postId = ingestion.getPlatformPostId();
        if (postId == null || !postId.matches("\\d{1,25}")) {
            return PostMediaFetchResult.unavailable("Post ID is not numeric; refusing to call the X API.");
        }

        JsonNode body;
        try {
            body = lookupPost(postId);
        } catch (MediaDownloadException e) {
            return e.isTransientFailure()
                    ? PostMediaFetchResult.transientFailure(e.getMessage())
                    : PostMediaFetchResult.unavailable(e.getMessage());
        }

        if (body.path("data").isMissingNode()) {
            String detail = body.path("errors").path(0).path("detail").asText("post not returned by the X API");
            return PostMediaFetchResult.unavailable("X API: " + detail);
        }

        Optional<JsonNode> video = StreamSupport.stream(body.path("includes").path("media").spliterator(), false)
                .filter(media -> {
                    String type = media.path("type").asText();
                    return "video".equals(type) || "animated_gif".equals(type);
                })
                .findFirst();
        if (video.isEmpty()) {
            return PostMediaFetchResult.unavailable("Post has no video or animated GIF attachment.");
        }

        long durationMs = video.get().path("duration_ms").asLong(0);
        if (durationMs > properties.maxVideoDurationSeconds() * 1000L) {
            return PostMediaFetchResult.unavailable("Video is " + (durationMs / 1000) + "s, above the "
                    + properties.maxVideoDurationSeconds() + "s limit.");
        }

        Optional<String> variantUrl = StreamSupport.stream(video.get().path("variants").spliterator(), false)
                .filter(variant -> "video/mp4".equals(variant.path("content_type").asText()))
                .filter(variant -> !variant.path("url").asText().isBlank())
                // Audio is what matters, so the lowest bit-rate MP4 keeps downloads small.
                .min(Comparator.comparingLong(variant -> variant.path("bit_rate").asLong(Long.MAX_VALUE)))
                .map(variant -> variant.path("url").asText());
        if (variantUrl.isEmpty()) {
            return PostMediaFetchResult.unavailable("X API returned no downloadable MP4 variant for this post.");
        }

        Path workspace = null;
        try {
            workspace = MediaWorkspaces.create(properties, ingestion.getId());
            Path target = workspace.resolve("media.mp4");
            downloader.download(variantUrl.get(), target);
            return PostMediaFetchResult.available(new FetchedMedia(target, workspace, "x-api"));
        } catch (MediaDownloadException e) {
            MediaWorkspaces.deleteRecursively(workspace);
            return e.isTransientFailure()
                    ? PostMediaFetchResult.transientFailure(e.getMessage())
                    : PostMediaFetchResult.unavailable(e.getMessage());
        } catch (IOException e) {
            MediaWorkspaces.deleteRecursively(workspace);
            return PostMediaFetchResult.transientFailure("Could not create temporary media workspace: " + e.getMessage());
        }
    }

    private JsonNode lookupPost(String postId) throws MediaDownloadException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/2/tweets/" + postId + LOOKUP_QUERY))
                .timeout(properties.downloadTimeout().compareTo(Duration.ofSeconds(30)) < 0
                        ? properties.downloadTimeout() : Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new MediaDownloadException("X API lookup timed out", true);
        } catch (IOException e) {
            throw new MediaDownloadException("X API lookup failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaDownloadException("X API lookup interrupted", true);
        }

        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw new MediaDownloadException("X API returned HTTP " + status, true);
        }
        if (status == 401 || status == 403) {
            // Credential or access-tier problem: retryable once the configuration is fixed.
            log.warn("X API rejected the lookup with HTTP {}; check X_BEARER_TOKEN and account credits", status);
            throw new MediaDownloadException("X API returned HTTP " + status + " (credentials or access)", true);
        }
        if (status == 404) {
            throw new MediaDownloadException("X API: post not found", false);
        }
        if (status != 200) {
            throw new MediaDownloadException("X API returned HTTP " + status, false);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new MediaDownloadException("X API returned unparseable JSON", true);
        }
    }
}
