package com.xeditarchive.ingestion;

public enum ProcessingJobStatus {
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
