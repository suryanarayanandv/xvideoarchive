package com.xeditarchive.ingestion;

public record IngestionResponse(Long id, String status) {
    public static IngestionResponse from(Ingestion ingestion) {
        return new IngestionResponse(ingestion.getId(), ingestion.getStatus().name());
    }
}
