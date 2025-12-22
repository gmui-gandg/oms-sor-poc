package com.oms.validator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * OMS Validator Service - Order validation and risk management
 * 
 * Responsibilities:
 * - Consume orders from order.ingest topic
 * - Validate order parameters (symbol, quantity, price, etc.)
 * - Perform risk checks (buying power, position limits, margin)
 * - Update order status (NEW -> VALIDATED or REJECTED)
 * - Publish validated orders to order.validated topic
 * - Publish rejected orders to order.rejected topic
 */
@SpringBootApplication
@EnableKafka
@ConfigurationPropertiesScan
public class ValidatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValidatorApplication.class, args);
    }
}
