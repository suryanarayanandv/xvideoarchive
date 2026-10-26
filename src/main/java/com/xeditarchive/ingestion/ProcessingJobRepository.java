package com.xeditarchive.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    Optional<ProcessingJob> findTopByIngestionIdOrderByCreatedAtDesc(Long ingestionId);

    /**
     * Atomically claims a job for one worker: succeeds (returns 1) only if the job is claimable, or is
     * in progress but its claim is older than {@code staleBefore} (the previous worker died).
     * Concurrent callers race on the row lock; exactly one sees 1, the others 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProcessingJob j
               set j.status = :claimedStatus, j.attempts = j.attempts + 1, j.updatedAt = :now
             where j.id = :id
               and (j.status in :claimable or (j.status in :inProgress and j.updatedAt < :staleBefore))
            """)
    int claim(@Param("id") Long id,
              @Param("claimable") Collection<ProcessingJobStatus> claimable,
              @Param("inProgress") Collection<ProcessingJobStatus> inProgress,
              @Param("claimedStatus") ProcessingJobStatus claimedStatus,
              @Param("staleBefore") Instant staleBefore,
              @Param("now") Instant now);

    /** Moves a claimed job between in-progress states; also refreshes the claim's timestamp. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProcessingJob j set j.status = :to, j.updatedAt = :now where j.id = :id and j.status = :from")
    int transition(@Param("id") Long id,
                   @Param("from") ProcessingJobStatus from,
                   @Param("to") ProcessingJobStatus to,
                   @Param("now") Instant now);
}
