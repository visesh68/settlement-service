# Settlement Service

Payment capture and settlement with **idempotent authorize/capture**, **effectively-once
settlement** against a downstream that fails ~40% of the time, and **crash-safe,
resumable** draining. Java 21 · Spring Boot · PostgreSQL.

| | |
|---|---|
| **Live app** | https://settlement-service-jyol.onrender.com |
| **Invariants, one request** | https://settlement-service-jyol.onrender.com/admin/stats |
| **Metrics** | https://settlement-service-jyol.onrender.com/metrics |
| **Dead letters** | https://settlement-service-jyol.onrender.com/admin/dead-letters |
| **CI** | [GitHub Actions](../../actions) — suite + image + both gates against the container |

Deployed as a Docker image on Render (Singapore), backed by Neon PostgreSQL, with logs
streamed to Better Stack.

> Render's free tier sleeps after ~15 minutes idle. The first request then takes ~50s while
> the instance wakes. Nothing is lost — the outbox is durable and draining resumes on the
> next tick — but the first call may be slow.

> Design reasoning — the locking strategy, the alternatives rejected, the failure modes —
> is in **[WRITEUP.md](WRITEUP.md)**. This file is how to run it.

```
authorize ──▶ AUTHORIZED ──capture──▶ CAPTURED ──▶ outbox ──drain──▶ SETTLED
                   │                     (same transaction)   │
                   └──void──▶ FAILED                           └──▶ DEAD_LETTER
```

---

## Run it

### Docker (app + database, one command)

```bash
docker compose up --build
# http://localhost:8080
```

### Locally

Needs a PostgreSQL and JDK 21.

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/settlement
export DATABASE_USERNAME=settlement
export DATABASE_PASSWORD=...
mvn spring-boot:run
```

Flyway migrates on startup. Configuration is entirely environment-driven —
see [`.env.example`](.env.example) for every variable.

### Tests

```bash
mvn verify
```

55 tests, green from a clean checkout, **no Docker required**: the suite downloads and
runs a real PostgreSQL as a child process (Zonky embedded). Set `TEST_DATABASE_URL`
(plus `TEST_DATABASE_USERNAME` / `TEST_DATABASE_PASSWORD`) to run against an external
PostgreSQL instead.

---

## The two correctness gates

One command each, against any running instance.

```bash
# Gate 1 — idempotent capture under concurrency
./scripts/gate1-concurrent-capture.sh https://your-app.example.com 20

# Gate 2 — exactly-once settlement against the flaky downstream
./scripts/gate2-exactly-once-settlement.sh https://your-app.example.com 25 0.4
```

On Windows:

```powershell
.\scripts\gates.ps1 -BaseUrl https://your-app.example.com          # both gates
.\scripts\gates.ps1 -BaseUrl http://localhost:8080 -Gate 1 -Concurrency 20
.\scripts\gates.ps1 -BaseUrl http://localhost:8080 -Gate 2 -Batch 25 -FaultRate 0.4
```

**Gate 1** fires 20 concurrent captures at one payment twice — once sharing an
idempotency key, once with distinct keys — and asserts the payment is captured exactly
once with zero 5xx in both cases.

**Gate 2** captures a batch, then drains repeatedly *and concurrently* while the
downstream fails ~40% of calls, and asserts every payment is settled exactly once, none
twice, none lost.

Everything either gate asserts is also available in one request:

```bash
curl -s https://your-app.example.com/admin/stats | jq .invariants
```

```jsonc
{
  "captured_payments": 27,
  "double_settled_payments": 0,       // must always be 0
  "captured_without_outbox_row": 0,   // must always be 0
  "settled_but_not_captured": 0,      // must always be 0
  "safety_holds": true,               // true at every instant, mid-drain and mid-crash
  "fully_drained": true,              // liveness: nothing outstanding
  "converged": true,
  "dead_lettered": 0
}
```

`safety_holds` is the invariant that must never be false, even mid-drain.
`fully_drained` is liveness and is legitimately false while work is in progress.

---

## API

| method | path | notes |
|---|---|---|
| `POST` | `/payments` | `{ amount, currency, idempotency_key }` → 201. Same key + same body → the original 201. Same key + different body → **409**. Zero/negative/fractional amounts and unknown currencies → 400. |
| `POST` | `/payments/{id}/capture` | `{ idempotency_key }` → 200, and enqueues settlement in the same transaction. Already captured / voided / key reused → **409**. Unknown payment → 404. |
| `POST` | `/payments/{id}/void` | Moves an uncaptured authorization to terminal `FAILED`. |
| `GET` | `/payments/{id}` | `{ state, settlement_state, attempts, settlement_key, last_error, timings }` |
| `GET` | `/payments?limit=50` | Recent payments (backs the admin page). |
| `POST` | `/admin/drain?passes=3` | Drain now. Safe to call concurrently with itself and with the background tick. |
| `GET` | `/admin/stats` | Counts and invariants (above). |
| `GET` | `/admin/dead-letters` | Terminal failures — visible, never silently dropped. |
| `POST` | `/admin/mock-settlement/config` | `{ "failure_rate": 0.0 }` — pin the downstream's fault rate at runtime. |
| `POST` | `/mock-settlement` | The simulated provider. Sleeps 100–500 ms, fails ~40%, idempotent on the settlement key. |
| `GET` | `/healthz` | Liveness. Checks nothing external, by design. |
| `GET` | `/readyz` | Readiness, including the datastore. |
| `GET` | `/metrics` | Prometheus exposition. |
| `GET` | `/` | Minimal admin page. |

Responses carry `X-Correlation-Id` (echoed from the request, or minted) and
`Idempotent-Replay: true|false`.

### Quick tour

```bash
BASE=http://localhost:8080

ID=$(curl -sS -X POST $BASE/payments -H 'Content-Type: application/json' \
  -d '{"amount":125000,"currency":"INR","idempotency_key":"demo-1"}' | jq -r .id)

curl -sS -X POST $BASE/payments/$ID/capture -H 'Content-Type: application/json' \
  -d '{"idempotency_key":"demo-cap-1"}' | jq .

curl -sS -X POST "$BASE/admin/drain?passes=5" | jq .
curl -sS $BASE/payments/$ID | jq '{state, settlement_state, attempts, timings}'
```

The drain tick runs every 2 s on its own, so the explicit drain is only for
watching it happen.

---

## Deploying

The image is deployed, not a buildpack. Any host that runs a container works; two
blueprints are included.

### Render + Neon

**1. Neon — create the database.** Sign in at [neon.tech](https://neon.tech), create a
project (region **Singapore / ap-southeast-1**, to match `render.yaml`). On the dashboard,
open **Connection Details** and make sure **Connection pooling is OFF** — copy the
*direct* string, not the `-pooler` one:

> Flyway serialises migrations with a **session-scoped** advisory lock. Neon's pooler runs
> PgBouncer in transaction mode, which does not pin a session to a connection, so that lock
> becomes unreliable. The direct endpoint is the correct choice here, and `DB_POOL_MAX=10`
> keeps us far inside the free connection limit anyway.

Neon gives you something like:

```
postgresql://neondb_owner:npg_AbC123@ep-cool-frost-12345678.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
        └── username ──┘ └─ password ─┘ └──────────────── host ────────────────────────┘ └─ db ─┘
```

Split it into the three variables Render needs — note the `jdbc:` prefix, the credentials
removed from the URL, and **`channel_binding` dropped** (libpq understands it, the
PostgreSQL JDBC driver does not and will reject the URL):

| variable | value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://ep-cool-frost-12345678.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` |
| `DATABASE_USERNAME` | `neondb_owner` |
| `DATABASE_PASSWORD` | `npg_AbC123` |

**2. Render — deploy the blueprint.** Sign in at [render.com](https://render.com), connect
your GitHub account, then **New → Blueprint** and pick this repository. Render reads
[`render.yaml`](render.yaml): Docker runtime, free plan, Singapore, health check on
`/readyz`. It will prompt for the three `DATABASE_*` values above — they are `sync: false`,
so they live in Render's dashboard and never in git. Click **Apply**.

First build takes ~5 minutes (Maven downloads its dependencies). Flyway migrates on first
boot; there is no manual migration step.

**3. Verify.** Once the service is live:

```bash
curl -s https://<your-service>.onrender.com/readyz
./scripts/gate1-concurrent-capture.sh https://<your-service>.onrender.com 20
./scripts/gate2-exactly-once-settlement.sh https://<your-service>.onrender.com 25 0.4
```

> Render's free web service sleeps after ~15 minutes idle, and a sleeping instance is not
> draining its outbox. Nothing is lost — the outbox is durable and the next request wakes
> it — but for a background drainer, **Koyeb** (no sleep) or **Fly.io** with
> `min_machines_running = 1` is a better free home. [`fly.toml`](fly.toml) is included.

### Fly.io

```bash
fly launch --no-deploy --copy-config
fly secrets set DATABASE_URL='jdbc:postgresql://...?sslmode=require' \
                DATABASE_USERNAME='...' DATABASE_PASSWORD='...'
fly deploy
```

### Shipping logs to Grafana Cloud

Logs go to stdout as JSON always. Setting the `loki` profile *additionally* ships them to
Grafana Cloud Loki, which is what backs the public dashboard link:

```bash
SPRING_PROFILES_ACTIVE=loki
LOKI_URL=https://logs-prod-XXX.grafana.net/loki/api/v1/push
LOKI_USERNAME=<numeric user/instance id>
LOKI_PASSWORD=<API token with logs:write>
```

Get all three from Grafana Cloud → **Connections → Loki → Send logs** (the "URL", "User"
and a generated token).

An app-side appender rather than a platform log drain, for two reasons: Render forwards
syslog while Loki ingests HTTP, and this way the pipeline works identically on any host or
under `docker compose`. Without the profile the appender does not exist, so nothing to
misconfigure locally or in tests.

Labels are deliberately just `service` and `level`. Loki indexes labels, so putting
`payment_id` or `correlation_id` there would create a stream per payment; those live in the
log line, which is JSON, so `| json` promotes them to real fields.

Queries worth saving:

```logql
{service="settlement-service"} | json | msg =~ "event=settlement_retry_scheduled.*"
{service="settlement-service"} | json | msg =~ "event=settlement_dead_lettered.*"
{service="settlement-service"} | json | correlation_id = "<id from a capture response>"
```

The last one is the useful one: it returns the capture *and* every settlement attempt,
retry and dead-letter that came from it, because the correlation id is stored on the outbox
row rather than living only in the request thread.

To make it public: build a dashboard over those queries, then **Dashboard settings → Public
dashboard → Enable**. That URL needs no login.

### Metrics

`/metrics` is a standard Prometheus endpoint — point Grafana Cloud's scraper or a Fly
metrics config at it. p99 latency is `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))`;
buckets are exported rather than pre-computed quantiles so that aggregation across
instances is correct.

---

## Scaling

```bash
docker compose up --scale app=3
```

No configuration change. Instances partition the outbox between themselves via
`FOR UPDATE SKIP LOCKED`, and a lease plus a reaper means an instance that dies mid-drain
has its work picked up by the others. See [WRITEUP.md](WRITEUP.md#scaling-and-failover).

## Layout

```
src/main/java/com/settlement/
  service/PaymentService.java       authorize + capture, the locking strategy
  service/SettlementDrainer.java    claim, deliver, retry, dead-letter, reclaim
  service/MockSettlementService.java the flaky, idempotent downstream
  repo/OutboxRepository.java        the SQL that does the real work
  web/                              controllers, correlation-id filter
src/main/resources/db/migration/    Flyway schema
src/test/java/com/settlement/       55 tests, real PostgreSQL
scripts/                            the two correctness gates (bash + PowerShell)
```
