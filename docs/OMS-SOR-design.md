OMS + SOR Detailed Design
=========================

Overview
- Purpose: This document provides the Detailed Design for the OMS + SOR POC referenced by `docs/OMS-SOR.prd`. It translates PRD-level requirements into implementable architecture, component responsibilities, API contracts, data models, operational runbooks, and test plans.
- Audience: Solution Architects, Java backend engineers, SRE/Platform, Security & Compliance, and vendor partners.

Chosen Tech Stack (POC)
- Language & Framework: Java on the JVM. Recommended framework: **Spring Boot 4.x** (rich ecosystem for Kafka, metrics, gRPC, security). Micronaut may be used if lower memory footprint is required.
  - **Java 25+ with Virtual Threads (Project Loom)** — improves throughput and simplifies async I/O handling; Spring Boot 4.x natively supports virtual threads for scalable, low-latency services.
- Messaging / Streaming: **Apache Kafka** (use Avro + Schema Registry for production; JSON acceptable for POC).
- Database: **PostgreSQL** as canonical transactional store; **Redis** (Lettuce client) for ephemeral caches, deduplication, and distributed locks.
- APIs & Protocols: **gRPC** for B2B service-to-service APIs, **REST** for UI and admin, **FIX 4.4** for execution adapters, and **WebSocket** for client notifications.
- Container & Orchestration: **Docker** images, **Kubernetes** (EKS or dev k8s) with Helm or k8s manifests.
- Cloud & Storage: **AWS** (S3 for snapshots/historical data); local dev can use MinIO as S3-compatible storage.
- CI/CD & Build: TeamCity / GitLab CI / Jenkins pipelines; Docker image promotion to registry.
- Observability: **Micrometer + OpenTelemetry** → **Prometheus** (metrics) + **Grafana** (dashboards) + **Jaeger** (tracing); logs to **OpenSearch**.
- Security & QA: **OAuth2/JWT** (Keycloak recommended for POC), **RBAC**, **SonarQube** for static analysis, **Black Duck** for SCA.
- Local/dev tooling: **Testcontainers** for integration tests, **Strimzi** for Kafka on k8s (or a managed Kafka). Use `kafka-console-producer/consumer` for quick tests.

Latency & Performance Considerations (2025 context)
- **Target latency for this POC**: 99th percentile <250ms end-to-end (order ingest → routing decision) — well-suited for Java/Spring Boot microservices.
- **When Java + Spring Boot is appropriate**:
  - Mid-tier latency requirements (100ms - 1s) typical for retail/institutional brokerage OMS.
  - Complex business logic (validation, risk checks, compliance, audit) where strong typing and mature tooling provide significant value.
  - Event-driven orchestration via Kafka for decoupled order flow and downstream processing.
- **When to consider polyglot/hybrid architecture** (future optimization beyond POC):
  - **Ultra-low latency requirements (<10ms, <1ms)** — Use **Rust, C++, or Go** for hot-path components:
    - Execution adapters and FIX gateways (sub-millisecond order submission).
    - Market data ingestion and tick processing (high-frequency data streams).
  - **High-frequency trading (HFT)** — C++/Rust/FPGA for critical path; Java for risk aggregation, reporting, and control plane.
  - **ML-driven SOR heuristics** — Python services (with gRPC or Kafka integration) for predictive routing models.
- **Java performance optimizations available in 2025**:
  - **Virtual Threads (Java 25+)** — dramatically improves I/O-bound throughput without reactive complexity.
  - **GraalVM Native Image** — compile Spring Boot to native binary for <100ms startup and lower memory footprint (useful for serverless or edge deployments).
  - **JVM tuning** — GC tuning (ZGC, Shenandoah for low-pause), JIT compiler optimizations, and profiling (JFR, async-profiler).
- **Industry precedent (2025)**:
  - Tier-1 broker OMS platforms (Bloomberg AIM, Fidessa, ION) remain predominantly Java-based with selective C++ for execution engines.
  - Cloud-native brokerages (Robinhood, newer fintechs) use polyglot microservices: Java (business logic), Go/Rust (gateways), Python (ML).
  - This POC design is **industry-standard for retail/active trader brokerage OMS+SOR** and aligns with 2025 best practices.

ASCII Architecture Diagram (high-level)
Clients
  +-----------------------------+
  |  Trading UIs / B2B clients  |
  +-------------+---------------+
    |
    | REST/gRPC/WebSocket (OAuth2/JWT)
    v
   +------+-------+    +----------------+
   |  API Gateway |<-->| OAuth2 Provider |
   +------+-------+    +----------------+
    |
    v
  +-----------------------------+     +------------------+
  |         OMS Ingest          |     | Market Data Feed |
  | (NewOrder, idempotency,     |     | (simulator or    |
  |  Kafka producer -> orders.*) |     |  real feed)      |
  +-------------+---------------+     +--------+---------+
    |                              |
    | orders.inbound               | marketdata.* -> Kafka
    v                              v
  +-----------------------------+   +-----------------------------+
  |         OMS Core            |   |         SOR Service         |
  | (consume orders.*, validate,|<--| (consume orders.validated,  |
  |  persist to Postgres, emit  |   |  marketdata, compute routes,|
  |  orders.validated)          |   |  emit orders.routing)       |
  +-------------+---------------+   +-------------+---------------+
    |                                 |
    | orders.routing                  | route_audit -> Postgres
    v                                 v
      +--------------------+               +--------------------+
      | Execution Adapter  |<--------------|  Redis (locks,     |
      | (FIX gateway or    |  fills/events |  dedup)            |
      |  simulator)        |               +--------------------+
      +---------+----------+
    |
    | ExecutionReports / Fills -> orders.execution
    v
  +---------------+
  | PostgreSQL DB |  (canonical state, order_events, fills, route_audit)
  +---------------+

Supporting infra:
- Apache Kafka cluster (topics: orders.*, marketdata.*, audit.*)
- Observability: Prometheus / Grafana / Jaeger / OpenSearch
- CI/CD: GitLab/TeamCity/Jenkins; SonarQube, Black Duck in pipelines


1. High-Level Architecture
- Components (POC scope):
  - API Gateway (Envoy/Ingress Controller)
  - OMS Ingest Service (Java)
  - OMS Core / State Machine Service (Java)
  - SOR Service (Java)
  - Risk Service (Java, stateless + optional stateful checks)
  - Execution Adapters (FIX adapter / simulator)
  - Market Data Simulator / Replay
  - Kafka cluster (topics listed below)
  - PostgreSQL (canonical order state & audit)
  - Redis (caches, locks, deduplication)
  - Observability stack (Prometheus, Grafana, OpenSearch)

1.1 Deployment Topology (POC)
- Single-region Kubernetes cluster (EKS or dev k8s) with namespaces: `oms`, `infra`, `test`.
- Helm chart or k8s manifests for each microservice, with resource requests/limits tuned for POC.
- External dependencies: Kafka (managed or local via Strimzi), Postgres, Redis, OAuth2 provider (Keycloak or cloud IAM), S3 bucket for artifacts.

2. Component Responsibilities & Contracts
2.1 API Gateway
- Terminates TLS, enforces authn/authz via OAuth2/JWT, routes REST/gRPC/WebSocket traffic to backend services.

2.2 OMS Ingest Service
- Responsibilities: Accept client orders, preliminary schema validation, idempotency check, enrich with metadata, write OrderCreated event to `orders.inbound` Kafka topic, return immediate ACK/NAK to client.
- Inputs: REST/gRPC/NewOrder request, WebSocket messages.
- Outputs: `orders.inbound` Kafka event.

2.3 OMS Core / State Machine
- Responsibilities: Consume `orders.inbound`, perform sync pre-trade validations (account, status, simple buying power), persist canonical order row(s) to Postgres inside a transactional boundary, emit `orders.validated` or `orders.rejected` events.
- Also consumes execution/fill events to update state and emits downstream audit/reporting events.

2.4 SOR Service
- Responsibilities: Consume `orders.validated` and market data topics, compute routing plan (single or multi-venue), record route audit, emit `orders.routing` (RoutingInstruction) events.
- Exposes admin API to configure heuristics and weights.

2.5 Execution Adapter(s)
- Responsibilities: Consume `orders.routing` events, translate to FIX messages (or simulated gateway call), send to destination, receive execution reports, emit `orders.execution` / `orders.fill` events.

2.6 Risk Service
- Responsibilities: Provide synchronous and asynchronous risk checks. Synchronous checks called by OMS Core (low-latency), asynchronous risk jobs subscribe to streams for portfolio-level checks and alerting.

3. Data Model
3.1 Postgres Tables (POC minimal)
-- orders
CREATE TABLE orders (
  order_id UUID PRIMARY KEY,
  client_id UUID NOT NULL,
  symbol TEXT NOT NULL,
  side VARCHAR(4) NOT NULL,
  qty BIGINT NOT NULL,
  price NUMERIC(18,6),
  type VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
  last_updated TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- order_events
CREATE TABLE order_events (
  event_id UUID PRIMARY KEY,
  order_id UUID REFERENCES orders(order_id),
  event_type VARCHAR(64) NOT NULL,
  payload JSONB,
  occurred_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- fills
CREATE TABLE fills (
  fill_id UUID PRIMARY KEY,
  order_id UUID REFERENCES orders(order_id),
  venue TEXT,
  executed_qty BIGINT,
  price NUMERIC(18,6),
  timestamp TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- route_audit
CREATE TABLE route_audit (
  route_id UUID PRIMARY KEY,
  order_id UUID REFERENCES orders(order_id),
  input_features JSONB,
  chosen_routes JSONB,
  reason_weights JSONB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);

3.2 Kafka Topics & Message Schemas (JSON for POC, Avro recommended production)
- `orders.inbound` { order_id, client_id, payload }
- `orders.validated` { order_id, status, validation_meta }
- `orders.routing` { order_id, routes: [{venue, qty, leg_id}], correlation_id }
- `orders.execution` { order_id, venue, exec_qty, exec_price, report_type }
- `marketdata.l1` { symbol, bid, ask, ts }

4. Service Contracts (examples)
4.1 gRPC NewOrder (proto snippet)
syntax = "proto3";
package oms;
message NewOrderRequest {
  string order_id = 1;
  string client_id = 2;
  string symbol = 3;
  int64 qty = 4;
  double price = 5;
  string side = 6; // BUY/SELL
  string type = 7; // MARKET/LIMIT
}
message NewOrderResponse { string status = 1; string message = 2; }
service Ingest { rpc NewOrder(NewOrderRequest) returns (NewOrderResponse); }

4.2 REST Examples
- POST /v1/orders  -> New order (returns 202 + order_id)
- GET /v1/orders/{order_id} -> order status
- POST /v1/admin/sor/config -> update SOR weights

4.3 FIX (Execution Adapter)
- Use FIX 4.4 for POC messaging; ExecutionAdapter translates RoutingInstruction into FIX NewOrderSingle messages and handles ExecutionReport messages to convert back into `orders.execution` events.

5. SOR Algorithm Design (POC)
5.1 Objectives
- Provide a deterministic, explainable routing decision using a weighted scoring function combining price, latency, fill probability, fee, and compliance constraints.

5.2 Inputs (features)
- NBBO / top-of-book prices per venue
- Historical fill probability per venue (ML or heuristics)
- Average round-trip latency to venue
- Fee / rebate per venue
- Risk constraints (e.g., venue disallowed for a client)

5.3 Scoring & Decision
- Score(venue) = w_price * price_score + w_latency * latency_score + w_fillprob * fillprob_score + w_fee * fee_score
- Price_score: normalized against NBBO (best = 1.0)
- Latency_score: normalized inversely (lower latency -> higher score)
- Fillprob_score: historical success probability
- Fee_score: normalized to prefer lower fees
- Weights (w_*) configurable via admin API; store configs in Postgres or ConfigMap for POC.

5.4 Routing Modes
- Single-best: choose top-scoring venue.
- Split: allocate across multiple venues proportional to score (e.g., based on available liquidity heuristics).
- Staged: send to primary venue with time-based fallback to secondary(s).

5.5 Explainability & Audit
- For every routing decision, persist `input_features`, per-venue scores, and final chosen routes into `route_audit` table for regulatory traceability.

6. State, Consistency & Idempotency
6.1 Event Sourcing vs Canonical DB
- POC will use a dual-write approach: primary events in Kafka + canonical snapshots in Postgres. For production, consider transactional outbox or event-sourcing.

6.2 Idempotency
- Clients provide `client_order_id` for deduplication; OMS Ingest stores recent IDs in Redis with TTL.
- Handlers must be idempotent: use order_id as the unique key and apply upsert semantics.

6.3 Exactly-once / At-least-once
- Kafka provides at-least-once delivery; implement deduplication and idempotent processing at consumers, and consider Kafka transactions + transactional outbox pattern for stronger guarantees.

7. Operational Design
7.1 Kubernetes Manifests (POC)
- Provide simple Deployment/Service manifests for each microservice with liveness/readiness probes, resource requests/limits, HPA for scaling by CPU or custom metrics.

7.2 CI/CD
- Pipelines (GitLab/TeamCity/Jenkins): build → unit tests → static analysis (SonarQube) → container image → SCA (Black Duck) → push to registry → deploy to dev namespace.

7.3 Backups & DR (POC minimal)
- Postgres daily snapshot to S3; Kafka topics retention configured to support replay for POC.

8. Observability & SLOs
8.1 Metrics
- Expose Prometheus metrics from each service (requests/sec, processing latency histograms, Kafka lag, DB latency, error rates).

8.2 Tracing
- Integrate OpenTelemetry with Jaeger-compatible traces; propagate trace IDs across gRPC/REST and Kafka message handlers.

8.3 Dashboards & Alerts
- Grafana dashboards: Order throughput, end-to-end latency, SOR decision latency, Kafka consumer lag, DB errors.
- Alerting: paging on high error rate, consumer lag above threshold, SLO degradations.

9. Security & Compliance
9.1 Authentication & Authorization
- OAuth2/JWT at the gateway; services validate JWT and enforce RBAC for admin endpoints.

9.2 Secrets & Supply Chain
- Use cloud secret manager or Kubernetes secrets for DB credentials; run SCA and static analysis in CI; require vendor dependencies to pass SCA gating.

9.3 Audit & Retention
- Persist order_events and route_audit for at least 30 days in Postgres (POC) and replicate to S3 for longer retention as needed.

10. Testing & Validation
10.1 Unit & Integration Tests
- Unit tests for core logic, integration tests for Kafka flows using testcontainers or embedded Kafka for POC.

10.2 Load & Chaos Testing
- Traffic generator (Gatling or custom Java/Go tool) to simulate orders at target throughput.
- Failure injection: kill service pods, saturate network, simulate destination latency, verify recovery and message handling.

10.3 Performance Benchmarks (POC targets)
- Throughput: 1,000 orders/sec (configurable).
- Latency: 99th percentile < 250ms for ingest → routing decision in POC environment.

11. Rollout & Cutover Plan (POC → Prod considerations)
- Validate assumptions: Kafka durability, DB scale, SOR algorithm behavior under real market conditions.
- Harden: move to transactional outbox, stronger exactly-once guarantees if required, multi-region Kafka, Postgres clustering, advanced observability.

12. Appendices
12.1 Example Order JSON
{
  "order_id": "...",
  "client_id": "...",
  "symbol": "AAPL",
  "qty": 100,
  "price": 177.25,
  "side": "BUY",
  "type": "LIMIT"
}

12.2 Example RoutingInstruction (orders.routing)
{
  "order_id": "...",
  "routes": [
    {"venue": "EX1","qty": 50},
    {"venue": "EX2","qty": 50}
  ],
  "reason_weights": {"price":0.5, "latency":0.2, "fillprob":0.2, "fee":0.1}
}

12.3 Next Implementation Tasks (starter)
- Implement `oms-ingest` service with NewOrder gRPC + kafka producer.
- Implement `oms-core` consumer to validate and persist orders.
- Implement lightweight `sor-service` with single-best routing and `route_audit` persistence.
- Implement `execution-simulator` to accept RoutingInstruction and emit fills.

References & Industry Precedents
- Architecture Guidance
  - `docs/OMS-SOR.prd` — Product Requirements Document for this POC
  - `docs/TradeStation-Architect-Position.txt` — Technology stack and architect role description

- Microservices & Event-Driven Architecture (foundational)
  - Martin Fowler, "Microservices" (2014) — https://martinfowler.com/articles/microservices.html
    - Seminal article on microservice patterns; cites Netflix, Guardian, comparethemarket.com as pioneers
  - Sam Newman, "Building Microservices" (O'Reilly, 2021) — includes financial services patterns and case studies
  - Martin Kleppmann, "Designing Data-Intensive Applications" (O'Reilly, 2017) — event sourcing, Kafka, consistency patterns used in trading platforms

- Financial Services & Trading Systems (industry patterns)
  - Adrian Cockcroft (Netflix/AWS), "Microservices at Scale" — widely cited in fintech for resilience and polyglot architecture patterns
  - Confluent (Kafka) Financial Services Case Studies — Capital One, Rabobank, and anonymized tier-1 broker implementations
    - https://www.confluent.io/customers/ (search: financial services)
  - AWS Financial Services Blog — architecture patterns for regulated workloads and event-driven systems
    - https://aws.amazon.com/blogs/industries/financial-services/
  - InfoQ Financial Services Architecture articles — QCon talks on OMS modernization, distributed trading platforms
    - https://www.infoq.com/ (search: "trading platform microservices", "OMS architecture")

- Technology-Specific References
  - Spring Boot documentation — microservices best practices, Kafka integration, observability
    - https://spring.io/projects/spring-boot
  - Kubernetes patterns for stateful workloads — https://kubernetes.io/docs/tutorials/stateful-application/
  - Prometheus/Grafana best practices for financial systems — https://prometheus.io/docs/practices/
  - OpenTelemetry distributed tracing — https://opentelemetry.io/

- Standards & Protocols
  - FIX Protocol — https://www.fixtrading.org/ (FIX 4.4 for POC, FIX 5.0 for production consideration)
  - gRPC documentation — https://grpc.io/docs/
  - Avro schema registry — https://docs.confluent.io/platform/current/schema-registry/

- Recommended Conference Sessions (search for recordings)
  - QCon, Kafka Summit, AWS re:Invent — search: "order management", "trading platform", "financial microservices"
  - Speakers from JPMorgan, Goldman Sachs, Bloomberg occasionally present high-level patterns (no proprietary details)

Note on Tier-1 Broker OMS Architectures
- Tier-1 financial firms (Goldman Sachs, Morgan Stanley, Bloomberg AIM, Fidessa, ION) use Java-based microservices and event-driven architectures for OMS/execution platforms but rarely publish detailed white papers due to competitive/regulatory concerns.
- Industry consensus (from conference talks, vendor case studies, and practitioner blogs): event-driven microservices with Kafka, polyglot persistence (Postgres + Redis + caches), and cloud-native deployment (Kubernetes) are standard patterns for modern OMS platforms.
- This POC design aligns with observed best practices from publicly available fintech architecture discussions and vendor-published patterns.
