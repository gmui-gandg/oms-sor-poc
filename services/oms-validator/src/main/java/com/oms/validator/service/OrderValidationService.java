package com.oms.validator.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import com.oms.common.model.OrderDTO;
import com.oms.validator.config.ValidatorProperties;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core validation logic for orders.
 * Pure validation - no database access.
 * Performs parameter validation and risk checks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderValidationService {

    private final ValidatorProperties properties;
    private final ObjectMapper objectMapper;
    private final RiskCheckService riskCheckService;

    /**
     * Validates an order from JSON.
     * Returns ValidationResult with the parsed order and validation status.
     */
    public ValidationResult validateOrder(String orderJson) {
        try {
            // Parse order from Kafka message
            OrderDTO order = objectMapper.readValue(orderJson, OrderDTO.class);

            if (order.getOrderId() == null) {
                return ValidationResult.rejected(order, "Missing orderId");
            }

            log.debug("Validating order: orderId={}, symbol={}, side={}, quantity={}",
                    order.getOrderId(), order.getSymbol(), order.getSide(), order.getQuantity());

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
                return ValidationResult.rejected(order, rejectionReason);
            }

            // All checks passed
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

        if (order.getTimeInForce() == null) {
            errors.add("Time in force is required");
        }

        // Limit orders must have limit price
        if (order.getOrderType() == OrderDTO.OrderType.LIMIT && order.getLimitPrice() == null) {
            errors.add("Limit price required for LIMIT orders");
        }

        // Stop orders must have stop price
        if (order.getOrderType() == OrderDTO.OrderType.STOP && order.getStopPrice() == null) {
            errors.add("Stop price required for STOP orders");
        }
    }

    private void validateSymbol(String symbol, List<String> errors) {
        // Stub: In production, check against a symbol master table or market data service
        if (symbol != null && symbol.length() > 10) {
            errors.add("Invalid symbol: " + symbol);
        }
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
