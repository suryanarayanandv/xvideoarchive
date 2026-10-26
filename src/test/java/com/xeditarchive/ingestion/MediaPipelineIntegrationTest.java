package com.xeditarchive.ingestion;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Runs the real worker path: local permitted sample -> per-job workspace -> FFmpeg -> recognition client
 * -> persisted match -> workspace cleanup. Only the recognition provider is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaPipelineIntegrationTest {

    private static final Path sampleDir;
    private static final Path workDir;

    static {
        try {
            sampleDir = Files.createTempDirectory("xea-samples");
            workDir = Files.createTempDirectory("xea-work");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @MockitoBean
    private SongRecognitionClient recognitionClient;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ProcessingJobService processingJobService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SongMatchRepository songMatchRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:pipeline;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("media.provider", () -> "local-sample");
        registry.add("media.local-sample.directory", sampleDir::toString);
        registry.add("media.temp-dir", workDir::toString);
        registry.add("jobs.max-attempts", () -> "2");
    }

    @BeforeAll
    static void createSample() throws Exception {
        assumeTrue(AudioExtractionServiceTest.ffmpegAvailable(), "ffmpeg not installed");
        TestMedia.generateVideoWithTone(sampleDir.resolve("9001.mp4"));
        Files.copy(sampleDir.resolve("9001.mp4"), sampleDir.resolve("9002.mp4"));
        Files.writeString(sampleDir.resolve("9003.mp4"), "corrupt");
    }

    @BeforeEach
    void workspaceStartsEmpty() throws Exception {
        try (var entries = Files.list(workDir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void permittedSample_flowsThroughFfmpegAndRecognition_toPersistedMatch() throws Exception {
        AtomicReference<Path> seenAudio = new AtomicReference<>();
        when(recognitionClient.recognize(any())).thenAnswer(invocation -> {
            Path audio = invocation.getArgument(0);
            seenAudio.set(audio);
            assertThat(audio).exists().startsWith(workDir);
            assertThat(Files.size(audio)).isGreaterThan(44);
            return SongRecognitionResult.match("Test Tone", "Synth", null, "tone-440", "audd", null, 0.0);
        });
        long matchesBefore = songMatchRepository.count();

        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/9001");
        ProcessingJobResponse job = processingJobService.processForIngestion(ingestion.id());

        assertThat(job.status()).isEqualTo("COMPLETED");
        assertThat(songMatchRepository.count()).isEqualTo(matchesBefore + 1);
        assertThat(seenAudio.get()).doesNotExist();
        try (var entries = Files.list(workDir)) {
            assertThat(entries).as("temporary media must be deleted").isEmpty();
        }
        assertThat(sampleDir.resolve("9001.mp4")).as("sample library must be untouched").exists();
    }

    @Test
    void providerError_schedulesRetry_thenFailsAtLimit_andAlwaysCleansUp() throws Exception {
        when(recognitionClient.recognize(any())).thenThrow(new IllegalStateException("AudD recognition failed: quota"));

        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/9002");

        ProcessingJobResponse first = processingJobService.processForIngestion(ingestion.id());
        assertThat(first.status()).isEqualTo("RETRY_WAIT");
        ProcessingJob stored = processingJobRepository.findById(first.id()).orElseThrow();
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getNextAttemptAt()).isAfter(stored.getUpdatedAt().minusSeconds(1));
        assertThat(stored.getLastError()).contains("quota");

        ProcessingJobResponse second = processingJobService.processForIngestion(ingestion.id());
        assertThat(second.status()).isEqualTo("FAILED");
        assertThat(processingJobRepository.findById(second.id()).orElseThrow().getLastError())
                .contains("Retry limit (2)");

        try (var entries = Files.list(workDir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void corruptMedia_isMediaUnavailable_withoutCallingRecognition() throws Exception {
        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/9003");

        ProcessingJobResponse job = processingJobService.processForIngestion(ingestion.id());

        assertThat(job.status()).isEqualTo("MEDIA_UNAVAILABLE");
        assertThat(processingJobRepository.findById(job.id()).orElseThrow().getLastError())
                .startsWith("ffmpeg extraction failed");
        org.mockito.Mockito.verifyNoInteractions(recognitionClient);
        try (var entries = Files.list(workDir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void missingSample_isMediaUnavailable() {
        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/9999");

        assertThat(processingJobService.processForIngestion(ingestion.id()).status())
                .isEqualTo("MEDIA_UNAVAILABLE");
    }
}
