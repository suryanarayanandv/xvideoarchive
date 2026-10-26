package com.xeditarchive.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when no media provider is selected ({@code media.provider=none} or unset). */
@Component
@ConditionalOnProperty(name = "media.provider", havingValue = "none", matchIfMissing = true)
public class DefaultXPostMediaClient implements XPostMediaClient {

    @Override
    public PostMediaFetchResult fetchMedia(Ingestion ingestion) {
        return PostMediaFetchResult.unavailable(
                "No authorized media provider is configured (set media.provider to x-api or local-sample). " +
                        "The job is marked MEDIA_UNAVAILABLE."
        );
    }
}
