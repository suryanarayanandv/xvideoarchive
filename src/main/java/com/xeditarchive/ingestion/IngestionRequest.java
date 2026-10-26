package com.xeditarchive.ingestion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record IngestionRequest(
        @NotBlank(message = "URL is required")
        @Pattern(
                regexp = "https?://.*",
                message = "URL must start with http:// or https://"
        )
        String url
) {
}
