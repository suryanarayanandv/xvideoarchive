package com.xeditarchive.ingestion;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;

@Service
public class SongRecognitionService {

    private final SongRepository songRepository;
    private final SongMatchRepository songMatchRepository;
    private final SongRecognitionClient songRecognitionClient;

    public SongRecognitionService(SongRepository songRepository,
                                 SongMatchRepository songMatchRepository,
                                 SongRecognitionClient songRecognitionClient) {
        this.songRepository = songRepository;
        this.songMatchRepository = songMatchRepository;
        this.songRecognitionClient = songRecognitionClient;
    }

    public SongRecognitionResult recognize(Path audioFile) {
        return songRecognitionClient.recognize(audioFile);
    }

    @Transactional
    public SongMatch persistMatch(Ingestion ingestion, SongRecognitionResult result) {
        if (result == null || !result.matched()) {
            throw new IllegalArgumentException("A recognized song result is required to persist a match.");
        }

        String provider = result.externalProvider() == null ? "unknown" : result.externalProvider();
        String trackId = result.externalTrackId() == null ? "manual-" + System.nanoTime() : result.externalTrackId();

        Song song = songRepository.findByExternalProviderAndExternalTrackId(provider, trackId)
                .orElseGet(() -> songRepository.save(new Song(
                        result.title(),
                        result.artist(),
                        result.album(),
                        null,
                        provider,
                        trackId
                )));

        SongMatch match = new SongMatch(
                ingestion,
                song,
                provider,
                result.confidence(),
                result.matchStartSeconds(),
                true
        );

        return songMatchRepository.save(match);
    }

    public Optional<Song> findSongByProviderAndTrack(String provider, String trackId) {
        return songRepository.findByExternalProviderAndExternalTrackId(provider, trackId);
    }
}
