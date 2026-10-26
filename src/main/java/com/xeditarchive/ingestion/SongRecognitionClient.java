package com.xeditarchive.ingestion;

import java.nio.file.Path;

public interface SongRecognitionClient {
    SongRecognitionResult recognize(Path audioFile);

    /**
     * Cheap local check run before any media is fetched, so a misconfigured provider does not
     * spend media-source quota. Throws {@link RecognitionNotConfiguredException} when unusable.
     */
    default void verifyConfigured() {
    }
}
