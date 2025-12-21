package com.oms.ingest.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaTemplateConfig {

    @Bean
    public ProducerFactory<String, String> stringProducerFactory(Environment environment) {
        Map<String, Object> producerProps = new HashMap<>();

        producerProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                environment.getProperty("spring.kafka.bootstrap-servers", "localhost:9092"));
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Optional tuning; defaults match our dev-friendly settings.
        producerProps.put(ProducerConfig.ACKS_CONFIG, environment.getProperty("spring.kafka.producer.acks", "all"));
        producerProps.put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                Boolean.parseBoolean(environment.getProperty("spring.kafka.producer.enable-idempotence", "true")));

        String lingerMs = environment.getProperty("spring.kafka.producer.properties.linger.ms");
        if (lingerMs != null && !lingerMs.isBlank()) {
            producerProps.put(ProducerConfig.LINGER_MS_CONFIG, Integer.parseInt(lingerMs));
        }

        String maxInFlight = environment.getProperty(
                "spring.kafka.producer.properties.max.in.flight.requests.per.connection");
        if (maxInFlight != null && !maxInFlight.isBlank()) {
            producerProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, Integer.parseInt(maxInFlight));
        }

        return new DefaultKafkaProducerFactory<>(producerProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> stringProducerFactory) {
        return new KafkaTemplate<>(stringProducerFactory);
    }
}
