package com.oms.ingest.service;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oms.ingest.model.OutboxEvent;
import com.oms.ingest.repository.OutboxRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox Publisher - publishes unpublished events to Kafka.
 * Uses PostgreSQL LISTEN/NOTIFY for low-latency event detection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;

    @Value("${oms.ingest.outbox-publisher.enabled:true}")
    private boolean enabled;

    @Value("${oms.ingest.outbox-publisher.batch-size:100}")
    private int batchSize;

    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        if (!enabled) {
            log.info("Outbox publisher is disabled");
            return;
        }

        // Start PostgreSQL LISTEN thread
        Thread listenerThread = new Thread(this::listenForNotifications, "outbox-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        // Initial poll for any missed events
        publishPendingEvents();
    }

    private void listenForNotifications() {
        try (Connection conn = dataSource.getConnection()) {
            PGConnection pgConn = conn.unwrap(PGConnection.class);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("LISTEN outbox_channel");
                log.info("Listening for outbox notifications on channel: outbox_channel");
            }

            while (!Thread.interrupted()) {
                // Check for notifications (500ms timeout)
                PGNotification[] notifications = pgConn.getNotifications(500);

                if (notifications != null && notifications.length > 0) {
                    log.debug("Received {} outbox notifications", notifications.length);
                    publishPendingEvents();
                }
            }

        } catch (Exception e) {
            log.error("Error in LISTEN thread", e);
            // Re-start listening after delay
            try {
                Thread.sleep(5000);
                startListening();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findUnpublishedEvents(
                PageRequest.of(0, batchSize));

        if (events.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                        event.getTopic(),
                        event.getKafkaKey() != null ? event.getKafkaKey().toString() : null,
                        event.getPayload());

                future.thenAccept(result -> {
                    // Mark as published
                    outboxRepository.markAsPublished(event.getId(), Instant.now());
                    log.debug("Published event {} to topic {}", event.getId(), event.getTopic());

                    // Metrics
                    Counter.builder("oms.ingest.outbox.published")
                            .tag("topic", event.getTopic())
                            .register(meterRegistry)
                            .increment();

                }).exceptionally(ex -> {
                    log.error("Failed to publish event {}: {}", event.getId(), ex.getMessage());

                    Counter.builder("oms.ingest.outbox.failed")
                            .tag("topic", event.getTopic())
                            .register(meterRegistry)
                            .increment();

                    return null;
                });

            } catch (Exception e) {
                log.error("Error publishing event {}", event.getId(), e);
            }
        }
    }
}
