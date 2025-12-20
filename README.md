# OMS-SOR POC - Order Management System & Smart Order Routing

A proof-of-concept implementation of an institutional-grade Order Management System (OMS) with Smart Order Routing (SOR) capabilities, demonstrating modern microservices architecture patterns for equity trading platforms.

## 🏗️ Architecture Overview

This POC implements an event-driven microservices architecture using:

- **Spring Boot 3.2** with Java 21 Virtual Threads (Project Loom)
- **Apache Kafka** for asynchronous event streaming
- **gRPC** for low-latency synchronous service communication
- **PostgreSQL** for transactional persistence
- **Redis** for caching and distributed locking
- **Docker & Kubernetes** for containerization and orchestration

### Key Services

| Service | Port | gRPC Port | Description |
|---------|------|-----------|-------------|
| **oms-ingest** | 8080 | 9090 | Order validation and ingestion (thin ingest layer) |
| **oms-core** | 8081 | 9091 | Order lifecycle orchestration and state management |
| **sor-engine** | 8082 | 9092 | Smart order routing decisions |
| **execution-adapter** | 8083 | 9093 | FIX protocol execution venue connectivity |
| **risk-service** | 8084 | 9094 | Pre-trade risk checks and position tracking |
| **marketdata-simulator** | 8085 | 9095 | Mock market data provider (POC only) |
| **broker-simulator** | 8086 | 9096 | Mock broker FIX acceptor (POC only) |

### Data Flow

```
Client → API Gateway (REST/WebSocket)
         ↓
      OMS Ingest (gRPC, fast ACK <50ms)
         ↓
      PostgreSQL + Outbox (LISTEN/NOTIFY)
         ↓
      Kafka: orders.inbound
         ↓
      OMS Core (orchestration)
         ├→ Risk Service (gRPC)
         └→ Kafka: orders.validated
                   ↓
              SOR Engine (routing algorithm)
                   ↓
              Kafka: orders.routed
                   ↓
           Execution Adapter (FIX 4.4)
                   ↓
              Broker (venue)
                   ↓
              Kafka: executions.fills
                   ↓
              OMS Core (fills processing)
```

## 📋 Prerequisites

- **Java 21** (JDK 21 LTS)
- **Maven 3.9+**
- **Docker Desktop** (for local development)
- **Docker Compose** v2.x
- **VS Code** with recommended extensions (see `.vscode/extensions.json`)

### Verify Installation

```powershell
java -version       # Should show Java 21
mvn -version        # Should show Maven 3.9+
docker --version    # Should show Docker 24+
docker-compose version
```

## 🚀 Quick Start

### 1. Start Infrastructure

Start all backing services (Kafka, Postgres, Redis, etc.):

```powershell
cd oms-sor-poc
.\scripts\start-infra.ps1
```

This starts:
- PostgreSQL on `localhost:5432`
- Kafka on `localhost:9092`
- Redis on `localhost:6379`
- Keycloak on `http://localhost:8180`
- Prometheus on `http://localhost:9090`
- Grafana on `http://localhost:3000`

### 2. Build All Services

```powershell
.\scripts\build-all.ps1
```

This builds all Maven modules and creates Docker images.

### 3. Run Services Locally

**Option A: Run in VS Code (Recommended for Development)**

1. Open the project in VS Code
2. Press `F5` or use "Run and Debug" view
3. Select "All Services" compound configuration

**Option B: Run via Maven**

```powershell
# Terminal 1 - OMS Ingest
cd services\oms-ingest
mvn spring-boot:run

# Terminal 2 - OMS Core
cd services\oms-core
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --grpc.server.port=9091"
```

### 4. Test the System

**Submit a test order:**

```powershell
curl -X POST http://localhost:8080/api/v1/orders `
  -H "Content-Type: application/json" `
  -d '{
    "clientOrderId": "TEST-001",
    "accountId": "ACC-12345",
    "symbol": "AAPL",
    "side": "BUY",
    "orderType": "LIMIT",
    "quantity": 100,
    "limitPrice": 150.50,
    "timeInForce": "DAY"
  }'
```

The HTTP response contains an `orderId` value which is a UUID (time-ordered UUIDv7). Use that UUID to check order status.

**Check order status (use the UUID `orderId` returned in the POST response):**

```powershell
# Example (replace with the actual UUID returned by the POST):
curl http://localhost:8080/api/v1/orders/3fa85f64-5717-4562-b3fc-2c963f66afa6
```

**View metrics:**

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)
- Actuator: http://localhost:8080/actuator/prometheus

## 📁 Project Structure

```
oms-sor-poc/
├── docs/                          # Documentation
│   ├── OMS-SOR.prd               # Product Requirements Document
│   ├── OMS-SOR-design.md         # Detailed Design Document
│   ├── OMS-SOR-microservices.md  # Microservices Architecture Guide
│   └── diagrams/                 # Mermaid & SVG diagrams
├── proto/                        # Shared protobuf definitions
│   ├── order.proto
│   ├── risk.proto
│   └── marketdata.proto
├── shared/                       # Shared libraries
│   ├── common-models/           # DTOs, enums, proto-generated classes
│   ├── common-kafka/            # Kafka config, topics, serializers
│   └── common-observability/    # Metrics, tracing configuration
├── services/                    # Microservices
│   ├── oms-ingest/             # Order ingestion service
│   │   ├── src/
│   │   ├── Dockerfile
│   │   └── pom.xml
│   └── oms-core/               # Order orchestration service
│       ├── src/
│       ├── Dockerfile
│       └── pom.xml
├── infra/                       # Infrastructure as Code
│   ├── docker/
│   │   ├── docker-compose.yml  # Local dev environment
│   │   └── prometheus.yml
│   └── k8s/                    # Kubernetes manifests (future)
├── scripts/                     # Build & deployment scripts
│   ├── build-all.ps1
│   ├── start-infra.ps1
│   └── stop-infra.ps1
├── .vscode/                     # VS Code configuration
│   ├── launch.json             # Debug configurations
│   ├── settings.json
│   └── extensions.json
├── pom.xml                      # Parent POM
└── README.md                    # This file
```

## 🔧 Development Workflow

### Running Tests

```powershell
# Run all tests
mvn test

# Run tests for specific service
cd services\oms-ingest
mvn test

# Run integration tests (uses Testcontainers)
mvn verify
```

### Building Docker Images

```powershell
# Build all services
.\scripts\build-all.ps1

# Build specific service
cd services\oms-ingest
mvn spring-boot:build-image
```

### Database Migrations

Flyway migrations are located in `services/oms-ingest/src/main/resources/db/migration/`.

Migrations run automatically on application startup.

### Kafka Topics

Topics are auto-created by default. For production, pre-create topics:

```powershell
docker exec -it oms-kafka kafka-topics --create `
  --bootstrap-server localhost:9092 `
  --topic orders.inbound `
  --partitions 3 `
  --replication-factor 1
```

**Core Topics:**
- `orders.inbound` - New orders from ingest service
- `orders.validated` - Orders validated by OMS Core
- `orders.routed` - Routing decisions from SOR Engine
- `executions.fills` - Fill confirmations from execution adapter
- `marketdata.quotes` - Market data updates

### Viewing Kafka Messages

```powershell
# Console consumer
docker exec -it oms-kafka kafka-console-consumer `
  --bootstrap-server localhost:9092 `
  --topic orders.inbound `
  --from-beginning

# With key
docker exec -it oms-kafka kafka-console-consumer `
  --bootstrap-server localhost:9092 `
  --topic orders.inbound `
  --property print.key=true `
  --from-beginning
```

## 🔍 Observability

### Metrics

- **Prometheus**: http://localhost:9090
  - Query: `oms_ingest_orders_received_total`
  - Query: `oms_ingest_outbox_published_total`

- **Grafana**: http://localhost:3000
  - Default credentials: admin/admin
  - Import dashboards from `infra/grafana/dashboards/`

### Logs

```powershell
# View logs for specific service
docker logs -f oms-ingest

# View all service logs
docker-compose logs -f
```

### Health Checks

- OMS Ingest: http://localhost:8080/actuator/health
- OMS Core: http://localhost:8081/actuator/health

## 🏛️ Architecture Patterns

### 1. Transactional Outbox Pattern

**oms-ingest** uses the outbox pattern with PostgreSQL LISTEN/NOTIFY:

- Orders are saved to `orders` table
- Outbox events are saved to `outbox_events` table (same transaction)
- PostgreSQL trigger fires `NOTIFY` on insert
- Outbox publisher listens and publishes to Kafka
- Guarantees at-least-once delivery (idempotency on consumer side)

### 2. Event-Driven Architecture

- Services communicate via Kafka topics
- Async processing for non-critical path (validation, routing)
- Sync gRPC calls for critical path (risk checks)

### 3. Virtual Threads (Java 21)

All services use Virtual Threads for high-throughput I/O:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

### 4. Observability

- Micrometer metrics → Prometheus
- OpenTelemetry distributed tracing → Jaeger
- Structured logging → OpenSearch/ELK

## 🧪 Testing

### Unit Tests

```powershell
mvn test
```

### Integration Tests (Testcontainers)

```powershell
mvn verify
```

Integration tests use Testcontainers to spin up:
- PostgreSQL
- Kafka
- Redis

### Load Testing

Use Gatling or JMeter:

```powershell
# Target: 1,000 orders/sec with <250ms p99 latency
```

## 📦 Deployment

### Local Docker Compose

```powershell
docker-compose -f infra/docker/docker-compose.yml up -d
```

### Kubernetes (Future)

```powershell
kubectl apply -f infra/k8s/namespaces/
kubectl apply -f infra/k8s/infra/
kubectl apply -f infra/k8s/services/
```

## 🛠️ Configuration

### Application Properties

Each service has `application.yml` with environment variable overrides:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:oms}
    username: ${POSTGRES_USER:oms_user}
    password: ${POSTGRES_PASSWORD:changeme}
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_HOST` | localhost | PostgreSQL host |
| `POSTGRES_PORT` | 5432 | PostgreSQL port |
| `POSTGRES_DB` | oms | Database name |
| `POSTGRES_USER` | oms_user | Database user |
| `POSTGRES_PASSWORD` | changeme | Database password |
| `KAFKA_BOOTSTRAP` | localhost:9092 | Kafka bootstrap servers |
| `REDIS_HOST` | localhost | Redis host |
| `REDIS_PORT` | 6379 | Redis port |

## 📚 Documentation

- **PRD**: `docs/OMS-SOR.prd` - Product requirements and acceptance criteria
- **Design Doc**: `docs/OMS-SOR-design.md` - Detailed technical design
- **Microservices Guide**: `docs/OMS-SOR-microservices.md` - Service architecture
- **Diagrams**: `docs/diagrams/` - Architecture, sequence, deployment diagrams

## 🤝 Contributing

1. Create feature branch: `git checkout -b feature/my-feature`
2. Commit changes: `git commit -am 'Add feature'`
3. Push to branch: `git push origin feature/my-feature`
4. Submit pull request

## 📄 License

This is a POC project for demonstration purposes.

## 🆘 Troubleshooting

### Port Already in Use

```powershell
# Find process using port 8080
netstat -ano | findstr :8080

# Kill process
taskkill /PID <PID> /F
```

### Docker Issues

```powershell
# Clean up containers
docker-compose down -v

# Prune Docker system
docker system prune -a
```

### Maven Build Issues

```powershell
# Clean Maven cache
mvn dependency:purge-local-repository

# Rebuild from scratch
mvn clean install -U
```

## 📞 Support

For questions or issues:
- Review documentation in `docs/`
- Check logs: `docker-compose logs -f`
- Open GitHub issue

---

**Built with ❤️ for institutional trading platforms**
