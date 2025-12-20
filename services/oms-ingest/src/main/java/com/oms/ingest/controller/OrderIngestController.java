package com.oms.ingest.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oms.common.model.OrderDTO;
import com.oms.ingest.controller.OrderIngestController.OrderResponse;
import com.oms.ingest.service.OrderIngestionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST API for order submission.
 * Target latency: <50ms for ACK
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderIngestController {

    private final OrderIngestionService orderIngestionService;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody OrderDTO orderRequest) {
        log.info("Received order request: clientOrderId={}, symbol={}, side={}, quantity={}",
                orderRequest.getClientOrderId(), orderRequest.getSymbol(),
                orderRequest.getSide(), orderRequest.getQuantity());

        try {
            OrderDTO savedOrder = orderIngestionService.ingestOrder(orderRequest);

            OrderResponse response = OrderResponse.builder()
                    .orderId(savedOrder.getOrderId())
                    .clientOrderId(savedOrder.getClientOrderId())
                    .status(savedOrder.getStatus().name())
                    .message("Order received successfully")
                    .timestamp(System.currentTimeMillis())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid order request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(OrderResponse.builder()
                    .message(e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            log.error("Error processing order", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(OrderResponse.builder()
                            .message("Internal server error")
                            .timestamp(System.currentTimeMillis())
                            .build());
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDTO> getOrder(@PathVariable java.util.UUID orderId) {
        return orderIngestionService.getOrder(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @lombok.Data
    @lombok.Builder
    public static class OrderResponse {
        private java.util.UUID orderId;
        private String clientOrderId;
        private String status;
        private String message;
        private Long timestamp;
    }
}
