# Performance Testing Report — OMS SOR POC

Date: 2025-12-24

## Executive Summary
- Two k6 runs were compared: baseline (`tests/load/k6/results-latest.json`) and a ZGC experiment (`tests/load/k6/results-zgc.json`).
- Aggregated latency percentiles for the `POST /api/v1/orders` workload are similar between runs (p99 ≈ 96ms baseline, ≈103ms ZGC).
- Despite similar aggregated percentiles, isolated p99+ outliers up to ~2.46s were observed and cluster tightly around 2025-12-24T00:43:57-05:00.
- Automated correlation found no direct matches within ±5s of those outliers in GC logs, thread-dumps, Postgres activity snapshot, or Kafka consumer-group snapshots.

## Test Setup
- Workload: `tests/load/k6/load-test.js` (scenario `load_test`, POST /api/v1/orders).
- Baseline results file: `tests/load/k6/results-latest.json` (summarized to `logs/k6-summary-latest.txt`).
- ZGC experiment results file: `tests/load/k6/results-zgc.json` (summarized to `logs/k6-summary-zgc.txt`).
- Correlation artifacts: `logs/k6-outliers-correlations.txt`, `logs/k6-outliers-summary.txt`.

## Key Metrics

Baseline (results-latest)

```
count: 37857
min: 6.45 ms
avg: 18.99 ms
p50: 12.91 ms
p90: 22.59 ms
p95: 28.06 ms
p99: 96.05 ms
max: 1418.48 ms
```

ZGC run (results-zgc)

```
count: 37794
min: 6.26 ms
avg: 19.47 ms
p50: 12.14 ms
p90: 24.33 ms
p95: 31.96 ms
p99: 103.14 ms
max: 2000.70 ms
```

Notes:
- Aggregated percentiles are similar; ZGC did not materially reduce p99 in this experiment.
- Both runs have low central tendency (median/mean) and low p95; p99 is under ~110ms in both runs. However, both runs include rare multi-second maxima.

## Outlier Correlation Findings
- The correlator extracted the top 50 slow samples (threshold ≥500ms) and produced a correlation log: `logs/k6-outliers-correlations.txt`.
- A condensed summary of the top-10 outliers is saved at `logs/k6-outliers-summary.txt`. Example top samples include values from ~1.96s to ~2.46s clustered at timestamps around 2025-12-24T00:43:57-05:00.
- The summarizer found no direct hits (within ±5s) in the following artifacts:
  - Java GC logs (`logs/oms-ingest-gc.log.*`, `logs/oms-validator-gc.log.*`) — no multi-second stop-the-world GC pauses seen in inspected excerpts; ZGC cycles observed were typically tens of ms.
  - JVM thread-dumps (`logs/thread-dump-oms-ingest.txt`, `logs/thread-dump-oms-validator.txt`) — an `outbox-listener` thread was seen in a socket read state in a sampled dump.
  - Postgres `pg_stat_activity` snapshot (`logs/postgres-activity.txt`) — snapshot at the sampled moment showed only the diagnostic query active.
  - Kafka consumer-group snapshot (`logs/kafka-consumer-groups.txt`) — consumer group showed zero lag at sample time.

Interpretation:
- The lack of matching GC/thread/postgres/Kafka lines within ±5s suggests the observed cluster of multi-second client-side outliers is likely caused by a short-lived external I/O stall or synchronous blocking call that was not captured by the coarse snapshots available. Candidate causes include:
  - Postgres checkpoint or fsync activity causing brief network/disk stalls for connections (Postgres logs show historical long checkpoint completions in the collected container logs).
  - A transient container host I/O pressure event (docker host disk / overlayfs) affecting DB or app containers.
  - Network hiccup between services (socket reads seen in thread dump suggest some JDBC/network waiting).

## Root-cause hypothesis
- Most likely: transient Postgres I/O pause (checkpoint or fsync burst) or host-level disk I/O saturation produced a brief blocking period for JDBC socket reads, manifesting as clustered client-side request spikes (~1.9–2.5s) at the observed timestamp.
- Less likely: ZGC or Kafka broker internal pauses — ZGC logs show only short concurrent phases and Kafka snapshots show zero lag at the instant examined.

## Recommendations / Next Steps
1. Add targeted DB instrumentation and re-run the load test:
   - Enable `log_min_duration_statement` (e.g., 100ms) and `log_checkpoints = on` for the test DB to capture slow queries and checkpoint timings.
   - Enable `pg_stat_statements` and capture output before/during the test to see if any queries spike in duration.
2. Capture aligned thread-dumps at spike times:
   - Automate periodic thread-dumps (e.g., every 5s) during a focused re-run, or trigger a dump when latency exceeds a threshold via the load test runner callback.
3. Correlate with host-level I/O metrics:
   - Collect container host disk I/O and system load metrics (iostat, vmstat, docker stats) during re-run.
4. Broker/db level metrics:
   - Collect Postgres `pg_stat_activity` continuously (periodic sampling) and Kafka broker metrics (request latencies) during the test.
5. If DB I/O confirmed, consider:
   - Tuning Postgres checkpoint settings for test environment, increasing commit batching, or using faster storage for DB container.

## Artifacts
- k6 summaries: `logs/k6-summary-latest.txt`, `logs/k6-summary-zgc.txt`.
- Correlation artifacts: `logs/k6-outliers-correlations.txt`, `logs/k6-outliers-summary.txt`.
- JVM artifacts: `logs/oms-ingest-gc.log.*`, `logs/oms-validator-gc.log.*`, `logs/thread-dump-oms-*.txt`.
- DB artifact: `logs/postgres-activity.txt` and Postgres container logs (see `logs/` folder).

## If you want, I can (pick one):
- Run a targeted re-run with continuous `pg_stat_activity` sampling and `pg_stat_statements` enabled, and capture thread-dumps on latency threshold.
- Add automated capture (periodic) of host-level I/O metrics during the next load test.

---
Report generated from artifacts in `logs/` and `tests/load/k6/`.
