package com.oms.validator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for OMS validator service
 */
@Data
@ConfigurationProperties(prefix = "oms.validator")
public class ValidatorProperties {

    private Topics topics = new Topics();
    private Risk risk = new Risk();
    private Validation validation = new Validation();

    @Data
    public static class Topics {
        private String ingest = "order.ingest";
        private String validated = "order.validated";
        private String rejected = "order.rejected";
    }

    @Data
    public static class Risk {
        private long maxOrderValue = 1000000;
        private long maxPositionSize = 100000;
        private boolean checkBuyingPower = true;
    }

    @Data
    public static class Validation {
        private boolean checkMarketHours = false;
        private boolean checkSymbolExists = true;
    }
}
