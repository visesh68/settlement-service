# Settlement Service

Payment capture and settlement with **idempotent authorize/capture**, **effectively-once
settlement** against a downstream that fails ~40% of the time, and **crash-safe,
resumable** draining. Java 21 · Spring Boot · PostgreSQL.

| | |
|---|---|
| **Live app** | https://settlement-service-jyol.onrender.com |
| **Invariants, one request** | https://settlement-service-jyol.onrender.com/admin/stats |
| **Metrics dashboard** | https://settlement-service-jyol.onrender.com/dashboard.html |
| **Metrics (Prometheus)** | https://settlement-service-jyol.onrender.com/metrics |
| **Dead letters** | https://settlement-service-jyol.onrender.com/admin/dead-letters |
| **Logs — Grafana** | [public dashboard](https://proudglider3361.grafana.net/public-dashboards/9a349a9c33c44b019c1771c653dbc4eb) — no login |
| **Logs — Better Stack** | [live tail](https://telemetry.betterstack.com/team/t579424/tail?s=l2656124) — **team access, sign-in required** |
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

## Demo

![Admin console: authorize, capture, settle, then the metrics dashboard](docs/media/admin-portal.gif)

Naming a settlement, authorizing it, capturing it, watching the drainer settle it against
a downstream that fails 40% of the time — then the metrics dashboard, which reads
`/metrics` directly in the browser. `double-settled` stays at 0 throughout; that is the
whole point.

### Logs — Grafana Cloud

![Grafana: the retry ladder and a dead-letter, in structured logs](docs/media/logs-grafana.gif)

The deployed instance's logs in Grafana. The retry panel is the one worth watching:
`event=settlement_retry_scheduled attempt=1 of 10` climbing through `attempt=9`, then
`event=settlement_dead_lettered attempts=10 of 10` — a settlement exhausting its attempts
against a downstream pinned to fail, ending up somewhere visible rather than silently
dropped. The other panel catches boot: Flyway reporting `Current version of schema
"public": 2`, then Tomcat and the OTLP exporter starting.

### Logs — Better Stack

![Better Stack live tail: structured JSON from the deployed instance](docs/media/logs-betterstack.gif)

The same service tailed live in Better Stack, from deploy through startup to graceful
shutdown. Every line is the JSON that `logstash-logback-encoder` writes to stdout —
`logger`, `thread`, `level`, `service` are real fields, not text to be regex'd, which is
what makes `| json | correlation_id = "…"` work as a query rather than a grep.

---

## Architecture

> Editable source: [`docs/diagrams/hld.excalidraw`](docs/diagrams/hld.excalidraw) — drop it
> into [excalidraw.com](https://excalidraw.com) to change or re-export it.

```mermaid
flowchart LR
  C["Clients<br/>admin UI · Postman · curl"]

  subgraph SVC["Settlement Service — Java 21 · Spring Boot 3 · Docker"]
    direction TB
    API["PaymentController<br/>authorize · capture · void"]
    PS["PaymentService<br/>idempotency · row locks"]
    OB["OutboxRepository<br/>SKIP LOCKED · leases"]
    DR["SettlementDrainer<br/>claim · retry · dead-letter"]
    MOCK["MockSettlementController<br/>simulated PSP<br/>~40% 500s · 100–500ms"]
    OBS["metrics · admin/stats<br/>healthz · readyz"]
  end

  DB[("Neon PostgreSQL<br/>payments · idempotency_records<br/>settlement_outbox · mock_settlements")]
  OBSV["Grafana Cloud · Loki<br/>Better Stack"]

  C -->|HTTP| API
  API --> PS
  PS --> OB
  OB --> DR
  PS -->|JDBC| DB
  OB -->|JDBC| DB
  DR -->|real HTTP| MOCK
  MOCK -->|its own ledger| DB
  OBS -.->|scrape / push| OBSV
```

Three things in that picture are deliberate:

- **The drainer reaches the provider over real HTTP**, not an in-process call — so timeouts,
  connection failures and 5xx are exercised for real rather than simulated.
- **The provider keeps its own table.** `mock_settlements` is conceptually on the far side of
  a network. The correctness gate asks *it* whether anything settled twice, so a bug in our
  bookkeeping cannot make the numbers look good.
- **The drainer is a scheduled tick, not a request thread.** Killing the process mid-drain
  loses nothing; the outbox is durable and a lease reaper picks the work back up.

---

## How it works

> Editable source: [`docs/diagrams/lld.excalidraw`](docs/diagrams/lld.excalidraw)

```mermaid
flowchart LR
  subgraph TX["ONE transaction — commits together or not at all"]
    direction TB
    T1["1 · SELECT … FOR UPDATE<br/>lock the payment row"]
    T2["2 · key already seen?<br/>replay the stored response"]
    T3["3 · state = AUTHORIZED?<br/>else 409"]
    T4["4 · UPDATE … WHERE state = AUTHORIZED"]
    T5["5 · INSERT settlement_outbox<br/>payment_id UNIQUE<br/>settlement_key minted once"]
    T6["6 · INSERT idempotency_record<br/>scope + key PRIMARY KEY"]
    T1 --> T2 --> T3 --> T4 --> T5 --> T6
  end

  subgraph DRAIN["Drain tick — separate transaction, every 2s"]
    direction TB
    D1["claim · FOR UPDATE SKIP LOCKED<br/>attempts+1 · lease 30s"]
    D2["POST /mock-settlement<br/>Idempotency-Key: settlement_key"]
    D3["2xx → SETTLED"]
    D4["5xx / timeout → PENDING + backoff"]
    D5["attempts exhausted → DEAD_LETTER"]
    D6["reaper · lease expired → PENDING<br/>the crash path"]
    D1 --> D2
    D2 --> D3
    D2 --> D4
    D4 --> D5
    D4 -.retry.-> D1
    D6 -.recovers.-> D1
  end

  TX ==> DRAIN
```

**The settlement key is minted once, at capture, and never regenerated on retry.** That
single fact is what makes every later redelivery safe.

**The dangerous window** is when the provider committed and we died before writing
`SETTLED`. The lease expires, the reaper requeues, and the *same* key is delivered again —
so the provider dedupes it. Delivery happened twice; settlement happened once. That is the
difference between at-least-once delivery and an exactly-once *effect*, and it is the thing
this service exists to demonstrate.

### The guards live in the database

So a bug in application code still cannot double-settle:

| Constraint | Guarantees |
|---|---|
| `payments.state` CHECK + `WHERE state='AUTHORIZED'` | one capture per payment |
| `settlement_outbox.payment_id` UNIQUE | one settlement job per capture |
| `settlement_outbox.settlement_key` UNIQUE | one dedupe key per job |
| `mock_settlements.settlement_key` PRIMARY KEY | the provider physically cannot settle a key twice |
| `idempotency_records (scope, key)` PRIMARY KEY | one stored answer per key |

---

## Technologies

| | |
|---|---|
| **Language / runtime** | Java 21 — virtual threads, so blocking JDBC plus a sleepy downstream stays cheap |
| **Framework** | Spring Boot 3.5 — web, JDBC, validation, actuator |
| **Data access** | Spring `JdbcTemplate` — hand-written SQL, because the locking *is* the design |
| **Database** | PostgreSQL 17 — `FOR UPDATE`, `FOR UPDATE SKIP LOCKED`, partial indexes, `RETURNING` |
| **Migrations** | Flyway — runs on startup, so a deploy migrates itself |
| **Connection pool** | HikariCP — sized above capture concurrency so requests queue on the row lock, not the pool |
| **Metrics** | Micrometer → Prometheus (`/metrics`) and OTLP (push, for hosts with no scraper) |
| **Logging** | Logback + `logstash-logback-encoder` (JSON to stdout), optional Loki appender |
| **Testing** | JUnit 5, AssertJ, Awaitility, Zonky embedded PostgreSQL — real Postgres, no Docker needed |
| **Build** | Maven, multi-stage Dockerfile |
| **CI** | GitHub Actions — suite, image build, and both correctness gates against the container |
| **Hosting** | Render (Docker, Singapore) + Neon PostgreSQL |
| **Observability** | Grafana Cloud, Better Stack, plus a built-in dashboard at `/dashboard.html` |
| **API collection** | Postman / newman — [`postman/`](postman/), 51 requests and 56 assertions |

No ORM, no message broker, no Redis. The outbox is a table, the queue is `SKIP LOCKED`, and
the idempotency store is a primary key. Everything that makes this correct is something
PostgreSQL already does.

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

65 tests, green from a clean checkout, **no Docker required**: the suite downloads and
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
  metrics/AppMetrics.java           throughput and latency
  metrics/InvariantMetrics.java     the safety invariants, as alertable gauges
  web/                              controllers, correlation-id filter
src/main/resources/db/migration/    Flyway schema (V1 base, V2 settlement name)
src/main/resources/static/          admin console + /dashboard.html metrics view
src/test/java/com/settlement/       65 tests, real PostgreSQL
scripts/                            the two correctness gates (bash + PowerShell)
postman/                            51-request collection, 7 scenario folders
grafana/                            dashboard JSON + Grafana Cloud setup
docs/diagrams/                      HLD and LLD Excalidraw sources
docs/media/                         admin console recording
```
