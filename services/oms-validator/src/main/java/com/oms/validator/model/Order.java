package com.oms.validator.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA Entity for orders table (read/update only for validator)
 */
@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @Column(name = "order_id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID orderId;

    @Column(name = "client_order_id", length = 100, nullable = false)
    private String clientOrderId;

    @Column(name = "account_id", length = 50, nullable = false)
    private String accountId;

    @Column(name = "source_channel", length = 20, nullable = false)
    private String sourceChannel;

    @Column(name = "symbol", length = 20, nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 10, nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 20, nullable = false)
    private OrderType orderType;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(name = "limit_price", precision = 18, scale = 6)
    private BigDecimal limitPrice;

    @Column(name = "stop_price", precision = 18, scale = 6)
    private BigDecimal stopPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private OrderStatus status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum OrderSide {
        BUY, SELL
    }

    public enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT
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
