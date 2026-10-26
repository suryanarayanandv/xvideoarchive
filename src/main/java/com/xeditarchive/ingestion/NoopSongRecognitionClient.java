package com.xeditarchive.ingestion;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "recognition.audd.enabled", havingValue = "false")
public class NoopSongRecognitionClient implements SongRecognitionClient {

    @Override
    public SongRecognitionResult recognize(Path audioFile) {
        return SongRecognitionResult.noMatch("No recognition provider is configured yet for this archive instance.");
    }
}
