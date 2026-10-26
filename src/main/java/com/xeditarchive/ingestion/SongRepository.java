package com.xeditarchive.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SongRepository extends JpaRepository<Song, Long> {
    Optional<Song> findByExternalProviderAndExternalTrackId(String externalProvider, String externalTrackId);
}
