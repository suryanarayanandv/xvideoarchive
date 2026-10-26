package com.xeditarchive.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Job claiming and transaction boundaries: one worker per job, in-progress states visible while the
 * worker is doing network work, and media-provider credential problems never using up retries.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProcessingJobClaimTest {

    @MockitoBean
    private XPostMediaClient mediaClient;

    @MockitoBean
    private SongRecognitionClient recognitionClient;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ProcessingJobService processingJobService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private IngestionRepository ingestionRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:claims;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("jobs.max-attempts", () -> "2");
    }

    @Test
    void concurrentTriggers_fetchOnce_andProgressIsVisibleWhileFetching() throws Exception {
        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/8101");
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        when(mediaClient.fetchMedia(any())).thenAnswer(invocation -> {
            fetches.incrementAndGet();
            fetching.countDown();
            release.await(10, TimeUnit.SECONDS);
            return PostMediaFetchResult.unavailable("Post has no video or animated GIF attachment.");
        });

        CompletableFuture<ProcessingJobResponse> first =
                CompletableFuture.supplyAsync(() -> processingJobService.processForIngestion(ingestion.id()));
        assertThat(fetching.await(10, TimeUnit.SECONDS)).isTrue();

        // Committed in-progress state is visible to other readers while the worker holds no transaction.
        assertThat(processingJobService.getByIngestionId(ingestion.id()).status()).isEqualTo("FETCHING");
        assertThat(ingestionRepository.findById(ingestion.id()).orElseThrow().getStatus())
                .isEqualTo(IngestionStatus.FETCHING);

        // A second trigger cannot claim the job; it reports the current state without fetching.
        ProcessingJobResponse second = processingJobService.processForIngestion(ingestion.id());
        assertThat(second.status()).isEqualTo("FETCHING");

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("MEDIA_UNAVAILABLE");
        assertThat(fetches.get()).isEqualTo(1);
        assertThat(processingJobRepository.findById(second.id()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void missingXToken_parksBeforeFetching_withoutUsingRetries() {
        doThrow(new MediaProviderNotConfiguredException("X_BEARER_TOKEN is not configured."))
                .when(mediaClient).verifyConfigured();
        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/8102");

        for (int i = 0; i < 4; i++) {
            assertThat(processingJobService.processForIngestion(ingestion.id()).status()).isEqualTo("RETRY_WAIT");
        }

        ProcessingJob job = processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(ingestion.id()).orElseThrow();
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).contains("X_BEARER_TOKEN");
        verify(mediaClient, never()).fetchMedia(any());
    }

    @Test
    void rejectedXCredentials_refundAttempt_andNeverFail() {
        when(mediaClient.fetchMedia(any()))
                .thenReturn(PostMediaFetchResult.notConfigured("X API returned HTTP 401 (check X_BEARER_TOKEN)"));
        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/8103");

        for (int i = 0; i < 4; i++) {
            assertThat(processingJobService.processForIngestion(ingestion.id()).status()).isEqualTo("RETRY_WAIT");
        }

        ProcessingJob job = processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(ingestion.id()).orElseThrow();
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).contains("HTTP 401");
    }

    @Test
    void mediaProviderException_isRetried_notLeftInFetching() {
        when(mediaClient.fetchMedia(any())).thenThrow(new IllegalStateException("boom"));
        IngestionResponse ingestion = ingestionService.createIngestion("https://x.com/someone/status/8104");

        assertThat(processingJobService.processForIngestion(ingestion.id()).status()).isEqualTo("RETRY_WAIT");
        assertThat(processingJobService.processForIngestion(ingestion.id()).status()).isEqualTo("FAILED");
    }
}
