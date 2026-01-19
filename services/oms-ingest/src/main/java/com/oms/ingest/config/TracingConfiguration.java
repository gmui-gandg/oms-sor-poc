package com.oms.ingest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JBaggageEventListener;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;

@Configuration
public class TracingConfiguration {

        private static final Logger log = LoggerFactory.getLogger(TracingConfiguration.class);

        @Value("${spring.application.name}")
        private String serviceName;

        @Value("${management.otlp.tracing.endpoint}")
        private String otlpEndpoint;

        @Bean
        public OpenTelemetry openTelemetry() {
                log.info("Configuring OpenTelemetry with endpoint: {}", otlpEndpoint);

                Resource resource = Resource.getDefault()
                                .merge(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, serviceName)));

                SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                                .addSpanProcessor(BatchSpanProcessor.builder(
                                                OtlpGrpcSpanExporter.builder()
                                                                .setEndpoint(otlpEndpoint)
                                                                .build())
                                                .build())
                                .setResource(resource)
                                .build();

                OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
                                .setTracerProvider(sdkTracerProvider)
                                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                                .buildAndRegisterGlobal();

                Runtime.getRuntime().addShutdownHook(new Thread(sdkTracerProvider::close));

                log.info("OpenTelemetry configured successfully");
                return openTelemetrySdk;
        }

        @Bean
        public Tracer otelTracer(OpenTelemetry openTelemetry) {
                OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
                Slf4JEventListener slf4JEventListener = new Slf4JEventListener();
                Slf4JBaggageEventListener slf4JBaggageEventListener = new Slf4JBaggageEventListener(
                                java.util.Collections.emptyList());

                OtelTracer tracer = new OtelTracer(
                                openTelemetry.getTracer(serviceName),
                                otelCurrentTraceContext,
                                event -> {
                                        slf4JEventListener.onEvent(event);
                                        slf4JBaggageEventListener.onEvent(event);
                                },
                                new OtelBaggageManager(otelCurrentTraceContext, java.util.Collections.emptyList(),
                                                java.util.Collections.emptyList()));

                log.info("Tracer bean created successfully");
                return tracer;
        }

        @Bean
        public OtelPropagator otelPropagator(OpenTelemetry openTelemetry, Tracer tracer) {
                return new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
                                openTelemetry.getTracer(serviceName));
        }

        @Bean
        public ObservationRegistryCustomizer<ObservationRegistry> observationRegistryCustomizer(
                        Tracer tracer, Propagator propagator) {
                return registry -> {
                        // Add tracing handlers in the correct order: Default first to create root spans
                        registry.observationConfig()
                                        .observationHandler(new DefaultTracingObservationHandler(tracer))
                                        .observationHandler(
                                                        new PropagatingReceiverTracingObservationHandler<>(tracer,
                                                                        propagator))
                                        .observationHandler(
                                                        new PropagatingSenderTracingObservationHandler<>(tracer,
                                                                        propagator));

                        log.info("ObservationRegistry customized with tracing handlers");
                };
        }
}
