package com.oms.validator.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.oms.validator.model.ValidatedOrder;

/**
 * Repository for ValidatedOrder entity.
 * Stores validation results for orders processed by this service.
 */
@Repository
public interface ValidatedOrderRepository extends JpaRepository<ValidatedOrder, UUID> {

    Optional<ValidatedOrder> findByClientOrderId(String clientOrderId);

    boolean existsByOrderId(UUID orderId);
}
