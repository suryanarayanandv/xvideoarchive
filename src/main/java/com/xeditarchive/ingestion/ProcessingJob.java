package com.xeditarchive.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "processing_jobs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_processing_job_ingestion_type", columnNames = {"ingestion_id", "job_type"})
        }
)
public class ProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ingestion_id", nullable = false)
    private Long ingestionId;

    @Column(name = "job_type", nullable = false)
    private String jobType = "INGESTION";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessingJobStatus status = ProcessingJobStatus.PENDING;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ProcessingJob() {
    }

    public ProcessingJob(Long ingestionId, String jobType) {
        this.ingestionId = ingestionId;
        this.jobType = jobType;
        this.nextAttemptAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getIngestionId() {
        return ingestionId;
    }

    public String getJobType() {
        return jobType;
    }

    public ProcessingJobStatus getStatus() {
        return status;
    }

    public void setStatus(ProcessingJobStatus status) {
        this.status = status;
        if (status == ProcessingJobStatus.COMPLETED || status == ProcessingJobStatus.NO_MATCH || status == ProcessingJobStatus.MEDIA_UNAVAILABLE || status == ProcessingJobStatus.FAILED || status == ProcessingJobStatus.NEEDS_REVIEW) {
            this.completedAt = Instant.now();
        }
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts += 1;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
