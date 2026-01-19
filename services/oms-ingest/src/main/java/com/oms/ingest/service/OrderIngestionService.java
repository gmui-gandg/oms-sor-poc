package com.oms.ingest.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oms.common.kafka.KafkaTopics;
import com.oms.common.model.OrderDTO;
import com.oms.ingest.model.Order;
import com.oms.ingest.model.OutboxEvent;
import com.oms.ingest.repository.OrderRepository;
import com.oms.ingest.repository.OutboxRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Service for order ingestion.
 * Implements fast validation and persistence with transactional outbox pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIngestionService {

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public record IngestResult(OrderDTO order, boolean created) {
    }

    @Transactional
    public IngestResult ingestOrder(OrderDTO orderRequest, String sourceChannel, String requestId) {
        Span span = tracer.nextSpan().name("order.ingest.service").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("service", "order-ingestion");
            span.tag("order.accountId", orderRequest.getAccountId());
            span.tag("order.symbol", orderRequest.getSymbol());

            String normalizedChannel = (sourceChannel == null || sourceChannel.isBlank()) ? "REST"
                    : sourceChannel.trim();
            span.tag("channel", normalizedChannel);

            // Idempotency check
            Span idempotencySpan = tracer.nextSpan().name("db.check-idempotency").start();
            try (Tracer.SpanInScope ws2 = tracer.withSpan(idempotencySpan)) {
                idempotencySpan.tag("db.operation", "findByAccountIdAndSourceChannelAndClientOrderId");
                idempotencySpan.tag("db.table", "orders");

                Optional<Order> existing = orderRepository.findByAccountIdAndSourceChannelAndClientOrderId(
                        orderRequest.getAccountId(),
                        normalizedChannel,
                        orderRequest.getClientOrderId());

                if (existing.isPresent()) {
                    idempotencySpan.event("order.duplicate-found");
                    span.tag("order.duplicate", "true");
                    return new IngestResult(toDTO(existing.get()), false);
                }
                idempotencySpan.event("order.unique-verified");
            } finally {
                idempotencySpan.end();
            }

            // Validation
            Span validationSpan = tracer.nextSpan().name("order.validate").start();
            try (Tracer.SpanInScope ws2 = tracer.withSpan(validationSpan)) {
                validateOrder(orderRequest);
                validationSpan.event("validation.passed");
            } finally {
                validationSpan.end();
            }

            // Convert and save
            Order order = toEntity(orderRequest);
            order.setSourceChannel(normalizedChannel);
            order.setRequestId(requestId);
            order.setStatus(Order.OrderStatus.NEW);

            Span saveSpan = tracer.nextSpan().name("db.save-order").start();
            final Order savedOrder;
            try (Tracer.SpanInScope ws2 = tracer.withSpan(saveSpan)) {
                saveSpan.tag("db.operation", "insert");
                saveSpan.tag("db.table", "orders");

                try {
                    savedOrder = orderRepository.save(order);
                    saveSpan.tag("order.id", savedOrder.getOrderId().toString());
                    saveSpan.event("order.persisted");
                    log.debug("Saved order: orderId={}", savedOrder.getOrderId());
                } catch (DataIntegrityViolationException e) {
                    saveSpan.tag("error", "true");
                    saveSpan.tag("error.type", "race-condition");
                    saveSpan.event("db.conflict");
                    // Race condition: another request won the insert
                    return orderRepository.findByAccountIdAndSourceChannelAndClientOrderId(
                            orderRequest.getAccountId(),
                            normalizedChannel,
                            orderRequest.getClientOrderId())
                            .map(o -> new IngestResult(toDTO(o), false))
                            .orElseThrow(() -> e);
                }
            } finally {
                saveSpan.end();
            }

            span.tag("order.id", savedOrder.getOrderId().toString());

            // Create outbox event
            Span outboxSpan = tracer.nextSpan().name("db.save-outbox").start();
            try (Tracer.SpanInScope ws2 = tracer.withSpan(outboxSpan)) {
                outboxSpan.tag("db.operation", "insert");
                outboxSpan.tag("db.table", "outbox_events");
                outboxSpan.tag("event.type", "OrderCreated");
                outboxSpan.tag("kafka.topic", KafkaTopics.ORDERS_INBOUND);

                String payload = objectMapper.writeValueAsString(toDTO(savedOrder));
                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .aggregateType("Order")
                        .aggregateId(savedOrder.getOrderId())
                        .eventType("OrderCreated")
                        .topic(KafkaTopics.ORDERS_INBOUND)
                        .kafkaKey(savedOrder.getOrderId())
                        .payload(payload)
                        .build();

                outboxRepository.save(outboxEvent);
                outboxSpan.tag("outbox.eventId", String.valueOf(outboxEvent.getId()));
                outboxSpan.event("outbox.event-created");
                log.debug("Created outbox event: eventId={}", outboxEvent.getId());

            } catch (JacksonException e) {
                outboxSpan.tag("error", "true");
                outboxSpan.tag("error.type", "serialization");
                log.error("Failed to serialize order to JSON", e);
                throw new RuntimeException("Failed to create outbox event", e);
            } finally {
                outboxSpan.end();
            }

            // Metrics
            Counter.builder("oms.ingest.orders.received")
                    .tag("symbol", savedOrder.getSymbol())
                    .tag("side", savedOrder.getSide().name())
                    .register(meterRegistry)
                    .increment();

            span.event("order.ingestion-complete");
            return new IngestResult(toDTO(savedOrder), true);
        } finally {
            span.end();
        }
    }

    public Optional<OrderDTO> getOrder(UUID orderId) {
        return orderRepository.findById(orderId).map(this::toDTO);
    }

    private void validateOrder(OrderDTO order) {
        if (order.getQuantity() == null || order.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        if (order.getOrderType() == OrderDTO.OrderType.LIMIT && order.getLimitPrice() == null) {
            throw new IllegalArgumentException("Limit price required for LIMIT order");
        }

        if (order.getOrderType() == OrderDTO.OrderType.STOP && order.getStopPrice() == null) {
            throw new IllegalArgumentException("Stop price required for STOP order");
        }
    }

    private Order toEntity(OrderDTO dto) {
        return Order.builder()
                .clientOrderId(dto.getClientOrderId())
                .accountId(dto.getAccountId())
                .symbol(dto.getSymbol())
                .side(Order.OrderSide.valueOf(dto.getSide().name()))
                .orderType(Order.OrderType.valueOf(dto.getOrderType().name()))
                .quantity(dto.getQuantity())
                .limitPrice(dto.getLimitPrice())
                .stopPrice(dto.getStopPrice())
                .timeInForce(Order.TimeInForce.valueOf(dto.getTimeInForce().name()))
                .build();
    }

    private OrderDTO toDTO(Order entity) {
        return OrderDTO.builder()
                .orderId(entity.getOrderId())
                .clientOrderId(entity.getClientOrderId())
                .accountId(entity.getAccountId())
                .symbol(entity.getSymbol())
                .side(OrderDTO.OrderSide.valueOf(entity.getSide().name()))
                .orderType(OrderDTO.OrderType.valueOf(entity.getOrderType().name()))
                .quantity(entity.getQuantity())
                .filledQuantity(entity.getFilledQuantity())
                .limitPrice(entity.getLimitPrice())
                .stopPrice(entity.getStopPrice())
                .timeInForce(OrderDTO.TimeInForce.valueOf(entity.getTimeInForce().name()))
                .status(OrderDTO.OrderStatus.valueOf(entity.getStatus().name()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
