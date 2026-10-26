package com.xeditarchive.ingestion;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class ProcessingJobService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobService.class);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final ProcessingJobRepository processingJobRepository;
    private final IngestionRepository ingestionRepository;
    private final XPostMediaClient xPostMediaClient;
    private final AudioExtractionService audioExtractionService;
    private final SongRecognitionService songRecognitionService;
    private final int maxAttempts;

    public ProcessingJobService(ProcessingJobRepository processingJobRepository,
                                IngestionRepository ingestionRepository,
                                XPostMediaClient xPostMediaClient,
                                AudioExtractionService audioExtractionService,
                                SongRecognitionService songRecognitionService,
                                @Value("${jobs.max-attempts:5}") int maxAttempts) {
        this.processingJobRepository = processingJobRepository;
        this.ingestionRepository = ingestionRepository;
        this.xPostMediaClient = xPostMediaClient;
        this.audioExtractionService = audioExtractionService;
        this.songRecognitionService = songRecognitionService;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Transactional
    public ProcessingJobResponse createForIngestion(Long ingestionId) {
        Optional<ProcessingJob> existingJob = processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(ingestionId);
        if (existingJob.isPresent()) {
            return ProcessingJobResponse.from(existingJob.get());
        }

        ProcessingJob job = new ProcessingJob(ingestionId, "INGESTION");
        ProcessingJob saved = processingJobRepository.save(job);
        return ProcessingJobResponse.from(saved);
    }

    @Transactional
    public ProcessingJobResponse processForIngestion(Long ingestionId) {
        Ingestion ingestion = ingestionRepository.findById(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));

        ProcessingJob job = processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(ingestionId)
                .orElseGet(() -> processingJobRepository.save(new ProcessingJob(ingestionId, "INGESTION")));

        if (isTerminal(job.getStatus())) {
            return ProcessingJobResponse.from(job);
        }

        // Check the recognizer before fetching, so a missing token never spends X API credits.
        try {
            songRecognitionService.verifyConfigured();
        } catch (RecognitionNotConfiguredException e) {
            return waitForConfiguration(job, ingestion, e.getMessage());
        }

        job.setStatus(ProcessingJobStatus.FETCHING);
        job.incrementAttempts();
        processingJobRepository.save(job);

        PostMediaFetchResult media = xPostMediaClient.fetchMedia(ingestion);
        if (media == null) {
            return finish(job, ingestion, ProcessingJobStatus.FAILED, "Media provider returned no result");
        }
        if (media.outcome() == PostMediaFetchResult.Outcome.TRANSIENT_FAILURE) {
            return retryOrFail(job, ingestion, media.reason());
        }
        if (!media.mediaAvailable()) {
            return finish(job, ingestion, ProcessingJobStatus.MEDIA_UNAVAILABLE, media.reason());
        }

        // The workspace (downloaded media, extracted audio, logs) is deleted whatever happens below.
        try (FetchedMedia fetched = media.media()) {
            job.setStatus(ProcessingJobStatus.RECOGNIZING);
            processingJobRepository.save(job);

            Path samplePath = fetched.workspace().resolve("sample.wav");
            AudioExtractionResult extraction = audioExtractionService.extractSample(fetched.file(), samplePath);
            if (!extraction.success()) {
                // Bad or audio-less media is a property of the post, not a transient condition.
                return finish(job, ingestion, ProcessingJobStatus.MEDIA_UNAVAILABLE, extraction.reason());
            }

            SongRecognitionResult recognition;
            try {
                recognition = songRecognitionService.recognize(extraction.outputPath());
            } catch (RecognitionNotConfiguredException e) {
                log.warn("Recognition is not usable for ingestion {}: {}", ingestionId, e.getMessage());
                job.refundAttempt();
                return waitForConfiguration(job, ingestion, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Recognition provider failed for ingestion {}: {}", ingestionId, e.getMessage());
                return retryOrFail(job, ingestion, "Recognition provider error: " + e.getMessage());
            }

            if (recognition == null || !recognition.matched()) {
                return finish(job, ingestion, ProcessingJobStatus.NO_MATCH,
                        recognition == null ? "No recognition result returned" : recognition.reason());
            }

            songRecognitionService.persistMatch(ingestion, recognition);
            return finish(job, ingestion, ProcessingJobStatus.COMPLETED, null);
        }
    }

    private static boolean isTerminal(ProcessingJobStatus status) {
        return status == ProcessingJobStatus.COMPLETED
                || status == ProcessingJobStatus.MEDIA_UNAVAILABLE
                || status == ProcessingJobStatus.NO_MATCH
                || status == ProcessingJobStatus.FAILED;
    }

    private ProcessingJobResponse retryOrFail(ProcessingJob job, Ingestion ingestion, String reason) {
        if (job.getAttempts() >= maxAttempts) {
            return finish(job, ingestion, ProcessingJobStatus.FAILED,
                    "Retry limit (" + maxAttempts + ") reached. Last error: " + reason);
        }
        long multiplier = 1L << Math.min(job.getAttempts() - 1, 20);
        Duration backoff = BASE_BACKOFF.multipliedBy(multiplier);
        if (backoff.compareTo(MAX_BACKOFF) > 0) {
            backoff = MAX_BACKOFF;
        }
        job.setNextAttemptAt(Instant.now().plus(backoff));
        return finish(job, ingestion, ProcessingJobStatus.RETRY_WAIT, reason);
    }

    /**
     * Credentials are an operator problem, not a property of the post: park the job without
     * consuming its retry budget, so it can run normally once the token is fixed.
     */
    private ProcessingJobResponse waitForConfiguration(ProcessingJob job, Ingestion ingestion, String reason) {
        job.setNextAttemptAt(Instant.now().plus(MAX_BACKOFF));
        return finish(job, ingestion, ProcessingJobStatus.RETRY_WAIT, reason);
    }

    private ProcessingJobResponse finish(ProcessingJob job, Ingestion ingestion,
                                         ProcessingJobStatus status, String reason) {
        job.setStatus(status);
        job.setLastError(truncate(reason));
        processingJobRepository.save(job);

        ingestion.setStatus(IngestionStatus.valueOf(status.name()));
        ingestionRepository.save(ingestion);
        return ProcessingJobResponse.from(job);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        // last_error is a default VARCHAR(255) column.
        return value.length() > 255 ? value.substring(0, 252) + "..." : value;
    }

    public ProcessingJobResponse getById(Long id) {
        return processingJobRepository.findById(id)
                .map(ProcessingJobResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Processing job not found: " + id));
    }

    public ProcessingJobResponse getByIngestionId(Long ingestionId) {
        return processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(ingestionId)
                .map(ProcessingJobResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Processing job not found for ingestion: " + ingestionId));
    }
}
