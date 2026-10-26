package com.xeditarchive.ingestion;

/**
 * The recognition provider cannot be used because of operator configuration (missing, invalid or
 * inactive credentials). This says nothing about the post itself, so jobs must not be marked
 * NO_MATCH or burn retries because of it; they wait until the configuration is fixed.
 */
public class RecognitionNotConfiguredException extends RuntimeException {

    public RecognitionNotConfiguredException(String message) {
        super(message);
    }
}
