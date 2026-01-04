# OMS Load Tests (k6)

Load testing scripts for the Order Management System using [k6](https://k6.io/).

## Prerequisites

### Install k6

**Windows (winget):**
```powershell
winget install k6 --source winget
```

**Windows (Chocolatey):**
```powershell
choco install k6
```

**Docker:**
```powershell
docker pull grafana/k6
```

## Quick Start

1. **Start the OMS services:**
   ```powershell
   # Start infrastructure
   .\scripts\start-infra.ps1
   
   # Start oms-ingest (in terminal 1)
   java -Dgrpc.server.port=9080 -jar services\oms-ingest\target\oms-ingest-1.0.0-SNAPSHOT.jar
   
   # Start oms-validator (in terminal 2)
   java -jar services\oms-validator\target\oms-validator-1.0.0-SNAPSHOT.jar
   ```

2. **Run a smoke test:**
   ```powershell
   cd tests\load\k6
   k6 run scenarios/smoke.js
   ```

## Test Scenarios

| Scenario | Command | Purpose |
|----------|---------|---------|
| **Smoke** | `k6 run scenarios/smoke.js` | Quick validation (1 VU, 30s) |
| **Load** | `k6 run load-test.js` | Standard load test (ramp 10→50 VUs) |
| **Stress** | `k6 run scenarios/stress.js` | Find breaking point (ramp to 300 VUs) |
| **Soak** | `k6 run scenarios/soak.js` | Long-running stability (1 hour) |
| **Spike** | `k6 run scenarios/spike.js` | Sudden traffic surge simulation |
| **Constant Rate** | `k6 run scenarios/constant-rate.js` | Fixed 100 orders/sec |

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080` | OMS ingest service URL |
| `DEBUG` | `false` | Enable verbose logging |

**Examples:**
```powershell
# Test against different environment
k6 run -e BASE_URL=http://oms-ingest:8080 load-test.js

# Enable debug output
k6 run -e DEBUG=true scenarios/smoke.js
```

### Override Options

```powershell
# Custom duration and VUs
k6 run --vus 20 --duration 2m load-test.js

# Output to JSON file
k6 run --out json=results.json load-test.js
```

## Prometheus/Grafana Integration

Output metrics to Prometheus for visualization in Grafana:

```powershell
# Start Prometheus Remote Write receiver (in docker-compose)
k6 run --out experimental-prometheus-rw load-test.js
```

Or use the statsd output with a Prometheus statsd exporter:

```powershell
k6 run --out statsd load-test.js
```

## Test Data Generation

The load test generates realistic order data:

### Symbol Distribution
- **60%** High-volume: AAPL, MSFT, GOOGL, AMZN, NVDA
- **30%** Medium-volume: META, TSLA, JPM, V, JNJ
- **10%** Low-volume: WMT, PG, HD, BAC, DIS

### Order Distribution
- **70%** LIMIT orders, **30%** MARKET orders
- **50%** small (10-100 shares), **35%** medium (100-500)
- **12%** large (500-2000), **3%** block (2000-10000)

### Accounts
- 3 Institutional accounts (ACC-INST-*)
- 2 Hedge fund accounts (ACC-HF-*)
- 5 Retail accounts (ACC-RET-*)

## Thresholds

Default thresholds that fail the test:

| Metric | Threshold |
|--------|-----------|
| p95 latency | < 500ms |
| p99 latency | < 1000ms |
| Success rate | > 95% |
| Failed orders | < 100 |

## Interpreting Results

```
     ✓ status is 200 or 201
     ✓ response has orderId
     ✓ order was created

     checks.........................: 100.00% ✓ 3000      ✗ 0
     data_received..................: 1.2 MB  20 kB/s
     data_sent......................: 890 kB  15 kB/s
     http_req_duration..............: avg=45ms min=12ms med=38ms max=312ms p(90)=78ms p(95)=95ms
     iterations.....................: 1000    16.67/s
     orders_created.................: 1000    16.67/s
     success_rate...................: 100.00% ✓ 1000      ✗ 0
```

**Key metrics to watch:**
- `http_req_duration` p95/p99 - API latency
- `orders_created` - Throughput
- `success_rate` - Error rate
- `iterations` - Requests completed

## Docker Execution

Run without installing k6:

```powershell
docker run --rm -i --network host `
  -v ${PWD}:/scripts `
  grafana/k6 run /scripts/load-test.js
```

## CI/CD Integration

```yaml
# GitHub Actions example
- name: Run k6 load test
  uses: grafana/k6-action@v0.3.1
  with:
    filename: tests/load/k6/scenarios/smoke.js
  env:
    BASE_URL: http://localhost:8080
```

## Troubleshooting

### Connection refused
Ensure services are running and healthy:
```powershell
curl http://localhost:8080/actuator/health
```

### High error rate
Check service logs for errors:
```powershell
# If running in Docker
docker logs oms-ingest
docker logs oms-validator
```

### Slow performance
- Check Kafka consumer lag in Grafana
- Monitor JVM heap usage
- Verify PostgreSQL connection pool
