package com.xeditarchive.ingestion;

/** A media fetch failed; {@link #isTransientFailure()} tells the worker whether a retry makes sense. */
public class MediaDownloadException extends Exception {

    private final boolean transientFailure;

    public MediaDownloadException(String message, boolean transientFailure) {
        super(message);
        this.transientFailure = transientFailure;
    }

    public boolean isTransientFailure() {
        return transientFailure;
    }
}
