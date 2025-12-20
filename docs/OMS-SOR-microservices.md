# OMS-SOR Microservices Architecture

This document describes the microservices that comprise the OMS-SOR POC platform.

## Core Microservices (6 services)

### 1. **api-gateway**
- **Purpose**: Single entry point for all client requests (REST/WebSocket)
- **Tech**: Spring Boot 4.x + Spring Cloud Gateway (or Netflix Zuul)
- **Responsibilities**: 
  - Authentication/authorization (OAuth2/JWT via Keycloak)
  - Rate limiting and throttling
  - Routing to backend services
  - WebSocket connections for real-time order updates
  - Request/response logging and metrics

### 2. **oms-ingest**
- **Purpose**: Fast order validation and persistence (the "thin ingest" layer)
- **Tech**: Spring Boot 4.x with Virtual Threads
- **Responsibilities**: 
  - gRPC server for internal order submission
  - REST endpoints for UI order entry
  - Basic validation (schema, required fields)
  - Write to Postgres + Kafka outbox pattern (or LISTEN/NOTIFY)
  - Publish to `orders.inbound` topic
  - Return fast ACK to client (<50ms)

### 3. **oms-core**
- **Purpose**: Order lifecycle orchestration and state management
- **Tech**: Spring Boot 4.x with Virtual Threads
- **Responsibilities**:
  - Consume from `orders.inbound` topic
  - Enrichment, business validation, risk checks (via gRPC to risk-service)
  - State transitions (NEW → VALIDATED → ROUTING → FILLED → etc.)
  - Persist order state to Postgres
  - Publish to `orders.validated` topic
  - Query API for order status (gRPC/REST)
  - Handle order amendments and cancellations

### 4. **sor-engine**
- **Purpose**: Smart Order Routing decisions
- **Tech**: Spring Boot 4.x
- **Responsibilities**:
  - Consume from `orders.validated` topic
  - Fetch market data (from marketdata-simulator via gRPC)
  - Execute routing algorithm (weighted scoring: price, liquidity, fill rate, cost)
  - Generate child orders (splits/IOC/FOK)
  - Publish routing decisions to `orders.routed` topic with explainability
  - Log routing decisions to `route_audit` table

### 5. **execution-adapter**
- **Purpose**: Venue connectivity and FIX protocol handling
- **Tech**: Spring Boot 4.x + QuickFIX/J (FIX 4.4 library)
- **Responsibilities**:
  - Consume from `orders.routed` topic
  - Translate to FIX NewOrderSingle (35=D)
  - Send to broker simulators (FIX sessions)
  - Receive ExecutionReport (35=8) messages
  - Publish fills to `executions.fills` topic
  - Handle order lifecycle (acks, rejects, cancels)
  - Maintain FIX session state and heartbeats

### 6. **risk-service**
- **Purpose**: Pre-trade risk checks
- **Tech**: Spring Boot 4.x
- **Responsibilities**:
  - gRPC server for synchronous risk checks
  - Position tracking (consume from `executions.fills`)
  - Credit checks, concentration limits, regulatory checks
  - Maintain positions in Redis cache + Postgres
  - Expose risk metrics via Prometheus
  - Block orders that violate risk rules

---

## Supporting Services (2 services)

### 7. **marketdata-simulator** (POC only)
- **Purpose**: Mock real-time market data
- **Tech**: Spring Boot 4.x
- **Responsibilities**:
  - gRPC server for NBBO (National Best Bid/Offer) quotes
  - Generate simulated prices for test symbols (AAPL, TSLA, GOOGL, etc.)
  - Publish market data updates to `marketdata.quotes` topic
  - Simulate realistic price movements and spreads

### 8. **broker-simulator** (POC only)
- **Purpose**: Simulate venue behavior (FIX acceptor)
- **Tech**: Spring Boot 4.x + QuickFIX/J
- **Responsibilities**:
  - Accept FIX connections from execution-adapter
  - Simulate order acks, fills, rejects with configurable latency
  - Send ExecutionReports back via FIX
  - Simulate partial fills and order book behavior

---

## Architecture Diagram

```
┌─────────────────┐
│  api-gateway    │ ← REST/WebSocket client entry point
└────────┬────────┘
         ↓
┌─────────────────┐      ┌──────────────┐
│  oms-ingest     │─────→│   Kafka      │
└─────────────────┘      │  orders.     │
         ↓               │  inbound     │
    PostgreSQL           └──────┬───────┘
                                ↓
┌─────────────────┐      ┌──────────────┐
│   oms-core      │←─────│   Kafka      │
└────────┬────────┘      │  orders.     │
         │               │  validated   │
         ↓               └──────┬───────┘
    gRPC call                   ↓
         ↓               ┌──────────────┐
┌─────────────────┐     │  sor-engine  │
│  risk-service   │     └──────┬───────┘
└─────────────────┘            ↓
         ↑               ┌──────────────┐
         │               │   Kafka      │
    Kafka fills          │  orders.     │
         │               │  routed      │
         └───────────────┴──────┬───────┘
                                ↓
                         ┌──────────────┐
                         │ execution-   │
                         │  adapter     │
                         └──────┬───────┘
                                ↓ FIX 4.4
                         ┌──────────────┐
                         │  broker-     │
                         │  simulator   │
                         └──────────────┘
```

---

## Data Flow

### Order Submission Flow
1. Client → `api-gateway` (REST POST /api/orders)
2. `api-gateway` → `oms-ingest` (gRPC PlaceOrder)
3. `oms-ingest` → Postgres (INSERT orders) + Kafka outbox
4. `oms-ingest` → Client (200 OK with order ID)
5. Postgres NOTIFY → `oms-ingest` outbox publisher
6. `oms-ingest` → Kafka `orders.inbound` topic
7. `oms-core` consumes `orders.inbound`
8. `oms-core` → `risk-service` (gRPC CheckRisk)
9. `oms-core` → Postgres (UPDATE order state to VALIDATED)
10. `oms-core` → Kafka `orders.validated` topic
11. `sor-engine` consumes `orders.validated`
12. `sor-engine` → `marketdata-simulator` (gRPC GetQuote)
13. `sor-engine` executes routing algorithm
14. `sor-engine` → Kafka `orders.routed` topic
15. `execution-adapter` consumes `orders.routed`
16. `execution-adapter` → `broker-simulator` (FIX NewOrderSingle)
17. `broker-simulator` → `execution-adapter` (FIX ExecutionReport)
18. `execution-adapter` → Kafka `executions.fills` topic
19. `oms-core` consumes `executions.fills` (updates order state to FILLED)
20. `risk-service` consumes `executions.fills` (updates positions)
21. `api-gateway` pushes WebSocket update to client

---

## Service Communication Patterns

| From | To | Protocol | Latency Target | Notes |
|------|----|---------:|---------------:|-------|
| api-gateway | oms-ingest | gRPC | <20ms | Synchronous request/response |
| oms-ingest | oms-core | Kafka | <50ms | Asynchronous via `orders.inbound` |
| oms-core | risk-service | gRPC | <10ms | Synchronous risk check |
| oms-core | sor-engine | Kafka | <30ms | Asynchronous via `orders.validated` |
| sor-engine | marketdata-simulator | gRPC | <5ms | Synchronous quote fetch |
| sor-engine | execution-adapter | Kafka | <20ms | Asynchronous via `orders.routed` |
| execution-adapter | broker-simulator | FIX 4.4 | <10ms | Synchronous order submission |
| execution-adapter | oms-core | Kafka | <30ms | Asynchronous fills via `executions.fills` |

---

## Deployment Model

### Per-Service Configuration

Each service should be:
- **Separate Git repo** (or monorepo with separate Maven/Gradle modules)
- **Separate Docker image** (built via Dockerfile in each service root)
- **Separate Kubernetes Deployment** with:
  - Resource limits (CPU: 500m-2000m, Memory: 512Mi-2Gi)
  - Horizontal Pod Autoscaler (HPA) targeting 70% CPU
  - Readiness/liveness probes
  - ConfigMaps for configuration
  - Secrets for credentials (Postgres, Kafka, Keycloak)

### Shared Infrastructure

All services connect to:
- **Kafka cluster**: 3+ brokers (HA), Zookeeper or KRaft mode
- **PostgreSQL**: 1 primary + 1 read replica (for queries)
- **Redis**: 1 master + 1 replica (for caching/locks)
- **Keycloak**: OAuth2/JWT identity provider
- **Schema Registry**: Avro schema management (if using Avro)
- **Prometheus**: Metrics collection (scraped via `/actuator/prometheus`)
- **Grafana**: Dashboards
- **Jaeger**: Distributed tracing
- **OpenSearch**: Log aggregation

### Kubernetes Namespaces

- `oms-dev`: Development environment
- `oms-staging`: Staging environment
- `oms-prod`: Production environment (future)
- `infra`: Kafka, Postgres, Redis, Keycloak
- `observability`: Prometheus, Grafana, Jaeger, OpenSearch

---

## POC Simplifications

For the POC, you could **optionally consolidate**:

### Option A: Minimal (4 services)
- Merge `api-gateway` + `oms-ingest` → **oms-api**
- Merge `oms-core` + `risk-service` → **oms-core**
- Keep `sor-engine` separate
- Keep `execution-adapter` separate
- Add `broker-simulator` (POC only)
- Add `marketdata-simulator` (POC only)

### Option B: Full Separation (8 services) ✅ **Recommended**
Keep all 8 services separate to demonstrate:
- True microservices architecture
- gRPC inter-service communication
- Event-driven patterns with Kafka
- Independently scalable components

The **full separation** approach is what tier-1 firms expect and validates the architecture for production.

---

## Technology Stack Summary

| Component | Technology | Version |
|-----------|-----------|---------||
| Language | Java | 21+ (LTS) |
| Framework | Spring Boot | 4.x |
| Build Tool | Maven or Gradle | Latest |
| Concurrency | Virtual Threads | Project Loom |
| Messaging | Apache Kafka | 3.x |
| Database | PostgreSQL | 15+ |
| Cache | Redis | 7.x |
| gRPC | gRPC-Java + Protobuf | 1.60+ |
| FIX Protocol | QuickFIX/J | 2.3.1 |
| Container | Docker | Latest |
| Orchestration | Kubernetes | 1.28+ |
| Service Mesh | Istio (optional) | 1.20+ |
| Observability | Micrometer + OpenTelemetry | Latest |
| Metrics | Prometheus | 2.x |
| Dashboards | Grafana | 10.x |
| Tracing | Jaeger | 1.x |
| Logs | OpenSearch | 2.x |
| Security | OAuth2/JWT (Keycloak) | 23.x |

---

## Development Workflow

### 1. Service Development
```bash
# Clone repo
git clone https://github.com/gmui-gandg/oms-sor-poc.git
cd oms-sor-poc

# Each service has:
services/
  ├── api-gateway/
  │   ├── src/main/java/com/oms/gateway/
  │   ├── src/main/resources/application.yml
  │   ├── Dockerfile
  │   ├── pom.xml
  │   └── README.md
  ├── oms-ingest/
  ├── oms-core/
  ├── sor-engine/
  ├── execution-adapter/
  ├── risk-service/
  ├── marketdata-simulator/
  └── broker-simulator/
```

### 2. Local Development
```bash
# Start infrastructure (Docker Compose)
docker-compose up -d kafka postgres redis keycloak

# Run service locally
cd services/oms-ingest
./mvnw spring-boot:run
```

### 3. Build Docker Images
```bash
cd services/oms-ingest
docker build -t oms-ingest:latest .
```

### 4. Deploy to Kubernetes
```bash
kubectl apply -f k8s/namespaces/
kubectl apply -f k8s/infra/
kubectl apply -f k8s/services/
```

---

## Testing Strategy

### Unit Tests
- JUnit 5 + Mockito for service logic
- TestContainers for Kafka/Postgres integration tests
- Coverage target: >80%

### Integration Tests
- Spring Boot Test with embedded Kafka
- Testcontainers for full stack (Kafka, Postgres, Redis)
- Test end-to-end flows (order submission → fill)

### Performance Tests
- Gatling or JMeter for load testing
- Target: 1,000 orders/sec with <250ms p99 latency
- Sustained load for 10+ minutes

### Contract Tests
- Pact for gRPC contract testing between services
- Schema Registry validation for Kafka messages

---

## Next Steps

1. **Scaffold Spring Boot projects** for each service
2. **Define shared models** (proto files, Avro schemas)
3. **Implement oms-ingest** (first service, simplest)
4. **Set up Docker Compose** for local infrastructure
5. **Implement oms-core** (order lifecycle)
6. **Implement sor-engine** (routing algorithm)
7. **Implement execution-adapter** (FIX connectivity)
8. **Add observability** (Prometheus, Grafana, Jaeger)
9. **Write integration tests** (Testcontainers)
10. **Create Kubernetes manifests** (Helm charts)

---

## Migration & Upgrade Notes

This project includes a consolidated fresh-schema migration and framework upgrades. Follow these steps when applying changes to databases or upgrading environments:

- Fresh deployments: the ingest service includes a consolidated `V1__initial_schema.sql` (classpath: `db/migration`) which creates tables using `uuid` columns for primary and aggregate IDs. Start the application against a *new* Postgres instance and Flyway will apply the V1 schema automatically.
- Existing databases: do **not** run the fresh V1 schema against production DBs. Instead:
  - Take a full backup/snapshot of the database before any schema changes.
  - Validate that all existing identifier values are valid UUID strings (use queries like `SELECT aggregate_id FROM outbox_events WHERE aggregate_id !~ '^[0-9a-fA-F-]+'` to find non-UUIDs).
  - Create a staging copy of the database and run migration scripts there first. For controlled conversions, write an `ALTER TABLE` migration that uses `USING (aggregate_id::uuid)` (as a single transaction), recreate dependent indexes, and verify application compatibility.
- Application changes: this branch upgrades Spring Boot to 4.0.1 (Spring Framework 7.x) and standardizes identifiers to time-ordered UUIDv7. Ensure the following before upgrading runtime environments:
  - Update any service or client that consumes the REST/gRPC API to accept UUID path parameters and produce UUID-typed fields in DTOs. The POC currently returns `orderId` as a UUID in API responses.
  - If external consumers cannot accept UUIDs, keep DTOs as strings at the API boundary and convert inside the service to UUID internally.
- Flyway usage (manual run): from `services/oms-ingest` you can run migrations manually with:

```powershell
cd services\oms-ingest
mvn flyway:migrate
```

- Test & Staging: always validate migrations in staging with a representative dataset, run the full test suite (`mvn test` / `mvn verify`), and smoke-test Kafka flows and outbox publishing before rolling to production.


## References

- [Spring Boot 4.x Documentation](https://spring.io/projects/spring-boot)
- [gRPC-Java](https://github.com/grpc/grpc-java)
- [QuickFIX/J](https://www.quickfixj.org/)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Testcontainers](https://www.testcontainers.org/)
