package com.oms.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OMS Ingest Service - Fast order validation and persistence.
 * 
 * Responsibilities:
 * - Accept orders via REST and gRPC
 * - Basic validation (schema, required fields)
 * - Persist to PostgreSQL
 * - Publish to Kafka orders.inbound topic (via outbox pattern)
 * - Return fast ACK to client (<50ms target)
 */
@SpringBootApplication
@EnableScheduling
public class IngestApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
