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
