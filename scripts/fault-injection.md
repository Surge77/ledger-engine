# Fault Injection Runbook

The point of the observability stack is not the dashboards — it is that a fault
produces an alert and a trace that localizes it. This runbook is how that claim gets
measured, and it is where the README number comes from.

**Prerequisites:** both services running, `docker compose -f docker-compose.monitoring.yml up -d`
in fraud-engine, and `k6 run scripts/load-test.js` generating steady traffic.

For each scenario, record:

| Field | How to get it |
|---|---|
| Fault injected at | wall clock when the command ran |
| Alert fired at | Prometheus → Alerts → the rule's `activeAt` |
| **Time to alert** | difference — the number that goes in the README |
| Root cause visible in trace | Grafana → Explore → Tempo → does one waterfall show it? |

---

## 1. Fraud-engine down

Kills the consumer while the ledger keeps accepting writes.

```bash
# stop the fraud service (Ctrl-C its process, or)
kill $(jsc=$(pgrep -f fraud-engine); echo $jsc)
```

**Expect:** `ServiceDown` fires within ~30s. Ledger latency is *unaffected* — this is
the point of publishing asynchronously through the outbox. `OutboxLagGrowing` does
**not** fire, because the ledger is still publishing to Kafka successfully; the
messages simply accumulate unconsumed. If you expected outbox lag here, that
expectation is the thing to correct: the outbox measures publish, not consumption.

## 2. Kafka down

```bash
docker compose stop kafka
```

**Expect:** `OutboxPublishFailing` within ~30s, then `OutboxLagGrowing` as the backlog
passes 100. Transfers keep succeeding — money still moves correctly, but fraud
scoring is now blind. This is the most important alert on the platform and the one
with no HTTP-metric equivalent.

Trace check: ledger spans end at the failed producer span; there is no fraud span,
and the gap is visible rather than inferred.

## 3. Latency injection on the fraud path

Add artificial delay to the evaluation pipeline (temporary, revert after):

```java
// TransactionEvaluationService.evaluate — remove after the exercise
Thread.sleep(300);
```

**Expect:** `FraudEvaluationLatencyHigh` fires; ledger write latency stays flat.
Trace shows the fraud evaluation span dominating total duration, with the ledger
spans unchanged — the case metrics alone cannot resolve, since both services would
merely look "slower" on a dashboard.

## 4. DB connection-pool exhaustion

```bash
# shrink the pool, restart ledger, keep k6 running
LEDGER_DB_POOL_SIZE=1 ./mvnw spring-boot:run
```

**Expect:** `LedgerWriteLatencySLOBreach` within ~30s as requests queue for a
connection. Trace shows time spent *before* the SQL span — waiting for the pool, not
executing the query. That distinction is the single clearest argument for tracing
over metrics: the p99 graph looks identical whether the database is slow or the pool
is starved, and the remediation is opposite in each case.

## 5. Postgres down

```bash
docker compose stop postgres
```

**Expect:** `LedgerAvailabilitySLOBreach` (5xx ratio) within ~30s. Transfers fail
outright rather than degrading — correct behavior for a ledger, which must never
accept a write it cannot durably record.

---

## Recording the result

Once every scenario has a measured time-to-alert, the README claim becomes concrete:

> Alert fires in under Ns on injected fault; a single trace localizes root cause
> across both services.

Replace `N` with the **worst** observed time, not the best. A platform is
characterized by its slowest detection, and quoting the best case is the kind of
number that does not survive a follow-up question.
