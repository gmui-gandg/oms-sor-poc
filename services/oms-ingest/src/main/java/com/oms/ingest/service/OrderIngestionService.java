package com.oms.ingest.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.kafka.KafkaTopics;
import com.oms.common.model.OrderDTO;
import com.oms.ingest.model.Order;
import com.oms.ingest.model.OutboxEvent;
import com.oms.ingest.repository.OrderRepository;
import com.oms.ingest.repository.OutboxRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    @Transactional
    public OrderDTO ingestOrder(OrderDTO orderRequest) {
        // Check for duplicate
        if (orderRepository.existsByClientOrderId(orderRequest.getClientOrderId())) {
            throw new IllegalArgumentException("Duplicate client order ID: " + orderRequest.getClientOrderId());
        }

        // Basic validation
        validateOrder(orderRequest);

        // Convert DTO to Entity
        Order order = toEntity(orderRequest);
        order.setStatus(Order.OrderStatus.NEW);

        // Save order (atomic with outbox)
        Order savedOrder = orderRepository.save(order);
        log.debug("Saved order: orderId={}", savedOrder.getOrderId());

        // Create outbox event for Kafka publication
        try {
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
            log.debug("Created outbox event: eventId={}", outboxEvent.getId());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order to JSON", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }

        // Metrics
        Counter.builder("oms.ingest.orders.received")
                .tag("symbol", savedOrder.getSymbol())
                .tag("side", savedOrder.getSide().name())
                .register(meterRegistry)
                .increment();

        return toDTO(savedOrder);
    }

    public Optional<OrderDTO> getOrder(UUID orderId) {
        return orderRepository.findById(orderId).map(this::toDTO);
    }

    private void validateOrder(OrderDTO order) {
        if (order.getQuantity() == null || order.getQuantity() <= 0) {
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
                .quantity(BigDecimal.valueOf(dto.getQuantity()))
                .limitPrice(dto.getLimitPrice() != null ? BigDecimal.valueOf(dto.getLimitPrice()) : null)
                .stopPrice(dto.getStopPrice() != null ? BigDecimal.valueOf(dto.getStopPrice()) : null)
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
                .quantity(entity.getQuantity().doubleValue())
                .filledQuantity(entity.getFilledQuantity() != null ? entity.getFilledQuantity().doubleValue() : 0.0)
                .limitPrice(entity.getLimitPrice() != null ? entity.getLimitPrice().doubleValue() : null)
                .stopPrice(entity.getStopPrice() != null ? entity.getStopPrice().doubleValue() : null)
                .timeInForce(OrderDTO.TimeInForce.valueOf(entity.getTimeInForce().name()))
                .status(OrderDTO.OrderStatus.valueOf(entity.getStatus().name()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
