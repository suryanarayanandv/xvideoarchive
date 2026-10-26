package com.xeditarchive.ingestion;

import java.nio.file.Path;

public interface SongRecognitionClient {
    SongRecognitionResult recognize(Path audioFile);
}
