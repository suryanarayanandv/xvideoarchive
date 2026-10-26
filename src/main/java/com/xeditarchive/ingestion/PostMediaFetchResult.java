package com.xeditarchive.ingestion;

/**
 * Outcome of asking a media provider for a post's video.
 * <ul>
 *   <li>{@link Outcome#AVAILABLE}: {@link #media()} holds a local file the caller must close.</li>
 *   <li>{@link Outcome#UNAVAILABLE}: permanent; the post has no accessible media (deleted, no video, too large...).</li>
 *   <li>{@link Outcome#TRANSIENT_FAILURE}: retryable (rate limit, 5xx, timeout).</li>
 * </ul>
 */
public record PostMediaFetchResult(Outcome outcome, FetchedMedia media, String reason) {

    public enum Outcome { AVAILABLE, UNAVAILABLE, TRANSIENT_FAILURE }

    public static PostMediaFetchResult available(FetchedMedia media) {
        return new PostMediaFetchResult(Outcome.AVAILABLE, media, null);
    }

    public static PostMediaFetchResult unavailable(String reason) {
        return new PostMediaFetchResult(Outcome.UNAVAILABLE, null, reason);
    }

    public static PostMediaFetchResult transientFailure(String reason) {
        return new PostMediaFetchResult(Outcome.TRANSIENT_FAILURE, null, reason);
    }

    public boolean mediaAvailable() {
        return outcome == Outcome.AVAILABLE && media != null;
    }
}
