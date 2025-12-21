package com.oms.validator.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import com.oms.common.model.OrderDTO;
import com.oms.validator.config.ValidatorProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes validated/rejected orders to Kafka
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ValidatorProperties properties;

    public void publishValidated(OrderDTO order) {
        try {
            String payload = objectMapper.writeValueAsString(order);
            String topic = properties.getTopics().getValidated();
            String key = order.getOrderId().toString();

            kafkaTemplate.send(topic, key, payload)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("Published validated order {} to {}", order.getOrderId(), topic);
                        } else {
                            log.error("Failed to publish validated order {}: {}", order.getOrderId(), ex.getMessage(), ex);
                        }
                    });
        } catch (Exception e) {
            log.error("Error publishing validated order {}: {}", order.getOrderId(), e.getMessage(), e);
            throw new RuntimeException("Failed to publish validated order", e);
        }
    }

    public void publishRejected(OrderDTO order, String reason) {
        try {
            RejectedOrderEvent event = new RejectedOrderEvent(order, reason);
            String payload = objectMapper.writeValueAsString(event);
            String topic = properties.getTopics().getRejected();
            String key = order != null ? order.getOrderId().toString() : "unknown";

            kafkaTemplate.send(topic, key, payload)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("Published rejected order {} to {}: {}", key, topic, reason);
                        } else {
                            log.error("Failed to publish rejected order {}: {}", key, ex.getMessage(), ex);
                        }
                    });
        } catch (Exception e) {
            log.error("Error publishing rejected order: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish rejected order", e);
        }
    }

    private record RejectedOrderEvent(OrderDTO order, String rejectionReason) {}
}
