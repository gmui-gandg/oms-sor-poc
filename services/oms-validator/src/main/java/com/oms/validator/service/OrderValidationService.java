package com.oms.validator.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.oms.common.model.OrderDTO;
import com.oms.validator.config.ValidatorProperties;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core validation logic for orders
 * Performs parameter validation and risk checks
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderValidationService {

    private final ValidatorProperties properties;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final RiskCheckService riskCheckService;

    public ValidationResult validateOrder(String orderJson) {
        try {
            // Parse order event from Kafka
            JsonNode eventNode = objectMapper.readTree(orderJson);
            JsonNode payloadNode = eventNode.get("payload");
            
            if (payloadNode == null) {
                return ValidationResult.rejected(null, "Invalid event format: missing payload");
            }

            OrderDTO order = objectMapper.treeToValue(payloadNode, OrderDTO.class);
            
            if (order.getOrderId() == null) {
                return ValidationResult.rejected(order, "Missing orderId");
            }

            log.debug("Validating order: orderId={}, symbol={}, side={}, quantity={}",
                    order.getOrderId(), order.getSymbol(), order.getSide(), order.getQuantity());

            // Load order from database to get current state
            com.oms.validator.model.Order dbOrder = orderRepository.findById(order.getOrderId())
                    .orElse(null);

            if (dbOrder == null) {
                log.warn("Order {} not found in database", order.getOrderId());
                return ValidationResult.rejected(order, "Order not found");
            }

            // Only validate orders in NEW status
            if (dbOrder.getStatus() != com.oms.validator.model.Order.OrderStatus.NEW) {
                log.debug("Order {} already processed: status={}", order.getOrderId(), dbOrder.getStatus());
                return ValidationResult.rejected(order, "Order already processed: " + dbOrder.getStatus());
            }

            // Perform validations
            List<String> errors = new ArrayList<>();

            // Basic parameter validation
            validateParameters(order, errors);

            // Symbol validation
            if (properties.getValidation().isCheckSymbolExists()) {
                validateSymbol(order.getSymbol(), errors);
            }

            // Risk checks
            if (properties.getRisk().isCheckBuyingPower()) {
                riskCheckService.checkBuyingPower(order, errors);
            }

            riskCheckService.checkOrderValue(order, errors);
            riskCheckService.checkPositionLimits(order, errors);

            if (!errors.isEmpty()) {
                String rejectionReason = String.join("; ", errors);
                updateOrderStatus(dbOrder, com.oms.validator.model.Order.OrderStatus.REJECTED);
                return ValidationResult.rejected(order, rejectionReason);
            }

            // All checks passed
            updateOrderStatus(dbOrder, com.oms.validator.model.Order.OrderStatus.VALIDATED);
            return ValidationResult.validated(order);

        } catch (Exception e) {
            log.error("Error validating order: {}", e.getMessage(), e);
            return ValidationResult.rejected(null, "Validation error: " + e.getMessage());
        }
    }

    private void validateParameters(OrderDTO order, List<String> errors) {
        if (order.getSymbol() == null || order.getSymbol().isBlank()) {
            errors.add("Symbol is required");
        }

        if (order.getQuantity() == null || order.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Quantity must be positive");
        }

        if (order.getSide() == null) {
            errors.add("Side is required");
        }

        if (order.getOrderType() == null) {
            errors.add("Order type is required");
        }

        // Limit orders must have limit price
        if ("LIMIT".equals(order.getOrderType()) && order.getLimitPrice() == null) {
            errors.add("Limit price required for LIMIT orders");
        }

        // Stop orders must have stop price
        if ("STOP".equals(order.getOrderType()) && order.getStopPrice() == null) {
            errors.add("Stop price required for STOP orders");
        }
    }

    private void validateSymbol(String symbol, List<String> errors) {
        // Stub: In production, check against a symbol master table or market data service
        if (symbol != null && symbol.length() > 10) {
            errors.add("Invalid symbol: " + symbol);
        }
    }

    private void updateOrderStatus(com.oms.validator.model.Order order, 
                                   com.oms.validator.model.Order.OrderStatus newStatus) {
        order.setStatus(newStatus);
        orderRepository.save(order);
        log.info("Updated order {} status to {}", order.getOrderId(), newStatus);
    }

    @Data
    @Builder
    public static class ValidationResult {
        private boolean valid;
        private OrderDTO order;
        private String rejectionReason;

        public static ValidationResult validated(OrderDTO order) {
            return ValidationResult.builder()
                    .valid(true)
                    .order(order)
                    .build();
        }

        public static ValidationResult rejected(OrderDTO order, String reason) {
            return ValidationResult.builder()
                    .valid(false)
                    .order(order)
                    .rejectionReason(reason)
                    .build();
        }
    }
}
