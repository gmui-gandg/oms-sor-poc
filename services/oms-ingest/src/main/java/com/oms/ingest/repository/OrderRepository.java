package com.oms.ingest.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.oms.ingest.model.Order;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByClientOrderId(String clientOrderId);

    boolean existsByClientOrderId(String clientOrderId);
}
