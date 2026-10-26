package com.xeditarchive.ingestion;

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
