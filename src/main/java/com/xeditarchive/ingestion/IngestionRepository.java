package com.xeditarchive.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IngestionRepository extends JpaRepository<Ingestion, Long> {
    Optional<Ingestion> findByPlatformAndPlatformPostId(String platform, String platformPostId);

    Optional<Ingestion> findByPostUrl(String postUrl);
}
