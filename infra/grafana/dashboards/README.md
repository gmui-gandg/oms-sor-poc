# OMS Grafana Dashboards

Pre-configured dashboards for monitoring the OMS microservices.

## Available Dashboards

| Dashboard | File | Description |
|-----------|------|-------------|
| **OMS Overview** | `oms-overview.json` | Order throughput, latency percentiles, error rates, service health |
| **OMS Kafka** | `oms-kafka.json` | Consumer lag, producer/consumer rates, listener processing time |
| **OMS JVM** | `oms-jvm.json` | Heap/non-heap memory, GC pauses, threads, CPU usage |

## Importing Dashboards

### Manual Import

1. Open Grafana at http://localhost:3000 (default: admin/admin)
2. Go to **Dashboards** → **Import**
3. Click **Upload JSON file** and select a dashboard file
4. Select the **Prometheus** data source
5. Click **Import**

### Prerequisites

Ensure Prometheus is configured as a data source in Grafana:
- Name: `prometheus`
- URL: `http://prometheus:9090` (if running in Docker) or `http://localhost:9090`

## Metrics Requirements

These dashboards expect Spring Boot Actuator metrics exposed via Micrometer:

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
```

## Dashboard Details

### OMS Overview
- **Orders (5m)**: Count of successful order submissions in last 5 minutes
- **Order Latency p99**: 99th percentile response time for order API
- **5xx Errors**: Server error count
- **Services Up**: Number of healthy OMS services
- **Request Rate**: Orders/second by endpoint
- **Latency Distribution**: p50/p95/p99 over time
- **Response Status Codes**: Breakdown by HTTP status
- **Kafka Consumer Rate**: Messages processed by listeners

### OMS Kafka
- **Consumer Lag**: Messages pending per topic (orders.inbound, orders.validated, orders.rejected)
- **Producer Message Rate**: Messages sent per second per topic
- **Consumer Message Rate**: Messages consumed per second per topic
- **Listener Processing Time**: p99 processing latency per listener

### OMS JVM
- **Heap Memory Used**: By memory pool (Eden, Survivor, Old Gen)
- **Non-Heap Memory**: Metaspace, Code Cache
- **GC Pause Time/Count**: Garbage collection activity
- **Threads**: Live, daemon, and peak thread counts
- **CPU Usage**: Process and system CPU utilization
- **Heap Usage %**: Gauge showing heap pressure
- **Uptime**: Service uptime in seconds
- **Classes Loaded**: Number of loaded classes
