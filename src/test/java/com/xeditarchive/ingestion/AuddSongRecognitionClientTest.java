package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class AuddSongRecognitionClientTest {

    @TempDir
    Path tempDirectory;

    @Test
    void recognize_mapsAudDMatchResponse() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.audd.io/"))
                .andExpect(method(Objects.requireNonNull(POST)))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, Objects.requireNonNull(org.hamcrest.Matchers.startsWith("multipart/form-data"))))
                .andExpect(content().string(Objects.requireNonNull(org.hamcrest.Matchers.containsString("api_token"))))
                .andExpect(content().string(Objects.requireNonNull(org.hamcrest.Matchers.containsString("test-token"))))
                .andRespond(withSuccess("""
                        {"status":"success","result":{"title":"Midnight City","artist":"M83","album":"Hurry Up, We're Dreaming","song_link":"https://lis.tn/MidnightCity","timecode":"00:00:12.5"}}
                        """, MediaType.APPLICATION_JSON));

        Path audio = Files.writeString(tempDirectory.resolve("sample.wav"), "audio-bytes");
        AuddSongRecognitionClient client = new AuddSongRecognitionClient(builder, "https://api.audd.io/", "test-token");

        SongRecognitionResult result = client.recognize(audio);

        assertThat(result.matched()).isTrue();
        assertThat(result.title()).isEqualTo("Midnight City");
        assertThat(result.artist()).isEqualTo("M83");
        assertThat(result.album()).isEqualTo("Hurry Up, We're Dreaming");
        assertThat(result.externalTrackId()).isEqualTo("https://lis.tn/MidnightCity");
        assertThat(result.externalProvider()).isEqualTo("audd");
        assertThat(result.confidence()).isNull();
        assertThat(result.matchStartSeconds()).isEqualTo(12.5);
        server.verify();
    }

    @Test
    void recognize_prefersIsrc_thenSpotifyId_thenSongLink_asStableTrackId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(AuddSongRecognitionClient.stableTrackId(mapper.readTree("""
                {"song_link":"https://lis.tn/x","spotify":{"id":"4RvWPyQ5RL0ao9LPZeSouE","external_ids":{"isrc":"gbf088590110"}},
                 "apple_music":{"isrc":"OTHER"}}
                """))).isEqualTo("isrc:GBF088590110");
        assertThat(AuddSongRecognitionClient.stableTrackId(mapper.readTree("""
                {"song_link":"https://lis.tn/x","apple_music":{"isrc":"GBF088590110"}}
                """))).isEqualTo("isrc:GBF088590110");
        assertThat(AuddSongRecognitionClient.stableTrackId(mapper.readTree("""
                {"song_link":"https://lis.tn/x","spotify":{"id":"4RvWPyQ5RL0ao9LPZeSouE"}}
                """))).isEqualTo("spotify:4RvWPyQ5RL0ao9LPZeSouE");
        assertThat(AuddSongRecognitionClient.stableTrackId(mapper.readTree("""
                {"song_link":"https://lis.tn/x","spotify":null,"apple_music":[]}
                """))).isEqualTo("https://lis.tn/x");
        assertThat(AuddSongRecognitionClient.stableTrackId(mapper.readTree("{}"))).isNull();
    }

    @Test
    void recognize_error300_isNoMatch_notRetried() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.audd.io/")).andRespond(withSuccess(
                "{\"status\":\"error\",\"error\":{\"error_code\":300,\"error_message\":\"Recognition failed\"}}",
                MediaType.APPLICATION_JSON));
        Path audio = Files.writeString(tempDirectory.resolve("short.wav"), "audio-bytes");

        SongRecognitionResult result = new AuddSongRecognitionClient(builder, "https://api.audd.io/", "t").recognize(audio);

        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).contains("error 300");
        server.verify();
    }

    @Test
    void recognize_errors400and500_areUnusableAudio() throws Exception {
        for (int code : new int[]{400, 500}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.audd.io/")).andRespond(withSuccess(
                    "{\"status\":\"error\",\"error\":{\"error_code\":" + code + ",\"error_message\":\"bad\"}}",
                    MediaType.APPLICATION_JSON));
            Path audio = Files.writeString(tempDirectory.resolve("bad-" + code + ".wav"), "audio-bytes");

            assertThatThrownBy(() -> new AuddSongRecognitionClient(builder, "https://api.audd.io/", "t").recognize(audio))
                    .isInstanceOf(UnusableAudioException.class)
                    .hasMessageContaining("error " + code);
            server.verify();
        }
    }

    @Test
    void recognize_otherProviderErrors_stayRetryable() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.audd.io/")).andRespond(withSuccess(
                "{\"status\":\"error\",\"error\":{\"error_code\":700,\"error_message\":\"no file\"}}",
                MediaType.APPLICATION_JSON));
        Path audio = Files.writeString(tempDirectory.resolve("other.wav"), "audio-bytes");

        assertThatThrownBy(() -> new AuddSongRecognitionClient(builder, "https://api.audd.io/", "t").recognize(audio))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("error 700");
        server.verify();
    }

    @Test
    void recognize_timesOut_whenAudDStallsPastReadTimeout() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (StubHttpServer stub = new StubHttpServer().handle("/", ex -> {
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            StubHttpServer.respond(ex, 200, "application/json", "{\"status\":\"success\",\"result\":null}".getBytes());
        })) {
            Path audio = Files.writeString(tempDirectory.resolve("slow.wav"), "audio-bytes");
            AuddSongRecognitionClient client = new AuddSongRecognitionClient(RestClient.builder(), stub.url("/"), "t",
                    java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(500));

            long start = System.nanoTime();
            try {
                assertThatThrownBy(() -> client.recognize(audio))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("request failed");
            } finally {
                release.countDown();
            }
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
        }
    }

    @Test
    void recognize_returnsNoMatchWhenAudDHasNoResult() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.audd.io/"))
                .andRespond(withSuccess("{\"status\":\"success\",\"result\":null}", MediaType.APPLICATION_JSON));
        Path audio = Files.writeString(tempDirectory.resolve("no-match.wav"), "audio-bytes");
        AuddSongRecognitionClient client = new AuddSongRecognitionClient(builder, "https://api.audd.io/", "test-token");

        SongRecognitionResult result = client.recognize(audio);

        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).contains("did not identify");
        server.verify();
    }

    @Test
    void recognize_failsWhenAudDRejectsRequest() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.audd.io/"))
                .andRespond(withServerError());
        Path audio = Files.writeString(tempDirectory.resolve("provider-error.wav"), "audio-bytes");
        AuddSongRecognitionClient client = new AuddSongRecognitionClient(builder, "https://api.audd.io/", "test-token");

        assertThatThrownBy(() -> client.recognize(audio))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AudD recognition request failed");
        server.verify();
    }

    @Test
    void recognize_doesNotCallAudDWhenTokenIsMissing() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Path audio = Files.writeString(tempDirectory.resolve("missing-token.wav"), "audio-bytes");
        AuddSongRecognitionClient client = new AuddSongRecognitionClient(builder, "https://api.audd.io/", " ");

        assertThatThrownBy(client::verifyConfigured).isInstanceOf(RecognitionNotConfiguredException.class);
        assertThatThrownBy(() -> client.recognize(audio))
                .isInstanceOf(RecognitionNotConfiguredException.class)
                .hasMessageContaining("AUDD_API_TOKEN");
        server.verify();
    }

    @Test
    void recognize_treatsRejectedTokenAsConfigurationError_withoutLeakingIt() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.audd.io/"))
                .andRespond(withSuccess("""
                        {"status":"error","error":{"error_code":900,"error_message":"authorization failed"},"request_params":{"api_token":"s***t"}}
                        """, MediaType.APPLICATION_JSON));
        Path audio = Files.writeString(tempDirectory.resolve("bad-token.wav"), "audio-bytes");
        AuddSongRecognitionClient client = new AuddSongRecognitionClient(builder, "https://api.audd.io/", "secret-token");

        assertThatThrownBy(() -> client.recognize(audio))
                .isInstanceOf(RecognitionNotConfiguredException.class)
                .hasMessageContaining("error 900")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-token"));
        server.verify();
    }
}
