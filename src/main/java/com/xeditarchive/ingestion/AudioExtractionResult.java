package com.xeditarchive.ingestion;

import java.nio.file.Path;

public record AudioExtractionResult(boolean success, Path outputPath, String reason) {
    public static AudioExtractionResult success(Path outputPath) {
        return new AudioExtractionResult(true, outputPath, null);
    }

    public static AudioExtractionResult unavailable(String reason) {
        return new AudioExtractionResult(false, null, reason);
    }
}
