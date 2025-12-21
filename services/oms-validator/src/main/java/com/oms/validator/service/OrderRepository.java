package com.oms.validator.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oms.validator.model.Order;

/**
 * Repository for Order entity
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByClientOrderId(String clientOrderId);
}
