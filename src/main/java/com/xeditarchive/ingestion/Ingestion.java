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
        name = "ingestions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ingestion_platform_post_id", columnNames = {"platform", "platform_post_id"}),
                @UniqueConstraint(name = "uk_ingestion_post_url", columnNames = {"post_url"})
        }
)
public class Ingestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String platform = "x";

    @Column(name = "platform_post_id", nullable = false)
    private String platformPostId;

    @Column(name = "post_url", nullable = false)
    private String postUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionStatus status = IngestionStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Ingestion() {
    }

    public Ingestion(String platform, String platformPostId, String postUrl) {
        this.platform = platform;
        this.platformPostId = platformPostId;
        this.postUrl = postUrl;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPlatform() {
        return platform;
    }

    public String getPlatformPostId() {
        return platformPostId;
    }

    public String getPostUrl() {
        return postUrl;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public void setStatus(IngestionStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
