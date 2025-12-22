package com.oms.validator.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.oms.common.model.OrderDTO;
import com.oms.validator.config.ValidatorProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Risk management checks for orders
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskCheckService {

    private final ValidatorProperties properties;

    public void checkBuyingPower(OrderDTO order, List<String> errors) {
        // Stub: In production, query account service for buying power
        // For now, assume all accounts have sufficient buying power
        log.debug("Checking buying power for order {}: PASSED (stub)", order.getOrderId());
    }

    public void checkOrderValue(OrderDTO order, List<String> errors) {
        BigDecimal orderValue = calculateOrderValue(order);
        long maxOrderValue = properties.getRisk().getMaxOrderValue();

        if (orderValue != null && orderValue.compareTo(BigDecimal.valueOf(maxOrderValue)) > 0) {
            errors.add("Order value " + orderValue + " exceeds maximum " + maxOrderValue);
            log.warn("Order {} rejected: value {} exceeds max {}", 
                    order.getOrderId(), orderValue, maxOrderValue);
        }
    }

    public void checkPositionLimits(OrderDTO order, List<String> errors) {
        // Stub: In production, query position service for current positions
        // Check if this order would exceed per-symbol position limits
        long maxPositionSize = properties.getRisk().getMaxPositionSize();
        
        if (order.getQuantity() != null && 
            order.getQuantity().compareTo(BigDecimal.valueOf(maxPositionSize)) > 0) {
            errors.add("Order quantity " + order.getQuantity() + " exceeds maximum position size " + maxPositionSize);
            log.warn("Order {} rejected: quantity {} exceeds max position size {}", 
                    order.getOrderId(), order.getQuantity(), maxPositionSize);
        }
    }

    private BigDecimal calculateOrderValue(OrderDTO order) {
        if (order.getQuantity() == null) {
            return null;
        }

        BigDecimal price = null;
        if ("LIMIT".equals(order.getOrderType()) && order.getLimitPrice() != null) {
            price = order.getLimitPrice();
        } else if ("STOP".equals(order.getOrderType()) && order.getStopPrice() != null) {
            price = order.getStopPrice();
        } else if ("MARKET".equals(order.getOrderType())) {
            // Stub: For market orders, use last traded price or estimated price
            // For now, skip value check for market orders
            return null;
        }

        if (price != null) {
            return order.getQuantity().multiply(price);
        }

        return null;
    }
}
