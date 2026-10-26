package com.xeditarchive.ingestion;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Runs one ingestion job: claim (short transaction) -> fetch, extract, recognize (no transaction,
 * no database locks held during network or FFmpeg work) -> record the outcome (short transaction).
 * <p>
 * The claim is an atomic compare-and-set on the job status, so concurrent triggers for the same job
 * cannot both spend an X read or an AudD request. In-progress states (FETCHING, RECOGNIZING) are
 * committed and therefore visible to the status endpoints. A claim older than {@code jobs.claim-timeout}
 * is treated as abandoned (crashed worker) and can be taken over.
 */
@Service
public class ProcessingJobService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobService.class);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final List<ProcessingJobStatus> CLAIMABLE =
            List.of(ProcessingJobStatus.PENDING, ProcessingJobStatus.RETRY_WAIT);
    private static final List<ProcessingJobStatus> IN_PROGRESS =
            List.of(ProcessingJobStatus.FETCHING, ProcessingJobStatus.RECOGNIZING);

    private final ProcessingJobRepository processingJobRepository;
    private final IngestionRepository ingestionRepository;
    private final XPostMediaClient xPostMediaClient;
    private final AudioExtractionService audioExtractionService;
    private final SongRecognitionService songRecognitionService;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final Duration claimTimeout;

    public ProcessingJobService(ProcessingJobRepository processingJobRepository,
                                IngestionRepository ingestionRepository,
                                XPostMediaClient xPostMediaClient,
                                AudioExtractionService audioExtractionService,
                                SongRecognitionService songRecognitionService,
                                PlatformTransactionManager transactionManager,
                                @Value("${jobs.max-attempts:5}") int maxAttempts,
                                @Value("${jobs.claim-timeout:15m}") Duration claimTimeout) {
        this.processingJobRepository = processingJobRepository;
        this.ingestionRepository = ingestionRepository;
        this.xPostMediaClient = xPostMediaClient;
        this.audioExtractionService = audioExtractionService;
        this.songRecognitionService = songRecognitionService;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.claimTimeout = claimTimeout;
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

    public ProcessingJobResponse processForIngestion(Long ingestionId) {
        ProcessingJob job = findOrCreateJob(ingestionId);
        if (isTerminal(job.getStatus())) {
            return ProcessingJobResponse.from(job);
        }
        Long jobId = job.getId();

        // Credentials are checked before claiming, so a misconfigured provider neither spends
        // X API / AudD quota nor uses up the job's retries.
        String configurationProblem = configurationProblem();
        if (configurationProblem != null) {
            return parkUnclaimed(jobId, ingestionId, configurationProblem);
        }

        if (!claim(jobId, ingestionId)) {
            // Another worker holds the job, or it finished in the meantime: report its current state.
            return current(jobId);
        }
        return runClaimed(jobId, ingestionId);
    }

    // --- phase 1: claim -------------------------------------------------------------------------

    private ProcessingJob findOrCreateJob(Long ingestionId) {
        try {
            return tx.execute(status -> {
                ingestionRepository.findById(ingestionId)
                        .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));
                return processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(ingestionId)
                        .orElseGet(() -> processingJobRepository.save(new ProcessingJob(ingestionId, "INGESTION")));
            });
        } catch (DataIntegrityViolationException e) {
            // A concurrent request created the job first (unique ingestion_id + job_type).
            return processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(ingestionId).orElseThrow(() -> e);
        }
    }

    private String configurationProblem() {
        try {
            songRecognitionService.verifyConfigured();
            xPostMediaClient.verifyConfigured();
            return null;
        } catch (RecognitionNotConfiguredException | MediaProviderNotConfiguredException e) {
            return e.getMessage();
        }
    }

    private boolean claim(Long jobId, Long ingestionId) {
        Boolean claimed = tx.execute(status -> {
            Instant now = Instant.now();
            int updated = processingJobRepository.claim(jobId, CLAIMABLE, IN_PROGRESS,
                    ProcessingJobStatus.FETCHING, now.minus(claimTimeout), now);
            if (updated == 1) {
                updateIngestionStatus(ingestionId, IngestionStatus.FETCHING);
            }
            return updated == 1;
        });
        return Boolean.TRUE.equals(claimed);
    }

    // --- phase 2: work outside any transaction --------------------------------------------------

    private ProcessingJobResponse runClaimed(Long jobId, Long ingestionId) {
        Ingestion ingestion = ingestionRepository.findById(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));

        PostMediaFetchResult media;
        try {
            media = xPostMediaClient.fetchMedia(ingestion);
        } catch (RuntimeException e) {
            log.warn("Media provider threw for ingestion {}", ingestionId, e);
            return complete(jobId, ingestionId, ProcessingJobStatus.FETCHING,
                    Completion.retry("Media provider error: " + e.getMessage()));
        }
        if (media == null) {
            return complete(jobId, ingestionId, ProcessingJobStatus.FETCHING,
                    Completion.terminal(ProcessingJobStatus.FAILED, "Media provider returned no result"));
        }
        switch (media.outcome()) {
            case NOT_CONFIGURED:
                return complete(jobId, ingestionId, ProcessingJobStatus.FETCHING, Completion.park(media.reason()));
            case TRANSIENT_FAILURE:
                return complete(jobId, ingestionId, ProcessingJobStatus.FETCHING, Completion.retry(media.reason()));
            case UNAVAILABLE:
                return complete(jobId, ingestionId, ProcessingJobStatus.FETCHING,
                        Completion.terminal(ProcessingJobStatus.MEDIA_UNAVAILABLE, media.reason()));
            default:
                break;
        }
        if (!media.mediaAvailable()) {
            return complete(jobId, ingestionId, ProcessingJobStatus.FETCHING,
                    Completion.terminal(ProcessingJobStatus.MEDIA_UNAVAILABLE, "Media provider returned no file"));
        }

        // The workspace (downloaded media, extracted audio, logs) is deleted whatever happens below.
        try (FetchedMedia fetched = media.media()) {
            if (!transition(jobId, ingestionId, ProcessingJobStatus.FETCHING, ProcessingJobStatus.RECOGNIZING)) {
                log.warn("Lost the claim on job {} before recognition; abandoning this run", jobId);
                return current(jobId);
            }
            Completion completion = recognize(ingestionId, fetched);
            return complete(jobId, ingestionId, ProcessingJobStatus.RECOGNIZING, completion);
        }
    }

    private Completion recognize(Long ingestionId, FetchedMedia fetched) {
        try {
            Path samplePath = fetched.workspace().resolve("sample.wav");
            AudioExtractionResult extraction = audioExtractionService.extractSample(fetched.file(), samplePath);
            if (!extraction.success()) {
                // Bad or audio-less media is a property of the post, not a transient condition.
                return Completion.terminal(ProcessingJobStatus.MEDIA_UNAVAILABLE, extraction.reason());
            }

            SongRecognitionResult recognition = songRecognitionService.recognize(extraction.outputPath());
            if (recognition == null || !recognition.matched()) {
                return Completion.terminal(ProcessingJobStatus.NO_MATCH,
                        recognition == null ? "No recognition result returned" : recognition.reason());
            }
            return Completion.matched(recognition);
        } catch (RecognitionNotConfiguredException e) {
            log.warn("Recognition is not usable for ingestion {}: {}", ingestionId, e.getMessage());
            return Completion.park(e.getMessage());
        } catch (UnusableAudioException e) {
            return Completion.terminal(ProcessingJobStatus.MEDIA_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Recognition failed for ingestion {}: {}", ingestionId, e.getMessage());
            return Completion.retry("Recognition provider error: " + e.getMessage());
        }
    }

    private boolean transition(Long jobId, Long ingestionId, ProcessingJobStatus from, ProcessingJobStatus to) {
        Boolean moved = tx.execute(status -> {
            int updated = processingJobRepository.transition(jobId, from, to, Instant.now());
            if (updated == 1) {
                updateIngestionStatus(ingestionId, IngestionStatus.valueOf(to.name()));
            }
            return updated == 1;
        });
        return Boolean.TRUE.equals(moved);
    }

    // --- phase 3: record the outcome ------------------------------------------------------------

    private enum Kind { FINAL, RETRY, PARK }

    private record Completion(Kind kind, ProcessingJobStatus status, String reason, SongRecognitionResult match) {
        static Completion terminal(ProcessingJobStatus status, String reason) {
            return new Completion(Kind.FINAL, status, reason, null);
        }

        static Completion matched(SongRecognitionResult match) {
            return new Completion(Kind.FINAL, ProcessingJobStatus.COMPLETED, null, match);
        }

        static Completion retry(String reason) {
            return new Completion(Kind.RETRY, ProcessingJobStatus.RETRY_WAIT, reason, null);
        }

        /** Operator-side problem (credentials): wait without consuming the retry budget. */
        static Completion park(String reason) {
            return new Completion(Kind.PARK, ProcessingJobStatus.RETRY_WAIT, reason, null);
        }
    }

    private ProcessingJobResponse complete(Long jobId, Long ingestionId, ProcessingJobStatus heldStatus,
                                           Completion completion) {
        try {
            return tx.execute(status -> applyCompletion(jobId, ingestionId, heldStatus, completion));
        } catch (RuntimeException e) {
            if (completion.match() == null) {
                throw e;
            }
            // Saving the match failed (e.g. a concurrent insert of the same song): retry the job later.
            log.warn("Could not save the match for ingestion {}", ingestionId, e);
            return tx.execute(status -> applyCompletion(jobId, ingestionId, heldStatus,
                    Completion.retry("Could not save match: " + e.getMessage())));
        }
    }

    private ProcessingJobResponse applyCompletion(Long jobId, Long ingestionId, ProcessingJobStatus heldStatus,
                                                  Completion completion) {
        ProcessingJob job = processingJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Processing job not found: " + jobId));
        if (job.getStatus() != heldStatus) {
            // Our claim expired and another worker took over; its result wins.
            log.warn("Job {} is {} (expected {}); discarding this run's result", jobId, job.getStatus(), heldStatus);
            return ProcessingJobResponse.from(job);
        }
        Ingestion ingestion = ingestionRepository.findById(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));

        switch (completion.kind()) {
            case PARK -> {
                job.refundAttempt();
                job.setNextAttemptAt(Instant.now().plus(MAX_BACKOFF));
                return record(job, ingestion, ProcessingJobStatus.RETRY_WAIT, completion.reason());
            }
            case RETRY -> {
                if (job.getAttempts() >= maxAttempts) {
                    return record(job, ingestion, ProcessingJobStatus.FAILED,
                            "Retry limit (" + maxAttempts + ") reached. Last error: " + completion.reason());
                }
                job.setNextAttemptAt(Instant.now().plus(backoff(job.getAttempts())));
                return record(job, ingestion, ProcessingJobStatus.RETRY_WAIT, completion.reason());
            }
            default -> {
                if (completion.match() != null) {
                    songRecognitionService.persistMatch(ingestion, completion.match());
                }
                return record(job, ingestion, completion.status(), completion.reason());
            }
        }
    }

    /** Parks a job that was never claimed (configuration missing); leaves in-progress jobs alone. */
    private ProcessingJobResponse parkUnclaimed(Long jobId, Long ingestionId, String reason) {
        return tx.execute(status -> {
            ProcessingJob job = processingJobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Processing job not found: " + jobId));
            if (!CLAIMABLE.contains(job.getStatus())) {
                return ProcessingJobResponse.from(job);
            }
            Ingestion ingestion = ingestionRepository.findById(ingestionId)
                    .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));
            job.setNextAttemptAt(Instant.now().plus(MAX_BACKOFF));
            return record(job, ingestion, ProcessingJobStatus.RETRY_WAIT, reason);
        });
    }

    private ProcessingJobResponse record(ProcessingJob job, Ingestion ingestion,
                                         ProcessingJobStatus status, String reason) {
        job.setStatus(status);
        job.setLastError(truncate(reason));
        processingJobRepository.save(job);

        ingestion.setStatus(IngestionStatus.valueOf(status.name()));
        ingestionRepository.save(ingestion);
        return ProcessingJobResponse.from(job);
    }

    private void updateIngestionStatus(Long ingestionId, IngestionStatus status) {
        ingestionRepository.findById(ingestionId).ifPresent(ingestion -> {
            ingestion.setStatus(status);
            ingestionRepository.save(ingestion);
        });
    }

    private ProcessingJobResponse current(Long jobId) {
        return getById(jobId);
    }

    private static Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration backoff = BASE_BACKOFF.multipliedBy(multiplier);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private static boolean isTerminal(ProcessingJobStatus status) {
        return status == ProcessingJobStatus.COMPLETED
                || status == ProcessingJobStatus.MEDIA_UNAVAILABLE
                || status == ProcessingJobStatus.NO_MATCH
                || status == ProcessingJobStatus.FAILED;
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
