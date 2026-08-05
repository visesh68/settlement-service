# Idempotent capture + exactly-once settlement — design notes

## State machine and data model

```
payments            (new) --authorize--> AUTHORIZED --capture--> CAPTURED   terminal
                                                    \--void----> FAILED     terminal

settlement_outbox   PENDING --claim--> IN_FLIGHT --2xx-----------> SETTLED       terminal
                                           |     \--5xx/timeout--> PENDING       (backoff)
                                           |                    \-> DEAD_LETTER  terminal
                                           \--lease expiry (crash)--> PENDING
```

Four tables (`src/main/resources/db/migration/V1__initial_schema.sql`, Flyway):

| table | purpose | the load-bearing constraint |
|---|---|---|
| `payments` | amount in integer minor units, state, timings | `CHECK (amount_minor > 0)`; `(state='CAPTURED') = (captured_at IS NOT NULL)` |
| `idempotency_records` | one row per `(scope, key)` with the request fingerprint and the stored response | `PRIMARY KEY (scope, idempotency_key)` |
| `settlement_outbox` | the work queue, written in the capture transaction | `payment_id UNIQUE`, `settlement_key UNIQUE` |
| `mock_settlements` | the *provider's* ledger, not ours | `settlement_key PRIMARY KEY` |

Money is `BIGINT` minor units everywhere. Jackson is configured with
`accept-float-as-int: false`, so `12.34` is a 400 rather than a silent truncation to 12.

`FAILED` exists so that "an already-captured **or failed** payment cannot be re-captured"
is a reachable, tested path rather than a claim about a state nothing can enter;
`POST /payments/{id}/void` is what enters it.

## Idempotent capture

Capture runs in one transaction that **begins by locking the payment row**
(`SELECT … FOR UPDATE`), then: check the idempotency key → check the state →
transition, insert the outbox row, and store the response. All in one commit.

The row lock is the serialization point. Twenty concurrent captures of one payment
queue on it and execute one at a time, so the read-check-write sequence is not a race.
Under READ COMMITTED, a transaction that waited on the lock takes a fresh snapshot for
its next statement, so it *sees* the winner's committed capture and committed
idempotency record — that is what makes the replay check correct rather than racy.
The result:

- **same key ×20** → one capture, twenty identical 200s (nineteen replayed);
- **distinct keys ×20** → one capture, one 200, nineteen 409s;
- never a 500, because losing a race is an expected outcome, not an error.

Authorize has no row to lock, so the idempotency table's primary key *is* the mutual
exclusion: a concurrent duplicate blocks on the uncommitted key, then gets a
duplicate-key error and replays the winner's response. Its own payment row rolls back,
so no second payment exists.

Same key + different body → 409, decided by comparing a SHA-256 fingerprint of the
canonical request (`amount|currency` for authorize, the payment id for capture). Hashing
rather than storing the body keeps caller input out of the database and the logs.

**Alternatives considered.** *Optimistic `version` CAS* — fine for the state flip, but
it cannot make "flip the state, enqueue settlement, and record the idempotent response"
one decision; the retry loop would be doing by hand what a row lock does for free.
*Unique constraint alone (no lock)* — works for authorize, but capture must also
read-then-decide against payment state, and a bare unique constraint gives you the
409 without telling you whether to replay. *Advisory locks* — same semantics as the
row lock but detached from the row they protect, so a missed unlock has no natural
recovery. The row lock is held for microseconds and touches no network.

## Exactly-once settlement

Strictly: **at-least-once delivery + provider dedupe on the settlement key =
effectively-once settlement.** Exactly-once delivery over a network is not achievable,
and any design claiming it is really doing this. Four mechanisms:

1. **Transactional outbox.** The outbox row is inserted in the *same transaction* as the
   capture, so "captured" and "will be settled" commit together or not at all.
   `payment_id UNIQUE` means even a logic bug cannot enqueue two jobs for one payment.
2. **A stable settlement key**, minted once at capture and never regenerated on retry.
   Every redelivery is byte-identical to the provider, which dedupes on it. This single
   fact is what makes retrying safe.
3. **`FOR UPDATE SKIP LOCKED` claim.** Concurrent drain passes take *disjoint* row sets,
   because each skips what the other has locked instead of blocking on it. No distributed
   lock, no leader election, no partitioning scheme.
4. **A lease, checked on completion.** A claim records `lease_owner` and
   `lease_expires_at`. Every terminal write is `WHERE status='IN_FLIGHT' AND lease_owner=?`,
   so a slow drainer whose lease already expired cannot stamp over the drainer that took
   over from it — it logs a lost race instead.

Failures retry with exponential backoff and **full jitter**. The jitter matters more than
the exponent: without it a batch that fails together retries together, and a struggling
downstream gets a synchronised wave every round. After `SETTLEMENT_MAX_ATTEMPTS` (10) the
item is **dead-lettered** — terminal, listed at `/admin/dead-letters`, counted in metrics,
never silently dropped.

`attempts` is incremented **at claim time, not at completion**. If the process dies
mid-flight the attempt is still counted, so a request that reliably kills the drainer
burns its attempts and dead-letters rather than looping forever.

## How a mid-drain crash resumes

There is no in-memory state to rebuild. A drainer that dies leaves exactly one trace: an
`IN_FLIGHT` row whose lease names an owner that is not coming back. Every drain pass
begins by reclaiming expired leases — back to `PENDING` if attempts remain, straight to
`DEAD_LETTER` if they do not, so nothing cycles forever.

The dangerous case is a crash *after* the provider committed but *before* we recorded it.
We cannot know which side of that line we died on, and we do not need to: redelivering
the same settlement key is safe, so we redeliver, the provider returns its original
result, and the payment settles once. `CrashResumeTest.crashInTheDangerousWindowDoesNotDoublePay`
reproduces exactly this window and asserts `delivery_count = 2, settlements = 1`.

## Why two concurrent drainers cannot double-settle

`SKIP LOCKED` means they never hold the same row in the same instant, so the common case
has nothing to deduplicate. The uncommon case — a lease expiring while its owner is still
in an HTTP call — is covered twice over: the lease-checked completion stops the stale
owner from writing, and even if both deliver, they deliver *the same settlement key*, and
the provider settles it once. Safety does not depend on the lease being tuned correctly;
a badly tuned lease costs redundant deliveries, not double payments.

## Scaling and failover

**Scaling the drainer needs no code change**: `docker compose up --scale app=3`. Instances
partition the outbox between themselves purely through `SKIP LOCKED`. If a single database
becomes the bottleneck, the claim query already orders by `(next_attempt_at, id)` behind a
partial index, and the natural next step is a shard key (say `hashtext(payment_id) % N`)
added to the claim predicate so instances take fixed slices — worth doing only when the
claim query itself is measurably hot, which it will not be for a long time.

**If the downstream degrades**, backoff and jitter already spread load, and the attempt cap
converts a permanent outage into a bounded, visible dead-letter queue rather than an
unbounded retry storm. What this deliberately does *not* have is a circuit breaker: with a
40%-failing provider, opening a breaker would stall settlement of the 60% that would have
succeeded. The right addition for a *sustained* outage is a breaker keyed on the recent
failure *rate* (not individual failures) that pauses claiming entirely and alerts, plus
`SETTLEMENT_MAX_ATTEMPTS` raised so items park in `PENDING` instead of dead-lettering
through an incident. Dead letters would then be replayed by an operator after recovery —
which is safe, again, because the settlement key never changes.

## How the tests prove it

55 tests, green from a clean checkout in about 50 seconds, against **real PostgreSQL**.
The guarantees under test are PostgreSQL guarantees — `FOR UPDATE`, `SKIP LOCKED`,
unique-index blocking on uncommitted keys, READ COMMITTED snapshots — so an in-memory
database would prove nothing. Zonky's embedded PostgreSQL is used rather than
Testcontainers so `mvn verify` is green **without a Docker daemon**; set
`TEST_DATABASE_URL` to point at an external PostgreSQL instead.

| test | what it establishes |
|---|---|
| `ConcurrentCaptureTest` | 20 concurrent captures, both same-key and distinct-key; one capture, one outbox row, zero 5xx; re-capture, voided-capture, cross-payment key reuse all 409 |
| `AuthorizeIdempotencyTest` | 20 concurrent authorizes with one key → one payment; same key + different body → 409; zero/negative/fractional amounts and bad currencies → 400 |
| `ExactlyOnceSettlementTest` | 30 payments against a 40%-failing provider; settled exactly once each, asserted against **the provider's ledger**; four concurrent drainers; settled items never redelivered |
| `CrashResumeTest` | boots a **real second instance**, kills it mid-HTTP-call by closing its connection pool first (so it gets no chance to tidy up — a `kill -9`, not a shutdown), and asserts recovery settles each item exactly once; plus the settled-then-crashed window; plus stale-owner completion being refused |
| `DeadLetterTest` | a permanently failing provider dead-letters at the cap, settles nothing, stays visible, and is not silently retried |
| `MockDownstreamIdempotencyTest` | the provider's own idempotency, including 16 concurrent deliveries of one key — the assumption the whole retry strategy rests on |
| `SelfDrivingDrainTest` | settles with nobody calling `/admin/drain`, including the tick racing manual drains |
| `BackoffTest` | backoff grows, is capped, is jittered, and survives a degenerate config |

Concurrency tests use a start-gun latch so the workers genuinely overlap; without it they
stagger by thread-creation time and the race may never happen, which is how concurrency
tests pass for the wrong reason.

The tests assert on **counts, not on happy paths** — "one settlement key per captured
payment", "zero double-settled payments" — and they ask the *provider's* table, so a bug
in our own bookkeeping cannot make the numbers look good.

## Containerization, deployment, observability

**Container.** Multi-stage `Dockerfile`: Maven/JDK 21 to build, `eclipse-temurin:21-jre-alpine`
to run. The fat jar is exploded into layers (`-Djarmode=tools … extract --layers`) so
dependencies and application classes are separate image layers. Non-root user (uid 1001)
owning nothing it can write. `HEALTHCHECK` hits `/readyz`. `docker compose up --build`
brings up app + PostgreSQL, with `pg_isready` gating startup — a TCP probe would let the
app start before the server accepts queries and turn a slow boot into a crash loop.

**12-factor.** Every value is environment-driven (`.env.example` documents all of them);
nothing sensitive is committed. `render.yaml` marks the database variables `sync: false`
so Render prompts for them. `PORT` is honoured, and `{port}` in the downstream URL is
substituted with the port actually bound, which is what makes a self-calling provider
correct on a host that assigns the port.

**Health.** `/healthz` is liveness and deliberately checks nothing external — a liveness
probe that fails during a database outage turns a recoverable incident into a crash loop.
`/readyz` checks the datastore, because without it the instance should leave rotation.

**Logs.** One JSON object per line on stdout, forwarded by Render's log stream to Better
Stack, which parses every field into something queryable without any parsing config —
that is the practical reason for structured logs rather than a stylistic one.

Every line carries a `correlation_id`, taken from an inbound `X-Correlation-Id` or minted.
Critically, the capture's correlation id is **persisted onto the outbox row**, so a
settlement retry or dead-letter logged minutes later, on a background thread, in a
different drain pass, still carries the id of the request that created the work. Searching
one correlation id on the live deployment returns the whole causal chain:

```
event=settlement_attempt attempt=10 of 10
    correlation_id=deadletter-demo settlement_key=e68b9a23… outbox_id=50
event=mock_settlement_failed reason=injected_fault
    correlation_id=deadletter-demo settlement_key=e68b9a23…
event=settlement_dead_lettered attempts=10 of 10 downstream_status=500        [ERROR]
    correlation_id=deadletter-demo payment_id=db94d258… outbox_id=50
```

This is the difference between logs you can grep and logs you can actually investigate an
incident with: "why did this payment not settle" is one query, not a manual join across
timestamps.

Logged events: `payment_authorized`, `payment_captured`, `capture_rejected`,
`capture_race_lost`, `authorize_race_lost`, `idempotent_replay`, `idempotency_key_reused`,
`settlement_attempt`, `settlement_succeeded`, `settlement_retry_scheduled`,
`settlement_dead_lettered`, `settlement_lease_reclaimed`, `settlement_lease_lost`,
`mock_settlement_deduplicated`, `drain_completed`. Amounts and ids are logged; idempotency
key *values*, request bodies and credentials never are.

Render's *metrics* stream is a paid feature, so metrics are not pushed to a hosted backend
on the free tier. `/metrics` is publicly scrapable instead, which satisfies the
"via `/metrics` and/or a dashboard" requirement; pointing a Grafana Cloud agent at it is a
configuration change, not a code one.

**Metrics** at `/metrics` (Prometheus): `payments_authorized_total`, `payments_captured_total`,
`payments_capture_race_lost_total`, `idempotency_replay_total{scope}`,
`idempotency_key_reused_total`, `settlement_attempts_total`, `settlement_success_total`,
`settlement_retry_total`, `settlement_dead_letter_total`, `settlement_lease_reclaimed_total`,
`settlement_outbox_size{status}`, `settlement_call_duration_seconds`, plus HTTP request
count/latency/error rate. Latency is exported as **histogram buckets rather than
pre-computed quantiles**, so p99 comes from `histogram_quantile(0.99, …)` and aggregates
correctly across instances — averaging per-instance p99s does not.

## AI usage — directed vs decided

**Directed:** the stack (Java 21, Spring Boot), the requirement that tests run green from a
clean checkout, the deployment targets, and the invariants that had to hold. Every design
decision below was reviewed against the running system rather than accepted on assertion.

**Decided by the AI, then verified:** the concrete locking strategy (row lock for capture
vs. unique-key collision for authorize), the outbox/lease/reaper shape, the choice of
Zonky over Testcontainers once it turned out the build machine had no Docker, the layered
jar extraction, and the test structure.

**Verified rather than trusted** — this is the part that matters. Several things the model
asserted were wrong and were caught by running them: `@DynamicPropertySource` silently
outranked the `@TestPropertySource` overrides in subclasses (two tests were passing
vacuously); `SpringApplicationBuilder.properties()` registers *default* properties that
rank below `application.yml`, so a second test instance was quietly connecting to the wrong
database; the "crash" test was closing a context gracefully enough that the dying instance
tidied up after itself and tested a much easier scenario; and the Prometheus scrape emits
histogram buckets, not `quantile=` samples, so an assertion written from memory was simply
false. The Dockerfile's `ENTRYPOINT` was likewise corrected after inspecting what
`jarmode=tools` actually emits, rather than what it was assumed to emit.

The most instructive one only appeared in CI. The suite was green on the machine it was
written on and failed on Linux, with three unrelated-looking assertions failing. The cause
was that `SelfDrivingDrainTest` is the one context with a live background drainer, and
Spring caches test contexts for the whole run rather than closing them per class — so its
tick kept firing against the shared database while *later* classes ran, claiming and
settling their outbox rows. Dead-letter tests found nothing dead-lettered; the crash test
found its work already drained. Whether it bit at all depended on the filesystem ordering
Surefire happened to see, which differs by platform. The fix is one `@DirtiesContext`, but
the point is that "passes on my machine" was not evidence of anything: it was confirmed by
reproducing the failure locally with `-Dsurefire.runOrder=reversealphabetical`, then
re-running the suite green under three different orderings. Two of the other tests were
also changed to assert that the outbox reached quiescence *before* asserting on what state
it reached, so a liveness problem now reports as one instead of masquerading as a
correctness failure.

**Where I would let AI act autonomously in a real payments flow:** writing tests,
migrations reviewed before they run, dashboards, log/metric plumbing, and non-mutating
diagnostics. **Where I would not:** anything that moves money or changes its state without
a human in the loop — replaying dead letters, editing outbox rows, changing the settlement
key, adjusting retry caps in production, or acting on a reconciliation mismatch. Those are
exactly the operations where a confident wrong answer is indistinguishable from a correct
one until the money is gone, and they should stay a human decision with an audit trail.

## Cost

**₹0.** Render free web service (Docker deploy) + Neon free PostgreSQL (0.5 GB) + Grafana
Cloud free tier (50 GB logs / 10k series) — or Better Stack / Axiom free tiers, all
sufficient at this volume. The build and the test suite need no paid runner.

One honest caveat: **Render's free web service sleeps after ~15 minutes of inactivity**,
and a sleeping instance is not draining its outbox. Nothing is lost — the outbox is durable
and the first request wakes the instance, which drains on its next tick — but for a service
whose whole job is background settlement, Koyeb's free tier (no sleep) or Fly.io with
`min_machines_running = 1` is the better free home, and `fly.toml` is included for that
reason. On a paid tier this is a non-issue.
