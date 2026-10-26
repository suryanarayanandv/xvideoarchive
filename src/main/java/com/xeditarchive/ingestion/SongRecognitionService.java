package com.xeditarchive.ingestion;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
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

    public void verifyConfigured() {
        songRecognitionClient.verifyConfigured();
    }

    /**
     * Idempotent: the same recording maps to one {@link Song}, and re-persisting the same match for a
     * post returns the existing row instead of inserting a duplicate.
     */
    @Transactional
    public SongMatch persistMatch(Ingestion ingestion, SongRecognitionResult result) {
        if (result == null || !result.matched()) {
            throw new IllegalArgumentException("A recognized song result is required to persist a match.");
        }

        String provider = result.externalProvider() == null ? "unknown" : result.externalProvider();
        String trackId = result.externalTrackId() != null && !result.externalTrackId().isBlank()
                ? result.externalTrackId()
                : metadataTrackId(result.title(), result.artist());

        Song song = songRepository.findByExternalProviderAndExternalTrackId(provider, trackId)
                .orElseGet(() -> songRepository.save(new Song(
                        result.title(),
                        result.artist(),
                        result.album(),
                        null,
                        provider,
                        trackId
                )));

        Optional<SongMatch> existing = songMatchRepository
                .findByIngestionIdAndSongIdAndMatchSource(ingestion.getId(), song.getId(), provider);
        if (existing.isPresent()) {
            return existing.get();
        }

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

    /** Deterministic fallback ID so matches without a provider ID still collapse onto one song. */
    static String metadataTrackId(String title, String artist) {
        String id = "meta:" + normalize(artist) + "|" + normalize(title);
        // external_track_id is a default VARCHAR(255) column.
        return id.length() > 255 ? id.substring(0, 255) : id;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    public Optional<Song> findSongByProviderAndTrack(String provider, String trackId) {
        return songRepository.findByExternalProviderAndExternalTrackId(provider, trackId);
    }
}
