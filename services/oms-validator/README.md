# OMS Validator Service

Order validation and risk management microservice for the OMS-SOR POC.

## Purpose

The `oms-validator` service is responsible for:
- **Consuming orders** from the `order.ingest` Kafka topic (populated by `oms-ingest`)
- **Validating order parameters** (symbol, quantity, price constraints)
- **Performing risk checks** (buying power, position limits, order value limits)
- **Updating order status** in the database (`NEW` → `VALIDATED` or `REJECTED`)
- **Publishing results** to downstream Kafka topics:
  - `order.validated` - orders that passed all checks
  - `order.rejected` - orders that failed validation/risk checks

## Architecture

### Event-Driven Flow
```
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│              │       │              │       │              │
│ oms-ingest   │──────>│   Kafka      │──────>│ oms-validator│
│              │       │order.ingest  │       │              │
└──────────────┘       └──────────────┘       └──────────────┘
                                                      │
                                                      │ validate
                                                      │ + risk check
                                                      │
                           ┌──────────────────────────┴───────────────────────┐
                           │                                                  │
                           ▼                                                  ▼
                    ┌──────────────┐                                  ┌──────────────┐
                    │   Kafka      │                                  │   Kafka      │
                    │order.validated│                                  │order.rejected│
                    └──────────────┘                                  └──────────────┘
                           │                                                  │
                           ▼                                                  ▼
                    (to routing)                                      (to notification)
```

### Components

#### 1. **OrderConsumer**
- Kafka listener on `order.ingest` topic
- Manual acknowledgment (commit only after successful processing)
- Concurrency: 3 consumer threads
- Error handling: failed messages are NOT committed (redelivered)

#### 2. **OrderValidationService**
- Core validation logic
- Parameter validation:
  - Symbol required and valid
  - Quantity > 0
  - Side (BUY/SELL) required
  - Order type required
  - Limit price required for LIMIT orders
  - Stop price required for STOP orders
- Business rule validation:
  - Symbol exists (stub - checks against symbol master)
  - Market hours (optional, disabled by default)
- Updates order status in database

#### 3. **RiskCheckService**
- Risk management rules:
  - **Max Order Value**: Default $1,000,000 (configurable)
  - **Max Position Size**: Default 100,000 shares (configurable)
  - **Buying Power Check**: Queries account service (stub)
- Calculates order value based on:
  - LIMIT orders: `quantity × limitPrice`
  - STOP orders: `quantity × stopPrice`
  - MARKET orders: skipped (no price available)

#### 4. **OrderPublisher**
- Publishes to `order.validated` topic on success
- Publishes to `order.rejected` topic with rejection reason on failure
- Uses order ID as Kafka message key for partitioning

### Database Schema

The service **reads and updates** the existing `orders` table created by `oms-ingest`:
- **Read**: loads order by `order_id` to check current status
- **Update**: sets `status` to `VALIDATED` or `REJECTED`
- **Idempotency**: only processes orders in `NEW` status

No new tables or migrations required.

## Configuration

Key configuration properties in `application.yml`:

```yaml
oms:
  validator:
    topics:
      ingest: order.ingest        # consume from
      validated: order.validated  # publish valid orders
      rejected: order.rejected    # publish rejected orders
    risk:
      max-order-value: 1000000    # $1M max order value
      max-position-size: 100000   # 100k shares max
      check-buying-power: true    # enable buying power check
    validation:
      check-market-hours: false   # disable market hours check (stub)
      check-symbol-exists: true   # enable symbol validation
```

### Kafka Consumer Settings
- **Group ID**: `oms-validator-group`
- **Auto Offset Reset**: `earliest` (reprocess from beginning if needed)
- **Manual Commit**: enabled (ack only after successful processing)
- **Concurrency**: 3 consumer threads
- **Max Poll Records**: 50

## Running the Service

### Prerequisites
- PostgreSQL running (same database as `oms-ingest`: `oms`)
- Kafka running (default `localhost:9092`)
- `oms-ingest` service running (to populate `order.ingest` topic)

### Build
```powershell
mvn -pl services/oms-validator -am clean package
```

### Run
```powershell
java -jar services/oms-validator/target/oms-validator-1.0.0-SNAPSHOT.jar
```

Or with custom config:
```powershell
java -jar services/oms-validator/target/oms-validator-1.0.0-SNAPSHOT.jar \
  --server.port=8081 \
  --oms.validator.risk.max-order-value=500000
```

### Health Check
```
http://localhost:8081/actuator/health
```

## Testing End-to-End

1. **Start infrastructure**:
   ```powershell
   .\scripts\start-infra.ps1
   ```

2. **Start oms-ingest**:
   ```powershell
   java -jar services/oms-ingest/target/oms-ingest-1.0.0-SNAPSHOT.jar
   ```

3. **Start oms-validator**:
   ```powershell
   java -jar services/oms-validator/target/oms-validator-1.0.0-SNAPSHOT.jar
   ```

4. **Submit an order** via REST:
   ```powershell
   $body = @{
     accountId='A123'
     clientOrderId='CO-TEST-001'
     symbol='AAPL'
     side='BUY'
     orderType='LIMIT'
     quantity=100
     limitPrice=150.00
     timeInForce='DAY'
   } | ConvertTo-Json

   Invoke-WebRequest -Method Post -Uri http://localhost:8080/api/v1/orders `
     -ContentType 'application/json' -Body $body -UseBasicParsing
   ```

5. **Check validator logs** - should see:
   - Consuming order from Kafka
   - Validating order parameters
   - Performing risk checks
   - Updating order status to VALIDATED
   - Publishing to `order.validated`

6. **Verify in database**:
   ```sql
   SELECT order_id, client_order_id, status, symbol, quantity, limit_price
   FROM orders
   WHERE client_order_id = 'CO-TEST-001';
   -- status should be 'VALIDATED'
   ```

## Monitoring

### Metrics (Prometheus format at `/actuator/prometheus`)
- `orders.processed` - total orders consumed
- `orders.validated` - orders that passed validation
- `orders.rejected` - orders that failed validation
- `orders.errors` - processing errors

### Logs
- `com.oms.validator` at DEBUG level
- Shows:
  - Kafka consumption (partition, offset)
  - Validation checks and results
  - Risk check outcomes
  - Publishing events

## Future Enhancements

1. **Real Symbol Master Integration**
   - Query market data service for valid symbols
   - Check if symbol is tradeable (not halted, not delisted)

2. **Real-Time Risk Checks**
   - Query account service for actual buying power
   - Query position service for current positions
   - Check margin requirements for leveraged accounts

3. **Market Hours Validation**
   - Check if market is open for the symbol's exchange
   - Allow pre-market / after-hours orders based on TIF

4. **Dead Letter Queue (DLQ)**
   - Publish persistently failing messages to a DLQ topic
   - Allows manual intervention without blocking processing

5. **Circuit Breaker**
   - Add Resilience4j for account/position service calls
   - Fail fast when downstream services are down

6. **Validation Rules Engine**
   - Externalize validation rules to a config service
   - Allow dynamic rule updates without redeployment

## Dependencies

- **Spring Boot 4.0.1** / Spring Framework 7.0.2
- **Spring Kafka** (consumer + producer)
- **Spring Data JPA** / Hibernate (order status updates)
- **PostgreSQL** (same `oms` database as oms-ingest)
- **Jackson** for JSON serialization
- **Micrometer** for metrics

## Related Services

- **oms-ingest**: Produces to `order.ingest` topic
- **oms-router** (future): Consumes from `order.validated` topic
- **oms-notification** (future): Consumes from `order.rejected` topic
