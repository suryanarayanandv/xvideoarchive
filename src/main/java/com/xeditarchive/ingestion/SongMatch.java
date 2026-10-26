package com.xeditarchive.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "song_matches",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_song_match_ingestion_song_source", columnNames = {"ingestion_id", "song_id", "match_source"})
        }
)
public class SongMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingestion_id", nullable = false)
    private Ingestion ingestion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id", nullable = false)
    private Song song;

    @Column(name = "match_source", nullable = false)
    private String matchSource;

    @Column
    private Double confidence;

    @Column(name = "match_start_seconds")
    private Double matchStartSeconds;

    @Column(name = "is_confirmed", nullable = false)
    private boolean isConfirmed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SongMatch() {
    }

    public SongMatch(Ingestion ingestion, Song song, String matchSource,
                    Double confidence, Double matchStartSeconds, boolean isConfirmed) {
        this.ingestion = ingestion;
        this.song = song;
        this.matchSource = matchSource;
        this.confidence = confidence;
        this.matchStartSeconds = matchStartSeconds;
        this.isConfirmed = isConfirmed;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public Song getSong() {
        return song;
    }

    public String getMatchSource() {
        return matchSource;
    }

    public Double getConfidence() {
        return confidence;
    }

    public Double getMatchStartSeconds() {
        return matchStartSeconds;
    }

    public boolean isConfirmed() {
        return isConfirmed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
