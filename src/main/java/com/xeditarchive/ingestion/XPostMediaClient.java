package com.xeditarchive.ingestion;

public interface XPostMediaClient {
    PostMediaFetchResult fetchMedia(Ingestion ingestion);

    /**
     * Cheap local check run before a job is claimed, so a misconfigured provider does not use up
     * retries. Throws {@link MediaProviderNotConfiguredException} when unusable.
     */
    default void verifyConfigured() {
    }
}
