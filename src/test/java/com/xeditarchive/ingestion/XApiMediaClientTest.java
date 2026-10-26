package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the adapter against a stub that returns the documented GET /2/tweets/:id response shape. */
class XApiMediaClientTest {

    @TempDir
    Path tempDir;

    private MediaProperties props(int maxDurationSeconds) {
        return new MediaProperties(1_000_000, maxDurationSeconds, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(30), List.of("127.0.0.1"), true, tempDir.toString());
    }

    private XApiMediaClient client(StubHttpServer server, MediaProperties props, String token) {
        return new XApiMediaClient(props, new BoundedMediaDownloader(props), new ObjectMapper(),
                server.url(""), token);
    }

    private static Ingestion ingestion(String postId) {
        return new Ingestion("x", postId, "https://x.com/someone/status/" + postId);
    }

    private static String lookupJson(String mediaJson) {
        return """
                {"data":{"id":"1","text":"edit","attachments":{"media_keys":["7_1"]}},
                 "includes":{"media":[%s]}}
                """.formatted(mediaJson);
    }

    @Test
    void downloadsLowestBitrateMp4_andSendsBearerToken() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> fetchedVariant = new AtomicReference<>();
        try (StubHttpServer server = new StubHttpServer()) {
            String media = """
                    {"media_key":"7_1","type":"video","duration_ms":12000,"variants":[
                      {"content_type":"application/x-mpegURL","url":"%s"},
                      {"content_type":"video/mp4","bit_rate":2176000,"url":"%s"},
                      {"content_type":"video/mp4","bit_rate":632000,"url":"%s"}]}
                    """.formatted(server.url("/pl.m3u8"), server.url("/high.mp4"), server.url("/low.mp4"));
            server.handle("/2/tweets/1", ex -> {
                auth.set(ex.getRequestHeaders().getFirst("Authorization"));
                query.set(ex.getRequestURI().getQuery());
                StubHttpServer.respond(ex, 200, "application/json", lookupJson(media).getBytes(StandardCharsets.UTF_8));
            });
            server.handle("/low.mp4", ex -> {
                fetchedVariant.set("low");
                StubHttpServer.respond(ex, 200, "video/mp4", new byte[2048]);
            });
            server.handle("/high.mp4", ex -> {
                fetchedVariant.set("high");
                StubHttpServer.respond(ex, 200, "video/mp4", new byte[4096]);
            });

            PostMediaFetchResult result = client(server, props(600), "test-token").fetchMedia(ingestion("1"));

            assertThat(result.mediaAvailable()).as(result.reason()).isTrue();
            assertThat(auth.get()).isEqualTo("Bearer test-token");
            assertThat(query.get()).contains("expansions=attachments.media_keys")
                    .contains("media.fields=type,duration_ms,variants");
            assertThat(fetchedVariant.get()).isEqualTo("low");
            try (FetchedMedia fetched = result.media()) {
                assertThat(Files.size(fetched.file())).isEqualTo(2048);
                assertThat(fetched.workspace()).startsWith(tempDir);
            }
            assertThat(result.media().workspace()).doesNotExist();
        }
    }

    @Test
    void postWithoutVideo_isUnavailable() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            server.handle("/2/tweets/1", ex -> StubHttpServer.respond(ex, 200, "application/json",
                    lookupJson("{\"media_key\":\"3_1\",\"type\":\"photo\"}").getBytes(StandardCharsets.UTF_8)));
            PostMediaFetchResult result = client(server, props(600), "t").fetchMedia(ingestion("1"));
            assertThat(result.outcome()).isEqualTo(PostMediaFetchResult.Outcome.UNAVAILABLE);
            assertThat(result.reason()).contains("no video");
        }
    }

    @Test
    void videoLongerThanLimit_isUnavailableWithoutDownloading() throws Exception {
        AtomicReference<Boolean> downloaded = new AtomicReference<>(false);
        try (StubHttpServer server = new StubHttpServer()) {
            String media = """
                    {"media_key":"7_1","type":"video","duration_ms":900000,"variants":[
                      {"content_type":"video/mp4","bit_rate":1,"url":"%s"}]}
                    """.formatted(server.url("/v.mp4"));
            server.handle("/2/tweets/1", ex -> StubHttpServer.respond(ex, 200, "application/json",
                    lookupJson(media).getBytes(StandardCharsets.UTF_8)));
            server.handle("/v.mp4", ex -> {
                downloaded.set(true);
                StubHttpServer.respond(ex, 200, "video/mp4", new byte[10]);
            });
            PostMediaFetchResult result = client(server, props(60), "t").fetchMedia(ingestion("1"));
            assertThat(result.outcome()).isEqualTo(PostMediaFetchResult.Outcome.UNAVAILABLE);
            assertThat(result.reason()).contains("limit");
            assertThat(downloaded.get()).isFalse();
        }
    }

    @Test
    void rateLimit_isTransient_andNotFound_isUnavailable() throws Exception {
        try (StubHttpServer server = new StubHttpServer()
                .handle("/2/tweets/429", ex -> StubHttpServer.respond(ex, 429, "application/json", "{}".getBytes()))
                .handle("/2/tweets/404", ex -> StubHttpServer.respond(ex, 404, "application/json", "{}".getBytes()))) {
            XApiMediaClient client = client(server, props(600), "t");
            assertThat(client.fetchMedia(ingestion("429")).outcome())
                    .isEqualTo(PostMediaFetchResult.Outcome.TRANSIENT_FAILURE);
            assertThat(client.fetchMedia(ingestion("404")).outcome())
                    .isEqualTo(PostMediaFetchResult.Outcome.UNAVAILABLE);
        }
    }

    @Test
    void deletedPostErrorPayload_isUnavailable() throws Exception {
        try (StubHttpServer server = new StubHttpServer().handle("/2/tweets/1", ex ->
                StubHttpServer.respond(ex, 200, "application/json",
                        "{\"errors\":[{\"detail\":\"Could not find tweet with id: [1].\"}]}".getBytes()))) {
            PostMediaFetchResult result = client(server, props(600), "t").fetchMedia(ingestion("1"));
            assertThat(result.outcome()).isEqualTo(PostMediaFetchResult.Outcome.UNAVAILABLE);
            assertThat(result.reason()).contains("Could not find tweet");
        }
    }

    @Test
    void missingToken_isNotConfigured_withoutCallingX() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        try (StubHttpServer server = new StubHttpServer().handle("/2/tweets/1", ex -> {
            called.set(true);
            StubHttpServer.respond(ex, 200, "application/json", "{}".getBytes());
        })) {
            XApiMediaClient client = client(server, props(600), "");
            org.assertj.core.api.Assertions.assertThatThrownBy(client::verifyConfigured)
                    .isInstanceOf(MediaProviderNotConfiguredException.class);
            PostMediaFetchResult result = client.fetchMedia(ingestion("1"));
            assertThat(result.outcome()).isEqualTo(PostMediaFetchResult.Outcome.NOT_CONFIGURED);
            assertThat(result.reason()).contains("X_BEARER_TOKEN");
            assertThat(called.get()).isFalse();
        }
    }

    @Test
    void rejectedCredentials_401and403_areNotConfigured_withoutLeakingToken() throws Exception {
        try (StubHttpServer server = new StubHttpServer()
                .handle("/2/tweets/401", ex -> StubHttpServer.respond(ex, 401, "application/json", "{}".getBytes()))
                .handle("/2/tweets/403", ex -> StubHttpServer.respond(ex, 403, "application/json", "{}".getBytes()))) {
            XApiMediaClient client = client(server, props(600), "secret-bearer");
            for (String id : new String[]{"401", "403"}) {
                PostMediaFetchResult result = client.fetchMedia(ingestion(id));
                assertThat(result.outcome()).isEqualTo(PostMediaFetchResult.Outcome.NOT_CONFIGURED);
                assertThat(result.reason()).contains("HTTP " + id).doesNotContain("secret-bearer");
            }
            try (var entries = Files.list(tempDir)) {
                assertThat(entries).isEmpty();
            }
        }
    }

    @Test
    void failedDownload_leavesNoWorkspaceBehind() throws Exception {
        try (StubHttpServer server = new StubHttpServer()) {
            String media = """
                    {"media_key":"7_1","type":"video","duration_ms":1000,"variants":[
                      {"content_type":"video/mp4","bit_rate":1,"url":"%s"}]}
                    """.formatted(server.url("/gone.mp4"));
            server.handle("/2/tweets/1", ex -> StubHttpServer.respond(ex, 200, "application/json",
                    lookupJson(media).getBytes(StandardCharsets.UTF_8)));
            server.handle("/gone.mp4", ex -> StubHttpServer.respond(ex, 403, "text/plain", new byte[0]));

            PostMediaFetchResult result = client(server, props(600), "t").fetchMedia(ingestion("1"));

            assertThat(result.outcome()).isEqualTo(PostMediaFetchResult.Outcome.UNAVAILABLE);
            try (var entries = Files.list(tempDir)) {
                assertThat(entries).isEmpty();
            }
        }
    }
}
