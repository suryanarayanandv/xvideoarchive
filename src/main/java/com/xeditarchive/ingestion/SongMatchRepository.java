package com.xeditarchive.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SongMatchRepository extends JpaRepository<SongMatch, Long> {
    Optional<SongMatch> findByIngestionIdAndSongIdAndMatchSource(Long ingestionId, Long songId, String matchSource);
}
