package com.xeditarchive.ingestion;

/**
 * The recognition provider permanently rejected the audio sample itself (invalid or oversized file).
 * Retrying the same sample cannot succeed, so the job becomes MEDIA_UNAVAILABLE without further attempts.
 */
public class UnusableAudioException extends RuntimeException {

    public UnusableAudioException(String message) {
        super(message);
    }
}
