package com.oms.ingest.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oms.common.model.OrderDTO;
import com.oms.ingest.service.OrderIngestionService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
    private final Tracer tracer;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestHeader(value = "X-OMS-Channel", required = false) String channel,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody OrderDTO orderRequest) {

        Span span = tracer.nextSpan().name("order.ingest.controller").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            // Add span attributes for filtering and debugging
            span.tag("order.clientOrderId", orderRequest.getClientOrderId());
            span.tag("order.symbol", orderRequest.getSymbol());
            span.tag("order.side", orderRequest.getSide().name());
            span.tag("order.type", orderRequest.getOrderType().name());
            span.tag("oms.channel", channel != null ? channel : "REST");
            if (requestId != null) {
                span.tag("oms.requestId", requestId);
            }

            log.info("Received order request: clientOrderId={}, symbol={}, side={}, quantity={}",
                    orderRequest.getClientOrderId(), orderRequest.getSymbol(),
                    orderRequest.getSide(), orderRequest.getQuantity());

            try {
                OrderIngestionService.IngestResult result = orderIngestionService.ingestOrder(orderRequest, channel,
                        requestId);
                OrderDTO savedOrder = result.order();

                // Add result attributes
                span.tag("order.id", savedOrder.getOrderId().toString());
                span.tag("order.created", String.valueOf(result.created()));
                span.tag("order.status", savedOrder.getStatus().name());
                span.event(result.created() ? "order.created" : "order.duplicate");

                OrderResponse response = OrderResponse.builder()
                        .orderId(savedOrder.getOrderId())
                        .clientOrderId(savedOrder.getClientOrderId())
                        .status(savedOrder.getStatus().name())
                        .created(result.created())
                        .message(result.created() ? "Order received successfully" : "Order already exists")
                        .timestamp(System.currentTimeMillis())
                        .build();

                return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);

            } catch (IllegalArgumentException e) {
                span.tag("error", "true");
                span.tag("error.type", "validation");
                span.event("validation.failed");
                log.warn("Invalid order request: {}", e.getMessage());
                return ResponseEntity.badRequest().body(OrderResponse.builder()
                        .created(false)
                        .message(e.getMessage())
                        .timestamp(System.currentTimeMillis())
                        .build());
            } catch (Exception e) {
                span.tag("error", "true");
                span.tag("error.type", "internal");
                span.event("processing.failed");
                log.error("Error processing order", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(OrderResponse.builder()
                                .created(false)
                                .message("Internal server error")
                                .timestamp(System.currentTimeMillis())
                                .build());
            }
        } finally {
            span.end();
        }
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, BindException.class })
    public ResponseEntity<OrderResponse> handleValidationExceptions(Exception ex) {
        String message = "Invalid request";

        if (ex instanceof MethodArgumentNotValidException manve) {
            if (manve.getBindingResult().hasFieldErrors()) {
                var fe = manve.getBindingResult().getFieldErrors().get(0);
                message = fe.getField() + ": " + fe.getDefaultMessage();
            }
        } else if (ex instanceof BindException be) {
            if (be.getBindingResult().hasFieldErrors()) {
                var fe = be.getBindingResult().getFieldErrors().get(0);
                message = fe.getField() + ": " + fe.getDefaultMessage();
            }
        }

        return ResponseEntity.badRequest().body(OrderResponse.builder()
                .created(false)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OrderResponse> handleUnreadableJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(OrderResponse.builder()
                .created(false)
                .message("Malformed JSON")
                .timestamp(System.currentTimeMillis())
                .build());
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
        private Boolean created;
        private String message;
        private Long timestamp;
    }
}
