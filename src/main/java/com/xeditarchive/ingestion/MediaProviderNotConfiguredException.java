package com.xeditarchive.ingestion;

/**
 * The media provider cannot be used because of operator configuration (missing or rejected
 * credentials, missing sample directory). Jobs wait without using retries until it is fixed.
 */
public class MediaProviderNotConfiguredException extends RuntimeException {

    public MediaProviderNotConfiguredException(String message) {
        super(message);
    }
}
