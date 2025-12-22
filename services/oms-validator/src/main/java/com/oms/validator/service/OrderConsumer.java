package com.oms.validator.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oms.common.model.OrderDTO;
import com.oms.validator.model.ValidatedOrder;
import com.oms.validator.repository.ValidatedOrderRepository;
import com.oms.validator.service.OrderValidationService.ValidationResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka consumer for orders.inbound topic.
 * Consumes orders, validates them, persists results, and publishes to next topic.
 * 
 * Flow:
 * 1. Receive order from orders.inbound
 * 2. Validate (business rules, risk checks)
 * 3. Persist to validated_orders table (own table)
 * 4. Publish to orders.validated or orders.rejected (direct Kafka - Option 2)
 * 5. Acknowledge Kafka offset
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderConsumer {

    private final OrderValidationService validationService;
    private final ValidatedOrderRepository validatedOrderRepository;
    private final OrderPublisher orderPublisher;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
        topics = "${oms.validator.topics.ingest}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeOrder(
            @Payload String orderJson,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("Consuming order from partition {} offset {}: key={}", partition, offset, key);

        try {
            // 1. Validate order
            ValidationResult result = validationService.validateOrder(orderJson);
            OrderDTO order = result.getOrder();

            if (order == null || order.getOrderId() == null) {
                log.error("Invalid order received, cannot process");
                acknowledgment.acknowledge(); // Skip bad message
                incrementCounter("orders.invalid");
                return;
            }

            // 2. Idempotency check - skip if already processed
            if (validatedOrderRepository.existsByOrderId(order.getOrderId())) {
                log.info("Order {} already processed, skipping", order.getOrderId());
                acknowledgment.acknowledge();
                incrementCounter("orders.duplicate");
                return;
            }

            // 3. Persist validation result to own table
            ValidatedOrder validatedOrder = toValidatedOrder(order, result);
            validatedOrderRepository.save(validatedOrder);
            log.debug("Persisted validation result for order {}: {}", 
                    order.getOrderId(), validatedOrder.getValidationStatus());

            // 4. Publish to next topic (direct Kafka - acceptable for POC)
            if (result.isValid()) {
                orderPublisher.publishValidated(order);
                incrementCounter("orders.validated");
                log.info("Order {} validated and published", order.getOrderId());
            } else {
                orderPublisher.publishRejected(order, result.getRejectionReason());
                incrementCounter("orders.rejected");
                log.warn("Order {} rejected: {}", order.getOrderId(), result.getRejectionReason());
            }

            // 5. Acknowledge Kafka offset after successful processing
            acknowledgment.acknowledge();
            incrementCounter("orders.processed");

        } catch (Exception e) {
            log.error("Error processing order from partition {} offset {}: {}", 
                    partition, offset, e.getMessage(), e);
            incrementCounter("orders.errors");
            // Don't acknowledge - message will be redelivered
            throw e;
        }
    }

    private ValidatedOrder toValidatedOrder(OrderDTO order, ValidationResult result) {
        return ValidatedOrder.builder()
                .orderId(order.getOrderId())
                .clientOrderId(order.getClientOrderId())
                .accountId(order.getAccountId())
                .symbol(order.getSymbol())
                .side(ValidatedOrder.OrderSide.valueOf(order.getSide().name()))
                .orderType(ValidatedOrder.OrderType.valueOf(order.getOrderType().name()))
                .quantity(order.getQuantity())
                .limitPrice(order.getLimitPrice())
                .stopPrice(order.getStopPrice())
                .timeInForce(ValidatedOrder.TimeInForce.valueOf(order.getTimeInForce().name()))
                .validationStatus(result.isValid() 
                        ? ValidatedOrder.ValidationStatus.VALIDATED 
                        : ValidatedOrder.ValidationStatus.REJECTED)
                .rejectionReason(result.getRejectionReason())
                .build();
    }

    private void incrementCounter(String name) {
        Counter.builder("oms.validator." + name)
                .tag("service", "oms-validator")
                .register(meterRegistry)
                .increment();
    }
}
