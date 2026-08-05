# Metrics and the Grafana dashboard

`/metrics` is Prometheus exposition — machine-readable by design. It is not
meant to be looked at directly, and making it "look nice" would be solving the
wrong problem. Point a dashboard at it instead.

## What is exposed

Verified names, straight off a running instance.

### Safety — must never move

| Metric | Meaning |
|---|---|
| `settlement_safety_holds` | 1 while every safety invariant holds, 0 if any is violated |
| `settlement_double_settled` | Payments the **provider's own ledger** shows settled twice |
| `settlement_captured_without_outbox` | Captured but never queued — money owed, never sent |
| `settlement_settled_not_captured` | Settled but never captured — money sent for nothing |

These come from `InvariantMetrics`, which polls `StatsService` every 10s. They
are asked of the provider's tables, not our bookkeeping, so a bug in our own
state cannot make them look good.

### Evidence — non-zero is healthy

| Metric | Meaning |
|---|---|
| `settlement_total_deliveries` | Deliveries the provider received, including duplicates |
| `settlement_distinct_keys` | Distinct settlement keys actually settled |
| `settlement_redundant_deliveries` | Deliveries deduplicated away |

**Do not alert on `settlement_redundant_deliveries`.** It rising is the proof
that at-least-once delivery plus provider dedupe equals effectively-once
settlement. Driving it to zero would mean removing the evidence, not the risk.

### Liveness

`settlement_outbox_size{status=}`, `settlement_outstanding`,
`settlement_converged`, `settlement_settled_downstream_not_recorded`.

### Throughput, latency, idempotency

`settlement_attempts_total`, `settlement_success_total`,
`settlement_retry_total`, `settlement_dead_letter_total`,
`settlement_call_duration_seconds` (histogram, p50/p95/p99),
`settlement_lease_reclaimed_total`, `settlement_lease_lost_total`,
`idempotency_replay_total{scope=}`, `idempotency_key_reused_total`,
`payments_authorized_total`, `payments_captured_total`,
`payments_capture_race_lost_total`, plus `http_server_requests_seconds` with the
SLO buckets from `application.yml`.

## Getting the metrics into Grafana Cloud

Render gives you a public URL but no inbound scraper, so the service pushes
rather than being pulled. The OTLP registry is on the classpath but **disabled
unless `OTLP_ENABLED=true`** — with it off there is no exporter thread and no
outbound connection attempt.

1. In Grafana Cloud: **Connections → Add new connection → OpenTelemetry (OTLP)**.
   Note the OTLP gateway URL for your stack's region, your instance ID, and
   generate an API token with metrics-write scope.

2. Build the auth header value:

   ```bash
   printf '%s' "Basic $(printf '%s:%s' "$INSTANCE_ID" "$TOKEN" | base64 -w0)"
   ```

3. In **Render → your service → Environment**, add:

   ```
   OTLP_ENABLED=true
   OTLP_URL=https://otlp-gateway-<region>.grafana.net/otlp/v1/metrics
   OTLP_AUTH=Basic <the base64 blob from step 2>
   OTLP_STEP=60s
   DEPLOY_ENV=production
   ```

   Note the `/v1/metrics` suffix — the Micrometer OTLP registry posts metrics to
   that path, not to the bare gateway root.

4. Redeploy. Metrics start arriving within one `OTLP_STEP`.

### If you run your own Prometheus instead

Nothing above is needed. Just scrape:

```yaml
scrape_configs:
  - job_name: settlement-service
    metrics_path: /metrics
    scheme: https
    static_configs:
      - targets: ["your-service.onrender.com"]
```

## Importing the dashboard

**Dashboards → New → Import → Upload JSON file** → pick
`settlement-service-dashboard.json` → choose your Prometheus data source when
prompted.

The data source is a dashboard variable rather than a hardcoded UID, so it
imports into any stack without editing.

## Two things that will bite you

**OTLP metric naming may not match exactly.** The dashboard uses the names as
Prometheus exposes them on `/metrics`, which is what a direct scrape gives you.
Grafana Cloud's OTLP ingestion applies its own normalisation and may add or drop
a `_total` suffix, or append unit suffixes. If a panel is empty after switching
on OTLP, find the real name in Grafana's metric browser and adjust the query —
the shape of the dashboard is right either way. A direct Prometheus scrape needs
no adjustment.

**Render's free tier sleeps.** An idle instance pushes nothing, so expect gaps.
Those gaps are not incidents, but they do mean an alert on
`settlement_safety_holds == 0` goes quiet exactly when the service is down.
Pair it with a staleness check so absence is not read as health:

```
# fires on a real violation
settlement_double_settled > 0

# fires when the signal itself disappears
absent_over_time(settlement_safety_holds[10m])
```

## Suggested alerts

| Alert | Expression | Why |
|---|---|---|
| Double settlement | `settlement_double_settled > 0` | Real money moved twice. Page someone. |
| Lost capture | `settlement_captured_without_outbox > 0` | Money owed and never queued. |
| Phantom settlement | `settlement_settled_not_captured > 0` | Money sent for nothing. |
| Unreconciled | `settlement_settled_downstream_not_recorded > 0` for 15m | Provider settled, our books disagree. |
| Dead letters | `increase(settlement_dead_letter_total[15m]) > 0` | Terminal failures need a human. |
| Signal lost | `absent_over_time(settlement_safety_holds[10m])` | The safety alert cannot fire if the series is gone. |
