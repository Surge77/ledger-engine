# Observability Platform — ledger-engine ↔ fraud-detection-engine

Cross-repo initiative. Companion doc lives in `fraud-detection-engine/PLAN-observability.md`.

**Goal:** turn two independent Spring Boot services into one observable platform with
end-to-end distributed tracing across a Kafka boundary, SLOs, and alerting proven by
fault injection.

**Headline metric to earn:** *"alert fires in < 30s on injected fault; a single trace
localizes root cause across both services."*

---

## Baseline (verified 2026-07-26)

| | ledger-engine | fraud-detection-engine |
|---|---|---|
| Entry points | REST (Transfer/Account/Admin) | REST + Kafka consumer |
| Stack | Web + Postgres (JdbcTemplate) | Web + Postgres + Redis + Kafka |
| Metrics deps | Micrometer + Actuator + Prometheus registry | Micrometer + Actuator |
| Prometheus/Grafana | none | `docker-compose.monitoring.yml`, `prometheus.yml`, `monitoring/grafana/` |
| Tracing | **none** | **none** |
| Integration between them | **none** | **none** |

Key existing asset: `TransferProcessor` already writes to a **transactional outbox**
(`outbox.insert(...)` inside the same `@Transactional` block as the ledger post), and
`OutboxPoller` drains it on a schedule. Its javadoc says *"Kafka-ready (logs for now)"* —
publishing is a seam that already exists, not new architecture.

---

## Design decision: anti-corruption layer

The two domains do not align:

- ledger `TRANSFER_POSTED` payload: `transactionId` (long), `from`, `to`, `amountMinor`, `currency`
- fraud `TransactionRequest`: `transactionId` (String), `accountId`, `amount` (BigDecimal),
  `currency`, `merchantId` **@NotBlank**, `location` **@NotBlank**, `timestamp` @NotNull

Ledger has no merchant or location concept — transfers are account-to-account. Publishing
ledger events onto `transactions.incoming` would fail bean validation on every message and
route straight to `transactions.incoming.DLT`.

**Rejected:** adding merchant/location to ledger (pollutes a clean double-entry domain);
loosening fraud's validation (weakens a production guard).

**Chosen:** ledger publishes its own domain event to its own topic. fraud adds a second
listener plus a mapper that translates the ledger event into its internal model. Each
service keeps its own language; the translation lives at the boundary, owned by the consumer.

```
POST /transfers
  └─ TransferProcessor  (@Transactional: entries + outbox row, atomic)
       └─ OutboxPoller  (scheduled drain)
            └─ Kafka topic: ledger.transfers.posted
                 └─ LedgerTransferListener (fraud)
                      └─ LedgerTransferMapper → TransactionRequest
                           └─ TransactionEvaluationService → Redis + Postgres
```

---

## Phases

### Phase 0 — Kafka integration (ledger side)
- Add `spring-kafka` to `pom.xml`; producer config in `application.yml`.
- Define `LedgerTopics.TRANSFERS_POSTED = "ledger.transfers.posted"`.
- Replace the `log.info` in `OutboxPoller.publishBatch()` with a `KafkaTemplate` send,
  keeping `markPublished` **after** successful send so the at-least-once guarantee holds.
- Preserve existing behavior when Kafka is absent: gate on a
  `ledger.outbox.publisher` property (`log` | `kafka`), default `log`, so local dev and
  the existing test suite are unaffected.

**Exit:** transfer produces a message on `ledger.transfers.posted`; outbox row marked
published only on send success; existing tests still green.

### Phase 1 — Consumer + ACL (fraud side)
- `LedgerTransferEvent` record mirroring the ledger payload.
- `LedgerTransferMapper` → `TransactionRequest`: `amountMinor / 100` → BigDecimal,
  `transactionId` → String, `from` → `accountId`, synthetic `merchantId`/`location`
  marking ledger-origin (documented as such — not fabricated business data).
- `LedgerTransferListener` on the new topic, reusing the existing manual-ack + DLT
  error handler.

**Exit:** a `POST /transfers` on ledger results in a fraud decision row in Postgres.

### Phase 2 — Tracing (both)
- OpenTelemetry Java agent on both services, OTLP → Tempo.
- Verify W3C trace context propagates **through Kafka headers** — the failure mode is
  silent (two orphan traces, no error). Assert one `traceId` spans both services.
- Add Tempo to the compose stack; wire Grafana datasource.

**Exit:** single Grafana waterfall: `POST /transfer` → outbox → Kafka → fraud eval → Redis.

### Phase 3 — Metrics + logs
- Business counters: `ledger_transfers_posted_total`, `ledger_outbox_lag`,
  alongside fraud's existing `FraudMetrics`.
- Structured JSON logging on both, with `trace_id` in MDC (fraud already uses MDC for
  `transactionId` — extend, don't replace).
- Promote fraud's Grafana setup to a shared compose scraping both services.

**Exit:** click a slow trace → jump to that request's logs.

### Phase 4 — SLOs + alerts
- SLOs: ledger write p99 < 200ms, availability 99.5%; fraud eval p99; outbox lag < N.
- Prometheus alert rules with burn-rate alerting.

### Phase 5 — Load + fault injection
- k6 drives traffic to populate dashboards.
- Injected faults: kill fraud-engine, add latency, exhaust DB pool, pause Kafka.
- Record alert-fire latency and confirm the trace localizes the fault. **This produces
  the README number.**

---

---

## Delivery status (2026-07-26)

| Phase | Code | Verified |
|---|---|---|
| 0 — Kafka publish | done | unit (5/5); integration suite not run — no local Postgres |
| 1 — Consumer + ACL | done | **76/76 unit, green** |
| 2 — Tracing | done | compiles; **propagation unverified — needs a live broker** |
| 3 — Metrics + logs | done | unit (metric assertions green) |
| 4 — SLOs + alerts | done | rules authored; **never evaluated by a live Prometheus** |
| 5 — Load + fault injection | scripted | **not executed** — needs the full stack |

**Tracing was implemented with the Micrometer bridge, not the OTel Java agent.** The
agent would attach at runtime and could not be compiled or asserted; the bridge makes
propagation ordinary wiring. Two settings carry the cross-service trace:
`spring.kafka.template.observation-enabled` on the producer, and — critically —
`setObservationEnabled(true)` on fraud-engine's hand-built
`ledgerTransferListenerContainerFactory`, which does **not** inherit the
`spring.kafka.listener.observation-enabled` property. Missing that one line produces
two orphan traces and no error.

### Metric-name audit (2026-07-26, post-merge)

A static audit of every metric referenced by the alert rules found four defects that
would each have failed **silently** — the alert simply never fires, and nothing logs
an error:

| Defect | Effect if unfixed |
|---|---|
| `LedgerMetrics.transferPosted()` was never called | `ledger_transfers_posted_total` permanently 0 |
| `ledger.transfer.duration` histogram enabled for a timer that does not exist | dead config; phantom second source of truth for write latency |
| Alert referenced `fraud_evaluation_duration_seconds_bucket` | real timer is `fraud.pipeline.duration`; rule matched no series |
| `fraud.pipeline.duration` had no histogram buckets | `histogram_quantile` returns nothing even with the correct name |

All four are fixed. The lesson generalizes: **a metric name is a contract between
code and alert rules that no compiler checks.** Java errors surface at build time;
a mistyped PromQL series name produces an alert that looks configured and is inert.

Remaining name assumptions still unverified against a live `/actuator/prometheus`:
`ledger_outbox_lag`, `ledger_outbox_publish_failures_total`,
`http_server_requests_seconds_bucket{uri="/transfers"}`. These follow Micrometer's
documented naming conversion, but conversion rules are not the same as observed
output — confirm them on first boot.

### Verification breakthrough (2026-07-26)

Two blockers previously recorded as fatal turned out not to be:

- **Kafka needs no Docker.** `spring-kafka-test` runs an in-JVM KRaft broker, so the
  publish path *and* W3C trace propagation are testable here.
  `KafkaOutboxPublisherTest` now asserts a `traceparent` header is written into the
  record using the real OTel bridge — not a stub propagator, which would only prove
  the stub works. Phase 2's producer half is **proven**, not assumed.
- **Postgres needs no service start.** `pg_ctl -D "<install>/data" start` runs it as a
  user process without elevation. The full ledger suite — **44/44 green**, including
  `OpsApiTest.outboxPollerPublishesPendingEvents`, which exercises the changed drain
  path — has now run.

Two real defects were found only by testing against a live broker:

1. `KafkaTemplate.send()` fails **synchronously** with `KafkaException` when the
   broker is unreachable — it never reaches the future. The publisher caught only
   `ExecutionException`/`TimeoutException`, so that escaped untranslated and lost the
   event id. Mocks could not have surfaced this.
2. `ConcurrentKafkaListenerContainerFactory.setObservationEnabled(true)` is now
   guarded by a test, since regressing it breaks tracing silently.

**Still unproven:** the consumer half of propagation (that fraud-engine *extracts* the
header end-to-end), alert-rule evaluation by a live Prometheus, and the
fault-injection numbers. Those need the full stack from
`scripts/fault-injection.md`.

## Risks

- **Silent trace-context loss over Kafka** — must be asserted in a test, not eyeballed.
- **Outbox at-least-once → duplicate fraud evaluations.** Fraud already documents
  `transactionId` as an idempotency key; verify that holds under redelivery.
- **Scope creep into ledger's transfer path.** Phase 0 touches `OutboxPoller` only;
  `TransferProcessor` is not modified.

## Sequencing

Phases 0–1 must land before 2 (nothing to trace across otherwise). 3–5 are independent
of each other but all depend on 2.

Estimated: ~1.5–2 weeks. Not a single-session build.
