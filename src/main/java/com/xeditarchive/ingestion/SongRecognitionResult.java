package com.xeditarchive.ingestion;

public record SongRecognitionResult(
        boolean matched,
        String title,
        String artist,
        String album,
        String externalTrackId,
        String externalProvider,
        Double confidence,
        Double matchStartSeconds,
        String reason
) {
    public static SongRecognitionResult match(String title, String artist, String album,
                                             String externalTrackId, String externalProvider,
                                             Double confidence, Double matchStartSeconds) {
        return new SongRecognitionResult(
                true,
                title,
                artist,
                album,
                externalTrackId,
                externalProvider,
                confidence,
                matchStartSeconds,
                null
        );
    }

    public static SongRecognitionResult noMatch(String reason) {
        return new SongRecognitionResult(false, null, null, null, null, null, null, null, reason);
    }

    public boolean isMatched() {
        return matched;
    }
}
