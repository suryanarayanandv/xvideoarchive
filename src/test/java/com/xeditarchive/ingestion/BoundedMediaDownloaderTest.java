package com.xeditarchive.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedMediaDownloaderTest {

    private static MediaProperties props(long maxBytes, Duration timeout) {
        return new MediaProperties(maxBytes, 600, Duration.ofSeconds(2), timeout, Duration.ofSeconds(30),
                List.of("127.0.0.1"), true, null);
    }

    @Test
    void downloadsBodyToTarget(@TempDir Path dir) throws Exception {
        byte[] body = new byte[10_000];
        try (StubHttpServer server = new StubHttpServer()
                .handle("/v.mp4", ex -> StubHttpServer.respond(ex, 200, "video/mp4", body))) {
            Path target = dir.resolve("media.mp4");
            new BoundedMediaDownloader(props(1_000_000, Duration.ofSeconds(5))).download(server.url("/v.mp4"), target);
            assertThat(Files.size(target)).isEqualTo(10_000);
        }
    }

    @Test
    void rejectsBodyAboveByteLimit_andRemovesPartialFile(@TempDir Path dir) throws Exception {
        byte[] body = new byte[5_000];
        try (StubHttpServer server = new StubHttpServer()
                .handle("/big.mp4", ex -> StubHttpServer.respond(ex, 200, "video/mp4", body))) {
            Path target = dir.resolve("media.mp4");
            assertThatThrownBy(() -> new BoundedMediaDownloader(props(1_000, Duration.ofSeconds(5)))
                    .download(server.url("/big.mp4"), target))
                    .isInstanceOf(MediaDownloadException.class)
                    .hasMessageContaining("limit")
                    .matches(e -> !((MediaDownloadException) e).isTransientFailure());
            assertThat(target).doesNotExist();
        }
    }

    @Test
    void enforcesLimitWhenContentLengthIsAbsent(@TempDir Path dir) throws Exception {
        try (StubHttpServer server = new StubHttpServer().handle("/chunked.mp4", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked, no Content-Length
            for (int i = 0; i < 10; i++) {
                ex.getResponseBody().write(new byte[1_000]);
            }
            ex.close();
        })) {
            Path target = dir.resolve("media.mp4");
            assertThatThrownBy(() -> new BoundedMediaDownloader(props(2_500, Duration.ofSeconds(5)))
                    .download(server.url("/chunked.mp4"), target))
                    .hasMessageContaining("limit");
            assertThat(target).doesNotExist();
        }
    }

    @Test
    void abortsStalledDownload_asTransientFailure(@TempDir Path dir) throws Exception {
        try (StubHttpServer server = new StubHttpServer().handle("/slow.mp4", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write(new byte[100]);
            ex.getResponseBody().flush();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        })) {
            Path target = dir.resolve("media.mp4");
            long start = System.nanoTime();
            assertThatThrownBy(() -> new BoundedMediaDownloader(props(1_000_000, Duration.ofMillis(800)))
                    .download(server.url("/slow.mp4"), target))
                    .isInstanceOf(MediaDownloadException.class)
                    .matches(e -> ((MediaDownloadException) e).isTransientFailure());
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            assertThat(target).doesNotExist();
        }
    }

    @Test
    void rejectsHostsOutsideAllowlist(@TempDir Path dir) {
        BoundedMediaDownloader downloader = new BoundedMediaDownloader(props(1_000, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> downloader.download("http://169.254.169.254/latest/meta-data", dir.resolve("x")))
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void rejectsPlainHttpUnlessExplicitlyAllowed(@TempDir Path dir) {
        MediaProperties strict = new MediaProperties(1_000, 600, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), List.of("video.twimg.com"), false, null);
        assertThatThrownBy(() -> new BoundedMediaDownloader(strict)
                .download("http://video.twimg.com/a.mp4", dir.resolve("x")))
                .hasMessageContaining("scheme");
    }

    @Test
    void rechecksAllowlistOnRedirect(@TempDir Path dir) throws Exception {
        try (StubHttpServer server = new StubHttpServer().handle("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://evil.example/steal");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        })) {
            assertThatThrownBy(() -> new BoundedMediaDownloader(props(1_000, Duration.ofSeconds(2)))
                    .download(server.url("/redirect"), dir.resolve("x")))
                    .hasMessageContaining("not allowlisted: evil.example");
        }
    }

    @Test
    void classifiesServerErrorsAsTransient_andClientErrorsAsPermanent(@TempDir Path dir) throws Exception {
        try (StubHttpServer server = new StubHttpServer()
                .handle("/503", ex -> StubHttpServer.respond(ex, 503, "text/plain", new byte[0]))
                .handle("/404", ex -> StubHttpServer.respond(ex, 404, "text/plain", new byte[0]))) {
            BoundedMediaDownloader downloader = new BoundedMediaDownloader(props(1_000, Duration.ofSeconds(2)));
            assertThatThrownBy(() -> downloader.download(server.url("/503"), dir.resolve("a")))
                    .matches(e -> ((MediaDownloadException) e).isTransientFailure());
            assertThatThrownBy(() -> downloader.download(server.url("/404"), dir.resolve("b")))
                    .matches(e -> !((MediaDownloadException) e).isTransientFailure());
        }
    }
}
