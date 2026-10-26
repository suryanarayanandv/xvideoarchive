package com.xeditarchive.ingestion;

public enum IngestionStatus {
    PENDING,
    FETCHING,
    RECOGNIZING,
    COMPLETED,
    NO_MATCH,
    MEDIA_UNAVAILABLE,
    RETRY_WAIT,
    NEEDS_REVIEW,
    FAILED
}
