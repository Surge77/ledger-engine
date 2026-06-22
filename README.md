# Ledger Engine

A production-grade **double-entry payment ledger**, built as a backend service.

Balances are never edited in place — they are *derived* from an append-only stream of
balanced entries. Every transfer debits one account and credits another by the same
amount, so the sum of all entries across the system is always **zero**. This is how real
payment infrastructure (Stripe, banks, brokerages, wallets) keeps money correct under
crashes, retries, and concurrency.

> Status: ✅ **V1 complete** — all phases built, 26 automated tests green, plus a
> crash-recovery proof and a load benchmark. See [`PLAN.md`](./PLAN.md) for the build spec.

## Headline numbers (measured locally)

| Metric | Result |
|--------|--------|
| Σ of all entries across every operation | **0** (DB-enforced, deferred trigger) |
| 100 concurrent transfers on one account | no lost update, no negative balance, Σ=0 |
| Exactly-once under concurrent retries | duplicate `Idempotency-Key` → applied once |
| Hard `kill -9` mid-load → restart | 158 transfers committed, **0** half-posted, 0 drift |
| Throughput (disjoint accounts, 32 threads) | **~1,990 transfers/sec**, p99 **~28 ms** |
| Throughput (single hot account, fully serialized) | ~330 transfers/sec, p99 ~178 ms |

> Numbers from a local PostgreSQL 17 on Windows; reproduce with
> `mvn -Dtest=LoadBenchmark test` and `pwsh scripts/crash-recovery-test.ps1`.

## Why this exists

Naive money code (`UPDATE balance = balance - 500`) silently loses or duplicates money
under crashes, retries, and races. A double-entry ledger makes money movement **atomic,
idempotent, and auditable**. This project implements that correctly and proves it with tests.

## What it does

- Create accounts and post **transfers** between them.
- **Exactly-once** transfers via `Idempotency-Key` (safe client retries).
- **Atomic** double-entry posting (both legs commit or neither).
- **Append-only** entries → full audit history; corrections via reversing entries.
- **Reconciliation** job that verifies the system-wide invariant and reports drift.

## Tech stack

Java 21 · Spring Boot 3 · PostgreSQL · Flyway · Spring JDBC · JUnit 5.
Money is stored as integer **minor units** (paise/cents) — never floating point.

> Redis (idempotency cache) and Kafka (event stream) are V2 enhancements; v1 uses
> Postgres-native equivalents (unique constraint + transactional outbox). No Docker required.

## API (overview)

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/accounts` | create an account |
| `POST` | `/transfers` | post a transfer (send `Idempotency-Key` header) |
| `GET`  | `/accounts/{id}/balance` | current balance (derived) |
| `GET`  | `/accounts/{id}/entries` | paginated history |
| `POST` | `/transfers/{id}/reverse` | reverse a transfer |
| `GET`  | `/admin/reconcile` | invariant + drift report |
| `GET`  | `/health` | liveness (public) |

Every endpoint except `/health` and `/actuator/health` requires the `X-Api-Key` header.
Account ids are integers; money is an integer `amountMinor`.

```bash
# create two accounts
curl -X POST localhost:8080/accounts -H 'X-Api-Key: <key>' \
  -H 'Content-Type: application/json' -d '{"name":"Alice","currency":"INR"}'

# post an idempotent transfer
curl -X POST localhost:8080/transfers \
  -H 'X-Api-Key: <key>' -H 'Idempotency-Key: 8f1c-…' \
  -H 'Content-Type: application/json' \
  -d '{"from":1,"to":2,"amountMinor":50000,"currency":"INR"}'
```

## Design notes

- **Atomicity & the invariant.** Each transfer is one DB transaction that inserts a
  balanced pair of entries. A `DEFERRABLE INITIALLY DEFERRED` constraint trigger
  re-checks `SUM(amount_minor)=0` per transaction at commit — the database itself
  refuses to persist a half-transfer.
- **Concurrency.** Both account rows are locked `FOR UPDATE` in ascending id order
  (deadlock-free), and the balance is read while the lock is held. The row lock is the
  per-account mutex, so `READ COMMITTED` is correct here — and it avoids the spurious
  serialization aborts that `SERIALIZABLE` raises on the balance predicate read under
  heavy contention. A bounded retry covers transient lock/deadlock errors.
- **Idempotency.** A unique constraint on `idempotency_key` makes a replayed transfer a
  no-op that returns the original transaction; a concurrent duplicate loses the insert
  race and is served the stored result.
- **Outbox.** Each post writes an `outbox` row in the same transaction; a `@Scheduled`
  poller drains it (Kafka-ready). No event without a transaction, no transaction without
  an event.

## Getting started

Prerequisites: JDK 21 and a local PostgreSQL (Maven via the bundled `./mvnw` wrapper).

```bash
git clone https://github.com/Surge77/ledger-engine.git
cd ledger-engine
# create role + db (psql):  CREATE ROLE ledger LOGIN PASSWORD '…';
#                           CREATE DATABASE ledger_engine OWNER ledger;
cp .env.example .env                  # set LEDGER_DB_* and LEDGER_API_KEY, then export them
./mvnw spring-boot:run                # Flyway applies the schema on startup
curl localhost:8080/health
```

## Testing & verification

Proven by tests, not a UI. The bar (all green):

| Test | Proves |
|------|--------|
| `TransferApiTest` — Σ of all entries = 0 | double-entry invariant |
| `TransferApiTest` — same `Idempotency-Key` twice → applied once | exactly-once |
| `ConcurrencyTest` — 100 parallel transfers | no lost update, no negative balance |
| `AtomicityTest` + `scripts/crash-recovery-test.ps1` | atomicity / crash-recovery |
| `OpsApiTest` — reconciliation | 0 balance drift |

```bash
./mvnw verify                          # 26 tests + JaCoCo coverage (~90% line)
mvn -Dtest=LoadBenchmark test          # throughput + p99 numbers
pwsh scripts/crash-recovery-test.ps1   # hard-kill mid-load, prove no half-transfer
```

## Roadmap

See [`PLAN.md`](./PLAN.md) — phased build (Phase 0 → 11), then V2 (Redis, Kafka, hosted demo).

## Contributing / Security / Conduct

- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [SECURITY.md](./SECURITY.md)
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)

## License

[MIT](./LICENSE) © 2026 Tejas Deshmane
