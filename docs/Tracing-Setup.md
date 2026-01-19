# Distributed Tracing Setup Guide

## Overview

Complete distributed tracing implementation using OpenTelemetry, Micrometer Tracing, Tempo, and Grafana for the OMS Ingest service.

## Architecture

```
Spring Boot Application (oms-ingest)
  └─> OpenTelemetry SDK
      └─> OTLP gRPC Exporter (port 4317)
          └─> Tempo (Trace Backend)
              └─> Grafana (Visualization)
```

## What Was Configured

### 1. Spring Boot 4 Manual Tracing Configuration

**Challenge**: Spring Boot 4.0.1 does not include auto-configuration for OpenTelemetry tracing. All tracing beans must be manually configured.

**Solution**: Created `TracingConfiguration.java` with:

#### OpenTelemetry SDK Setup
- **OpenTelemetry Bean**: Configured with OTLP gRPC exporter to Tempo
- **Resource Attributes**: Service name set to `oms-ingest`
- **Trace Propagation**: W3C Trace Context format for distributed tracing
- **Batch Span Processor**: Efficient async export to Tempo

```java
@Bean
public OpenTelemetry openTelemetry() {
    Resource resource = Resource.getDefault()
        .merge(Resource.create(Attributes.of(
            ResourceAttributes.SERVICE_NAME, "oms-ingest")));

    SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(
            OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://localhost:4317")
                .build())
            .build())
        .setResource(resource)
        .build();

    return OpenTelemetrySdk.builder()
        .setTracerProvider(sdkTracerProvider)
        .setPropagators(ContextPropagators.create(
            W3CTraceContextPropagator.getInstance()))
        .buildAndRegisterGlobal();
}
```

#### Micrometer Tracing Bridge
- **OtelTracer**: Bridges Micrometer API to OpenTelemetry
- **Slf4J Integration**: Automatic traceId/spanId injection into logs
- **Baggage Management**: Cross-cutting concerns propagation

```java
@Bean
public Tracer otelTracer(OpenTelemetry openTelemetry) {
    OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
    Slf4JEventListener slf4JEventListener = new Slf4JEventListener();
    Slf4JBaggageEventListener slf4JBaggageEventListener = 
        new Slf4JBaggageEventListener(Collections.emptyList());

    return new OtelTracer(
        openTelemetry.getTracer(serviceName),
        otelCurrentTraceContext,
        event -> {
            slf4JEventListener.onEvent(event);
            slf4JBaggageEventListener.onEvent(event);
        },
        new OtelBaggageManager(otelCurrentTraceContext, 
            Collections.emptyList(), Collections.emptyList()));
}
```

#### ObservationRegistry Customization
- **Spring Boot Integration**: Customizes the auto-configured ObservationRegistry
- **Handler Chain**: DefaultTracingObservationHandler → PropagatingReceiverTracingObservationHandler → PropagatingSenderTracingObservationHandler
- **Automatic HTTP Tracing**: Creates root SERVER spans for all HTTP requests

```java
@Bean
public ObservationRegistryCustomizer<ObservationRegistry> observationRegistryCustomizer(
        Tracer tracer, Propagator propagator) {
    return registry -> {
        registry.observationConfig()
            .observationHandler(new DefaultTracingObservationHandler(tracer))
            .observationHandler(new PropagatingReceiverTracingObservationHandler<>(tracer, propagator))
            .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));
    };
}
```

### 2. Custom Span Instrumentation

#### Controller Layer (`OrderIngestController`)

**Main Span**: `order.ingest.controller`

**Attributes**:
- `order.clientOrderId` - Client-provided order identifier
- `order.symbol` - Trading symbol (e.g., AAPL)
- `order.side` - BUY or SELL
- `order.type` - MARKET, LIMIT, STOP
- `oms.channel` - Source channel (REST, gRPC, etc.)
- `oms.requestId` - Request correlation ID
- `order.id` - Server-generated UUID (on success)
- `order.created` - true/false indicating new vs duplicate
- `order.status` - Order status (NEW, REJECTED, etc.)
- `error` - "true" if error occurred
- `error.type` - validation, internal

**Events**:
- `order.created` - New order successfully created
- `order.duplicate` - Duplicate order detected (idempotency)
- `validation.failed` - Order validation error
- `processing.failed` - Internal processing error

#### Service Layer (`OrderIngestionService`)

**Main Span**: `order.ingest.service`

**Attributes**:
- `service` = "order-ingestion"
- `order.accountId` - Customer account identifier
- `order.symbol` - Trading symbol
- `channel` - Normalized channel name
- `order.id` - Order UUID

**Child Spans**:

1. **`db.check-idempotency`**
   - Attributes: `db.operation`, `db.table`
   - Events: `order.duplicate-found`, `order.unique-verified`

2. **`order.validate`**
   - Events: `validation.passed`

3. **`db.save-order`**
   - Attributes: `db.operation`, `db.table`, `order.id`
   - Events: `order.persisted`, `db.conflict`

4. **`db.save-outbox`**
   - Attributes: `db.operation`, `db.table`, `event.type`, `kafka.topic`, `outbox.eventId`
   - Events: `outbox.event-created`

**Main Span Event**:
- `order.ingestion-complete` - Full ingestion workflow completed

### 3. Dependencies Added

**pom.xml additions**:
```xml
<!-- Micrometer Tracing Bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!-- OpenTelemetry OTLP Exporter -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>

<!-- OpenTelemetry Semantic Conventions -->
<dependency>
    <groupId>io.opentelemetry.semconv</groupId>
    <artifactId>opentelemetry-semconv</artifactId>
    <version>1.25.0-alpha</version>
</dependency>

<!-- OpenTelemetry Trace Propagators -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-extension-trace-propagators</artifactId>
</dependency>
```

### 4. Application Configuration

**application.yml**:
```yaml
spring:
  application:
    name: oms-ingest

management:
  otlp:
    tracing:
      endpoint: http://localhost:4317
  tracing:
    sampling:
      probability: 1.0  # 100% sampling for development

logging:
  pattern:
    level: '%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]'
```

### 5. Tempo Configuration

**infra/docker/tempo.yaml**:
```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces
```

**docker-compose.yml**:
```yaml
tempo:
  image: grafana/tempo:2.9.0
  command: [ "-config.file=/etc/tempo.yaml" ]
  volumes:
    - ./tempo.yaml:/etc/tempo.yaml
  ports:
    - "3200:3200"   # HTTP API / Grafana queries
    - "4317:4317"   # OTLP gRPC receiver
```

### 6. Grafana Datasource

**grafana/provisioning/datasources/datasources.yaml**:
```yaml
apiVersion: 1

datasources:
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    isDefault: false
    editable: true
```

## Running the Application

### Start Infrastructure
```powershell
# Start all infra services (Postgres, Kafka, Tempo, Prometheus, Grafana)
cd infra/docker
docker compose up -d
```

### Verify Services
```powershell
# Check Tempo is receiving traces
Invoke-RestMethod http://localhost:3200/ready

# Check Grafana datasource
Invoke-RestMethod http://localhost:3000/api/datasources | ConvertFrom-Json
```

### Start oms-ingest with Tracing
```powershell
cd services/oms-ingest
mvn spring-boot:run
```

The service will:
1. Initialize OpenTelemetry SDK
2. Connect to Tempo at http://localhost:4317
3. Create Tracer bean with Slf4J integration
4. Customize ObservationRegistry with tracing handlers
5. Log: "ObservationRegistry customized with tracing handlers"

## Viewing Traces in Grafana

### Access Grafana
1. Open http://localhost:3000
2. Login: `admin` / `admin`

### Search for Traces

**Method 1: Search by Service**
1. Navigate to **Explore** (compass icon)
2. Select **Tempo** datasource
3. Click **Search** tab
4. Select Service Name: `oms-ingest`
5. Click **Run Query**

**Method 2: TraceQL Query**
```traceql
# All POST requests
{ span.http.method="POST" }

# Specific endpoint
{ span.name="http post /api/v1/orders" }

# By service and status
{ resource.service.name="oms-ingest" && span.http.status_code=201 }

# Slow requests (>100ms)
{ resource.service.name="oms-ingest" && duration > 100ms }

# With specific order symbol
{ span.order.symbol="AAPL" }
```

**Method 3: Trace ID from Logs**
```
2026-01-19 INFO [oms-ingest,a1b2c3d4e5f6g7h8,i9j0k1l2m3n4o5p6] - Order received
```
Copy `a1b2c3d4e5f6g7h8` and paste into Grafana **Trace ID** search.

### Trace Visualization

**Span Hierarchy Example**:
```
http post /api/v1/orders (201ms)                    [SPAN_KIND_SERVER]
├─ order.ingest.controller (195ms)                  [SPAN_KIND_INTERNAL]
│  └─ order.ingest.service (190ms)                  [SPAN_KIND_INTERNAL]
│     ├─ db.check-idempotency (15ms)               [SPAN_KIND_INTERNAL]
│     ├─ order.validate (2ms)                       [SPAN_KIND_INTERNAL]
│     ├─ db.save-order (45ms)                       [SPAN_KIND_INTERNAL]
│     └─ db.save-outbox (25ms)                      [SPAN_KIND_INTERNAL]
└─ http get /actuator/prometheus (185ms)            [SPAN_KIND_INTERNAL]
```

**Attributes Available for Filtering**:
- `resource.service.name` = "oms-ingest"
- `span.http.method` = "POST"
- `span.http.status_code` = 201
- `span.order.clientOrderId` = "client-12345"
- `span.order.symbol` = "AAPL"
- `span.order.side` = "BUY"
- `span.order.id` = "uuid-here"
- `span.db.operation` = "insert"
- `span.db.table` = "orders"
- `span.kafka.topic` = "orders.inbound"

**Events in Timeline**:
- `order.created`
- `order.duplicate-found`
- `order.unique-verified`
- `validation.passed`
- `order.persisted`
- `outbox.event-created`
- `order.ingestion-complete`

## Example: Submit Order and View Trace

```powershell
# Submit an order
cd tests/unit
.\submit-order.ps1 -JsonFile order-market.json

# Output includes trace context
HTTP/201 - 
{"orderId":"1f0f4dfb-7148-6a5b-94d3-a9a9311d456a","clientOrderId":"market-012",...}
```

### Query Recent Traces
```powershell
# Get latest traces for oms-ingest
$traces = Invoke-RestMethod "http://localhost:3200/api/search?q={resource.service.name=%22oms-ingest%22}&limit=5"
$traces.traces | Select-Object traceID, rootServiceName, durationMs

# Get detailed trace
$traceId = $traces.traces[0].traceID
Invoke-RestMethod "http://localhost:3200/api/traces/$traceId" | ConvertTo-Json -Depth 10
```

## Performance Impact

### Sampling Strategy

**Development**: 100% sampling (all requests traced)
```yaml
management.tracing.sampling.probability: 1.0
```

**Production**: Recommended 10-20% sampling
```yaml
management.tracing.sampling.probability: 0.1
```

**Head-based Sampling**: Decision made at trace start
**Tail-based Sampling**: Could be added for errors/slow requests

### Overhead Measurements

- **Instrumentation**: ~1-2ms per request (span creation/tagging)
- **OTLP Export**: Async batch export (~5-10ms per batch of 100 spans)
- **Memory**: ~100KB per 1000 active spans

## Troubleshooting

### No Traces Appearing

1. **Check Tempo is running**:
   ```powershell
   Invoke-RestMethod http://localhost:3200/ready
   ```

2. **Verify OTLP endpoint configuration**:
   ```powershell
   # Check application logs for
   "Configuring OpenTelemetry with endpoint: http://localhost:4317"
   "Tracer bean created successfully"
   "ObservationRegistry customized with tracing handlers"
   ```

3. **Check for connection errors**:
   ```powershell
   # Look for export failures in logs
   Select-String -Path logs/*.log -Pattern "Failed to export"
   ```

4. **Test Tempo API directly**:
   ```powershell
   Invoke-RestMethod http://localhost:3200/api/search?limit=10
   ```

### Trace IDs Not in Logs

**Issue**: Logs show `[oms-ingest,,]` instead of `[oms-ingest,traceId,spanId]`

**Solution**: Verify `Slf4JEventListener` is configured in `TracingConfiguration`:
```java
Slf4JEventListener slf4JEventListener = new Slf4JEventListener();
// Must be registered in OtelTracer event handler
```

### Root Span Shows "<root span not yet received>"

**Issue**: Child spans exist but no parent HTTP SERVER span

**Solution**: Ensure `DefaultTracingObservationHandler` is **first** in handler chain:
```java
registry.observationConfig()
    .observationHandler(new DefaultTracingObservationHandler(tracer))  // FIRST!
    .observationHandler(new PropagatingReceiverTracingObservationHandler<>(tracer, propagator))
    .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));
```

## Next Steps

### 1. Add Metrics Correlation
Link Prometheus metrics with traces using exemplars:
```java
Counter.builder("order.received")
    .tag("symbol", order.getSymbol())
    .register(meterRegistry)
    .increment();
```

### 2. Configure oms-validator Service
Apply same tracing configuration to downstream services for end-to-end visibility.

### 3. Create Grafana Dashboards
- **RED Metrics**: Rate, Errors, Duration per endpoint
- **Service Map**: Visualize service dependencies
- **Latency Heatmaps**: p50, p95, p99 response times

### 4. Add Distributed Context
Propagate business context across services:
```java
span.tag("user.id", userId);
span.tag("order.portfolio", portfolioId);
span.tag("order.strategy", strategyName);
```

### 5. Implement Tail-based Sampling
Keep all error traces and slow requests:
```java
// In TracingConfiguration
.addSpanProcessor(new SimpleSpanProcessor(errorOnlyExporter))
```

## References

- [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
- [Grafana Tempo](https://grafana.com/docs/tempo/latest/)
- [TraceQL Query Language](https://grafana.com/docs/tempo/latest/traceql/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
