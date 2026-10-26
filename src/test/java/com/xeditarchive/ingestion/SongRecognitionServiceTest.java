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
    private IngestionRepository ingestionRepository;

    @Test
    void persistMatch_savesRecognizedSongMetadata() {
        Ingestion ingestion = ingestionRepository.save(new Ingestion("x", "song-123", "https://x.com/example/status/123"));

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
        assertThat(songMatchRepository.count()).isEqualTo(1);
    }
}
