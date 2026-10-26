package com.xeditarchive.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live check: local permitted sample -> FFmpeg -> real AudD API -> persisted match.
 * <pre>
 * LIVE_PIPELINE_TEST=true AUDD_API_TOKEN=... LIVE_SAMPLE_FILE=/path/to/permitted.mp4 \
 *   LIVE_EXPECTED_ARTIST="Tears For Fears" mvn test -Dtest=LiveAuddPipelineTest
 * </pre>
 * Costs one AudD request. Never runs in the default build.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LIVE_PIPELINE_TEST", matches = "true")
class LiveAuddPipelineTest {

    private static final String POST_ID = "424242";
    private static final Path SAMPLE_DIR;
    private static final Path WORK_DIR;

    static {
        try {
            SAMPLE_DIR = Files.createTempDirectory("xea-live-samples");
            WORK_DIR = Files.createTempDirectory("xea-live-work");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ProcessingJobService processingJobService;

    @Autowired
    private SongMatchRepository songMatchRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        String sample = System.getenv("LIVE_SAMPLE_FILE");
        if (sample == null || sample.isBlank()) {
            throw new IllegalStateException("LIVE_SAMPLE_FILE must point to a media file you are permitted to use");
        }
        String name = Path.of(sample).getFileName().toString();
        Files.copy(Path.of(sample), SAMPLE_DIR.resolve(POST_ID + name.substring(name.lastIndexOf('.'))));

        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:live;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("recognition.audd.enabled", () -> "true");
        registry.add("recognition.audd.api-token", () -> System.getenv("AUDD_API_TOKEN"));
        registry.add("media.provider", () -> "local-sample");
        registry.add("media.local-sample.directory", SAMPLE_DIR::toString);
        registry.add("media.temp-dir", WORK_DIR::toString);
        registry.add("jobs.max-attempts", () -> "1");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void permittedSample_isRecognizedByAudd_andPersisted() throws Exception {
        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/sample/status/" + POST_ID);

        ProcessingJobResponse job = processingJobService.processForIngestion(ingestion.id());

        assertThat(job.status()).as("job status").isEqualTo("COMPLETED");
        SongMatch match = songMatchRepository.findAll().stream()
                .filter(m -> m.getIngestion().getId().equals(ingestion.id()))
                .findFirst().orElseThrow();
        System.out.printf("LIVE RESULT: %s - %s (source=%s, offset=%s)%n",
                match.getSong().getArtist(), match.getSong().getTitle(),
                match.getMatchSource(), match.getMatchStartSeconds());

        String expectedArtist = System.getenv("LIVE_EXPECTED_ARTIST");
        if (expectedArtist != null && !expectedArtist.isBlank()) {
            assertThat(match.getSong().getArtist()).isEqualToIgnoringCase(expectedArtist);
        }
        try (var entries = Files.list(WORK_DIR)) {
            assertThat(entries).as("temporary media must be deleted").isEmpty();
        }
    }
}
