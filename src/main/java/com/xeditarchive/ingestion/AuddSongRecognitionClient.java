package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "recognition.audd.enabled", havingValue = "true", matchIfMissing = true)
public class AuddSongRecognitionClient implements SongRecognitionClient {

    private static final Logger log = LoggerFactory.getLogger(AuddSongRecognitionClient.class);
    /** Documented AudD errors: #900 invalid/inactive token, #901 no token and the anonymous limit was reached. */
    private static final int ERROR_INVALID_TOKEN = 900;
    private static final int ERROR_NO_TOKEN_LIMIT = 901;
    private static final String PUBLIC_DEMO_TOKEN = "test";

    private final RestClient restClient;
    private final String apiToken;

    public AuddSongRecognitionClient(RestClient.Builder restClientBuilder,
                                     @Value("${recognition.audd.endpoint:https://api.audd.io/}") String endpoint,
                                     @Value("${recognition.audd.api-token:}") String apiToken) {
        this.restClient = restClientBuilder.baseUrl(Objects.requireNonNull(endpoint)).build();
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        if (this.apiToken.isEmpty()) {
            log.warn("AudD recognition is enabled but AUDD_API_TOKEN is not set; jobs will wait in RETRY_WAIT until it is configured.");
        } else if (PUBLIC_DEMO_TOKEN.equals(this.apiToken)) {
            log.warn("AUDD_API_TOKEN is AudD's public demo token; it is heavily limited and only suitable for local checks.");
        }
    }

    @Override
    public void verifyConfigured() {
        if (apiToken.isEmpty()) {
            throw new RecognitionNotConfiguredException("AudD is enabled but AUDD_API_TOKEN is not configured.");
        }
    }

    @Override
    public SongRecognitionResult recognize(Path audioFile) {
        verifyConfigured();
        if (audioFile == null || !Files.isRegularFile(audioFile) || !Files.isReadable(audioFile)) {
            throw new IllegalArgumentException("Recognition audio file must exist and be readable.");
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("api_token", apiToken);
        form.add("return", "apple_music,spotify");
        form.add("file", new FileSystemResource(audioFile));

        JsonNode response;
        try {
            response = restClient.post()
                    .contentType(Objects.requireNonNull(MediaType.MULTIPART_FORM_DATA))
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("AudD recognition request failed.", exception);
        }

        if (response != null && !"success".equals(response.path("status").asText())) {
            int errorCode = response.path("error").path("error_code").asInt(0);
            if (errorCode == ERROR_INVALID_TOKEN || errorCode == ERROR_NO_TOKEN_LIMIT) {
                // Only the code is surfaced; the provider message and request echo are not stored.
                throw new RecognitionNotConfiguredException(
                        "AudD rejected AUDD_API_TOKEN (error " + errorCode + "): check the token and account status on dashboard.audd.io.");
            }
        }
        if (response == null || !"success".equals(response.path("status").asText())) {
            String providerMessage = response == null ? "empty response" : response.path("error").path("error_message").asText("unknown provider error");
            throw new IllegalStateException("AudD recognition failed: " + providerMessage);
        }

        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return SongRecognitionResult.noMatch("AudD did not identify a song in the audio sample.");
        }

        String title = textOrNull(result, "title");
        String artist = textOrNull(result, "artist");
        if (title == null || artist == null) {
            throw new IllegalStateException("AudD returned a match without a title or artist.");
        }

        return SongRecognitionResult.match(
                title,
                artist,
                textOrNull(result, "album"),
                textOrNull(result, "song_link"),
                "audd",
                null,
                parseTimecode(textOrNull(result, "timecode"))
        );
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static Double parseTimecode(String timecode) {
        if (timecode == null) {
            return null;
        }
        try {
            String[] parts = timecode.split(":");
            double seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return seconds;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
