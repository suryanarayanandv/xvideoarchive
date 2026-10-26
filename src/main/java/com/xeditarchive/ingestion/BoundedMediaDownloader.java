package com.xeditarchive.ingestion;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Downloads a media URL to a local file with an allowlisted host, a byte cap and a wall-clock budget.
 * Redirects are followed manually (max 3) so every hop is re-checked against the allowlist.
 */
@Component
public class BoundedMediaDownloader {

    private static final int MAX_REDIRECTS = 3;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final MediaProperties properties;
    private final HttpClient httpClient;
    private final Set<String> allowedHosts;

    public BoundedMediaDownloader(MediaProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.allowedHosts = properties.allowedHosts().stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Writes the media body to {@code target}. On any failure the partial file is removed.
     *
     * @throws MediaDownloadException with {@code transientFailure=true} for retryable conditions
     */
    public void download(String url, Path target) throws MediaDownloadException {
        Instant deadline = Instant.now().plus(properties.downloadTimeout());
        URI current;
        try {
            current = validate(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new MediaDownloadException("Media URL is malformed", false);
        }

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<InputStream> response = send(current, deadline);
            int status = response.statusCode();

            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new MediaDownloadException("Redirect without Location header", false));
                current = validate(current.resolve(location));
                continue;
            }
            if (status == 429 || status >= 500) {
                closeQuietly(response.body());
                throw new MediaDownloadException("Media host returned HTTP " + status, true);
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new MediaDownloadException("Media host returned HTTP " + status, false);
            }

            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > properties.maxDownloadBytes()) {
                closeQuietly(response.body());
                throw new MediaDownloadException("Media is " + declared + " bytes, above the "
                        + properties.maxDownloadBytes() + "-byte limit", false);
            }
            copyBounded(response.body(), target, deadline);
            return;
        }
        throw new MediaDownloadException("Too many redirects", false);
    }

    private HttpResponse<InputStream> send(URI uri, Instant deadline) throws MediaDownloadException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(remaining(deadline))
                .header("Accept", "video/mp4,audio/*;q=0.9,*/*;q=0.1")
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new MediaDownloadException("Media download timed out", true);
        } catch (IOException e) {
            throw new MediaDownloadException("Media download failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaDownloadException("Media download interrupted", true);
        }
    }

    private void copyBounded(InputStream body, Path target, Instant deadline) throws MediaDownloadException {
        long written = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        // A stalled server can block read() indefinitely; close the stream when the budget runs out.
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            closeQuietly(body);
        }, Math.max(1, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);

        try (InputStream in = body;
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > properties.maxDownloadBytes()) {
                    throw new MediaDownloadException("Media exceeded the "
                            + properties.maxDownloadBytes() + "-byte limit", false);
                }
                out.write(buffer, 0, read);
            }
        } catch (MediaDownloadException e) {
            deleteQuietly(target);
            throw e;
        } catch (IOException e) {
            deleteQuietly(target);
            if (timedOut.get()) {
                throw new MediaDownloadException("Media download exceeded the "
                        + properties.downloadTimeout().toSeconds() + "s budget", true);
            }
            throw new MediaDownloadException("Media download failed: " + e.getMessage(), true);
        } finally {
            watchdog.cancel(false);
        }

        if (timedOut.get()) {
            deleteQuietly(target);
            throw new MediaDownloadException("Media download exceeded the "
                    + properties.downloadTimeout().toSeconds() + "s budget", true);
        }
        if (written == 0) {
            deleteQuietly(target);
            throw new MediaDownloadException("Media host returned an empty body", false);
        }
    }

    URI validate(URI uri) throws MediaDownloadException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = "https".equals(scheme) || ("http".equals(scheme) && properties.allowInsecureHttp());
        if (!schemeOk) {
            throw new MediaDownloadException("Media URL scheme is not allowed: " + scheme, false);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw new MediaDownloadException("Media host is not allowlisted: " + host, false);
        }
        if (uri.getUserInfo() != null) {
            throw new MediaDownloadException("Media URL must not contain credentials", false);
        }
        return uri;
    }

    private static Duration remaining(Instant deadline) throws MediaDownloadException {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new MediaDownloadException("Media download exceeded its time budget", true);
        }
        return remaining;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the workspace is removed later anyway
        }
    }
}
