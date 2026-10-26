package com.xeditarchive.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "recognition.audd.enabled", havingValue = "true", matchIfMissing = true)
public class AuddSongRecognitionClient implements SongRecognitionClient {

    private static final Logger log = LoggerFactory.getLogger(AuddSongRecognitionClient.class);
    /** Documented AudD errors: #900 invalid/inactive token, #901 no token and the anonymous limit was reached. */
    private static final Set<Integer> TOKEN_ERRORS = Set.of(900, 901);
    /** #300: fingerprinting failed, typically because the audio is too short. Retrying the same sample cannot help. */
    private static final int ERROR_FINGERPRINT = 300;
    /** #400: file too big (10 MB max); #500: invalid audio file. Both are properties of the sample itself. */
    private static final Set<Integer> UNUSABLE_AUDIO_ERRORS = Set.of(400, 500);
    private static final String PUBLIC_DEMO_TOKEN = "test";

    private final RestClient restClient;
    private final String apiToken;

    @Autowired
    public AuddSongRecognitionClient(RestClient.Builder restClientBuilder,
                                     @Value("${recognition.audd.endpoint:https://api.audd.io/}") String endpoint,
                                     @Value("${recognition.audd.api-token:}") String apiToken,
                                     @Value("${recognition.audd.connect-timeout:10s}") Duration connectTimeout,
                                     @Value("${recognition.audd.read-timeout:30s}") Duration readTimeout) {
        // clone(): the shared Boot builder must not inherit this client's timeouts.
        this(restClientBuilder.clone().requestFactory(timeoutRequestFactory(connectTimeout, readTimeout)),
                endpoint, apiToken);
    }

    /** Uses the builder as given (tests bind a mock server to it). */
    AuddSongRecognitionClient(RestClient.Builder restClientBuilder, String endpoint, String apiToken) {
        this.restClient = restClientBuilder.baseUrl(Objects.requireNonNull(endpoint)).build();
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        if (this.apiToken.isEmpty()) {
            log.warn("AudD recognition is enabled but AUDD_API_TOKEN is not set; jobs will wait in RETRY_WAIT until it is configured.");
        } else if (PUBLIC_DEMO_TOKEN.equals(this.apiToken)) {
            log.warn("AUDD_API_TOKEN is AudD's public demo token; it is heavily limited and only suitable for local checks.");
        }
    }

    static JdkClientHttpRequestFactory timeoutRequestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Objects.requireNonNull(readTimeout));
        return factory;
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
            // Includes connect/read timeouts; transient, so the worker may retry.
            throw new IllegalStateException("AudD recognition request failed.", exception);
        }

        if (response == null) {
            throw new IllegalStateException("AudD recognition failed: empty response");
        }
        if (!"success".equals(response.path("status").asText())) {
            int errorCode = response.path("error").path("error_code").asInt(0);
            // Only the code is surfaced for credential errors; the provider message and request echo are not stored.
            if (TOKEN_ERRORS.contains(errorCode)) {
                throw new RecognitionNotConfiguredException(
                        "AudD rejected AUDD_API_TOKEN (error " + errorCode + "): check the token and account status on dashboard.audd.io.");
            }
            if (errorCode == ERROR_FINGERPRINT) {
                return SongRecognitionResult.noMatch("AudD could not fingerprint the sample (error 300; audio may be too short).");
            }
            if (UNUSABLE_AUDIO_ERRORS.contains(errorCode)) {
                throw new UnusableAudioException("AudD rejected the audio sample (error " + errorCode + ").");
            }
            String providerMessage = response.path("error").path("error_message").asText("unknown provider error");
            throw new IllegalStateException("AudD recognition failed (error " + errorCode + "): " + providerMessage);
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
                stableTrackId(result),
                "audd",
                null,
                parseTimecode(textOrNull(result, "timecode"))
        );
    }

    /**
     * Most stable identifier available for the recording: ISRC (shared across stores), then the
     * Spotify track ID, then AudD's song_link. Null lets the caller fall back to title/artist.
     */
    static String stableTrackId(JsonNode result) {
        String isrc = firstNonNull(
                textOrNull(result.path("spotify").path("external_ids"), "isrc"),
                textOrNull(result.path("apple_music"), "isrc"));
        if (isrc != null) {
            return "isrc:" + isrc.trim().toUpperCase();
        }
        String spotifyId = textOrNull(result.path("spotify"), "id");
        if (spotifyId != null) {
            return "spotify:" + spotifyId.trim();
        }
        return textOrNull(result, "song_link");
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
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
