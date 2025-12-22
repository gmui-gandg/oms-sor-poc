package com.oms.validator.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oms.validator.config.ValidatorProperties;
import com.oms.validator.service.OrderValidationService.ValidationResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka consumer for order.ingest topic
 * Consumes orders, validates them, and publishes results
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderConsumer {

    private final OrderValidationService validationService;
    private final OrderPublisher orderPublisher;
    private final ValidatorProperties properties;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
        topics = "${oms.validator.topics.ingest}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrder(
            @Payload String orderJson,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("Consuming order from partition {} offset {}: key={}", partition, offset, key);

        try {
            // Validate order
            ValidationResult result = validationService.validateOrder(orderJson);

            // Publish to appropriate topic
            if (result.isValid()) {
                orderPublisher.publishValidated(result.getOrder());
                incrementCounter("orders.validated");
                log.info("Order {} validated and published", result.getOrder().getOrderId());
            } else {
                orderPublisher.publishRejected(result.getOrder(), result.getRejectionReason());
                incrementCounter("orders.rejected");
                log.warn("Order {} rejected: {}", result.getOrder().getOrderId(), result.getRejectionReason());
            }

            // Manual commit after successful processing
            acknowledgment.acknowledge();
            incrementCounter("orders.processed");

        } catch (Exception e) {
            log.error("Error processing order from partition {} offset {}: {}", partition, offset, e.getMessage(), e);
            incrementCounter("orders.errors");
            // Don't acknowledge - message will be redelivered
            throw e;
        }
    }

    private void incrementCounter(String name) {
        Counter.builder(name)
                .tag("service", "oms-validator")
                .register(meterRegistry)
                .increment();
    }
}
