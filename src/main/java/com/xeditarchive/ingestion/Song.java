package com.xeditarchive.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "songs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_song_provider_track", columnNames = {"external_provider", "external_track_id"})
        }
)
public class Song {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String artist;

    private String album;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "external_provider")
    private String externalProvider;

    @Column(name = "external_track_id")
    private String externalTrackId;

    @Column(name = "normalized_title", nullable = false)
    private String normalizedTitle;

    @Column(name = "normalized_artist")
    private String normalizedArtist;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Song() {
    }

    public Song(String title, String artist, String album, Integer durationSeconds,
                String externalProvider, String externalTrackId) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationSeconds = durationSeconds;
        this.externalProvider = externalProvider;
        this.externalTrackId = externalTrackId;
        this.normalizedTitle = normalizeValue(title);
        this.normalizedArtist = normalizeValue(artist);
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.normalizedTitle == null) {
            this.normalizedTitle = normalizeValue(this.title);
        }
        if (this.normalizedArtist == null) {
            this.normalizedArtist = normalizeValue(this.artist);
        }
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public String getExternalProvider() {
        return externalProvider;
    }

    public String getExternalTrackId() {
        return externalTrackId;
    }

    public String getNormalizedTitle() {
        return normalizedTitle;
    }

    public String getNormalizedArtist() {
        return normalizedArtist;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String normalizeValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase();
    }
}
