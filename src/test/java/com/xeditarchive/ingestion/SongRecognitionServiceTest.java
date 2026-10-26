package com.xeditarchive.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SongRecognitionServiceTest {

    @Autowired
    private SongRecognitionService songRecognitionService;

    @Autowired
    private SongMatchRepository songMatchRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private IngestionRepository ingestionRepository;

    @Test
    void persistMatch_savesRecognizedSongMetadata() {
        Ingestion ingestion = ingestionRepository.save(new Ingestion("x", "song-123", "https://x.com/example/status/123"));
        long matchesBefore = songMatchRepository.count();

        SongRecognitionResult result = new SongRecognitionResult(
                true,
                "Midnight City",
                "M83",
                "Hurry Up, We're Dreaming",
                "track-42",
                "audd",
                0.94,
                12.5,
                null
        );

        SongMatch match = songRecognitionService.persistMatch(ingestion, result);

        assertThat(match).isNotNull();
        assertThat(match.getSong().getTitle()).isEqualTo("Midnight City");
        assertThat(match.getSong().getArtist()).isEqualTo("M83");
        assertThat(match.getMatchSource()).isEqualTo("audd");
        assertThat(songMatchRepository.count()).isEqualTo(matchesBefore + 1);
    }

    @Test
    void persistMatch_withoutProviderId_reusesOneSong_acrossPosts() {
        Ingestion first = ingestionRepository.save(new Ingestion("x", "501", "https://x.com/a/status/501"));
        Ingestion second = ingestionRepository.save(new Ingestion("x", "502", "https://x.com/a/status/502"));
        long songsBefore = songRepository.count();

        SongMatch a = songRecognitionService.persistMatch(first,
                SongRecognitionResult.match("Everybody Wants To Rule The World", "Tears For Fears", null, null, "audd", null, 1.0));
        SongMatch b = songRecognitionService.persistMatch(second,
                SongRecognitionResult.match("  everybody wants to rule the world ", "TEARS FOR FEARS", null, null, "audd", null, 2.0));

        assertThat(songRepository.count()).isEqualTo(songsBefore + 1);
        assertThat(b.getSong().getId()).isEqualTo(a.getSong().getId());
        assertThat(a.getSong().getExternalTrackId()).isEqualTo("meta:tears for fears|everybody wants to rule the world");
    }

    @Test
    void persistMatch_isIdempotentForTheSamePostAndSong() {
        Ingestion ingestion = ingestionRepository.save(new Ingestion("x", "503", "https://x.com/a/status/503"));
        SongRecognitionResult result = SongRecognitionResult.match("Song", "Artist", null, "isrc:US0000000001", "audd", null, 0.0);
        long matchesBefore = songMatchRepository.count();

        SongMatch first = songRecognitionService.persistMatch(ingestion, result);
        SongMatch again = songRecognitionService.persistMatch(ingestion, result);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(songMatchRepository.count()).isEqualTo(matchesBefore + 1);
    }
}
