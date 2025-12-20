package com.oms.common.model;

import java.time.Instant;
import java.util.UUID;

import com.oms.common.model.OrderDTO.OrderSide;
import com.oms.common.model.OrderDTO.OrderStatus;
import com.oms.common.model.OrderDTO.OrderType;
import com.oms.common.model.OrderDTO.TimeInForce;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain model for an order in the OMS.
 * This is used for REST API, Kafka messages, and internal processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDTO {

    private UUID orderId;

    @NotBlank(message = "Client order ID is required")
    private String clientOrderId;

    @NotBlank(message = "Account ID is required")
    private String accountId;

    @NotBlank(message = "Symbol is required")
    private String symbol;

    @NotNull(message = "Side is required")
    private OrderSide side;

    @NotNull(message = "Order type is required")
    private OrderType orderType;

    @Positive(message = "Quantity must be positive")
    private Double quantity;

    private Double filledQuantity;

    private Double limitPrice;

    private Double stopPrice;

    @NotNull(message = "Time in force is required")
    private TimeInForce timeInForce;

    private OrderStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    public enum OrderSide {
        BUY, SELL
    }

    public enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT
    }

    public enum TimeInForce {
        DAY, GTC, IOC, FOK
    }

    public enum OrderStatus {
        NEW,
        PENDING_VALIDATION,
        VALIDATED,
        REJECTED,
        ROUTING,
        ROUTED,
        PARTIALLY_FILLED,
        FILLED,
        CANCELED,
        EXPIRED
    }
}
