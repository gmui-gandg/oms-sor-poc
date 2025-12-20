package com.oms.common.kafka;

/**
 * Centralized Kafka topic names for the OMS platform.
 */
public final class KafkaTopics {
    
    // Order topics
    public static final String ORDERS_INBOUND = "orders.inbound";
    public static final String ORDERS_VALIDATED = "orders.validated";
    public static final String ORDERS_ROUTED = "orders.routed";
    
    // Execution topics
    public static final String EXECUTIONS_FILLS = "executions.fills";
    public static final String EXECUTIONS_REJECTS = "executions.rejects";
    
    // Market data topics
    public static final String MARKETDATA_QUOTES = "marketdata.quotes";
    
    // Dead letter queue
    public static final String DLQ_SUFFIX = ".dlq";
    
    private KafkaTopics() {
        throw new UnsupportedOperationException("Utility class");
    }
}
