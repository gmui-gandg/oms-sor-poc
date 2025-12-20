package com.oms.ingest.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA Entity for outbox_events table.
 * Implements the Transactional Outbox pattern for guaranteed Kafka delivery.
 */
@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", length = 50, nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", columnDefinition = "uuid", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "topic", length = 100, nullable = false)
    private String topic;

    @Column(name = "kafka_key", columnDefinition = "uuid")
    private java.util.UUID kafkaKey;

    @Column(name = "published", nullable = false)
    private Boolean published;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (published == null) {
            published = false;
        }
    }
}
