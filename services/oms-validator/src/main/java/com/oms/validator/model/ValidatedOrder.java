package com.oms.validator.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity for storing validation results.
 * This is the validator's own table - not shared with oms-ingest.
 */
@Entity
@Table(name = "validated_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidatedOrder {

    @Id
    @Column(name = "order_id", columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID orderId;

    @Column(name = "client_order_id", length = 100, nullable = false)
    private String clientOrderId;

    @Column(name = "account_id", length = 50, nullable = false)
    private String accountId;

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
    @Column(name = "time_in_force", length = 10, nullable = false)
    private TimeInForce timeInForce;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", length = 20, nullable = false)
    private ValidationStatus validationStatus;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "validated_at", nullable = false)
    private Instant validatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (validatedAt == null) validatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum OrderSide {
        BUY, SELL
    }

    public enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT
    }

    public enum TimeInForce {
        DAY, GTC, IOC, FOK
    }

    public enum ValidationStatus {
        VALIDATED, REJECTED
    }
}
