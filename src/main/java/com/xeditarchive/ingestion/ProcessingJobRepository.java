package com.xeditarchive.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    Optional<ProcessingJob> findTopByIngestionIdOrderByCreatedAtDesc(Long ingestionId);
}
