package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
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

    private final RestClient restClient;
    private final String apiToken;

    public AuddSongRecognitionClient(RestClient.Builder restClientBuilder,
                                     @Value("${recognition.audd.endpoint:https://api.audd.io/}") String endpoint,
                                     @Value("${recognition.audd.api-token:}") String apiToken) {
        this.restClient = restClientBuilder.baseUrl(Objects.requireNonNull(endpoint)).build();
        this.apiToken = Objects.requireNonNull(apiToken);
    }

    @Override
    public SongRecognitionResult recognize(Path audioFile) {
        if (apiToken == null || apiToken.isBlank()) {
            return SongRecognitionResult.noMatch("AudD is selected but AUDD_API_TOKEN is not configured.");
        }
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
