# Ledger Engine

A production-grade **double-entry payment ledger**, built as a backend service.

Balances are never edited in place — they are *derived* from an append-only stream of
balanced entries. Every transfer debits one account and credits another by the same
amount, so the sum of all entries across the system is always **zero**. This is how real
payment infrastructure (Stripe, banks, brokerages, wallets) keeps money correct under
crashes, retries, and concurrency.

> Status: 🚧 in development. See [`PLAN.md`](./PLAN.md) for the full phased build spec.

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
| `GET`  | `/health` | liveness |

```bash
curl -X POST localhost:8080/transfers \
  -H 'Idempotency-Key: 8f1c…' -H 'Content-Type: application/json' \
  -d '{"from":"acc_1","to":"acc_2","amount_minor":50000,"currency":"INR"}'
```

## Getting started

Prerequisites: JDK 21, Maven, a local PostgreSQL.

```bash
git clone https://github.com/Surge77/ledger-engine.git
cd ledger-engine
createdb ledger                       # or via pgAdmin
cp .env.example .env                  # set DB url / credentials
./mvnw spring-boot:run
curl localhost:8080/health
```

## Testing & verification

This project is proven by tests, not a UI. The bar:

| Test | Proves |
|------|--------|
| Σ of all entries = 0 | double-entry invariant |
| same `Idempotency-Key` twice → applied once | exactly-once |
| 100 parallel transfers | no lost update, no negative balance |
| kill mid-batch & restart | atomicity / crash-recovery |
| reconciliation | 0 balance drift |

```bash
./mvnw test
```

## Roadmap

See [`PLAN.md`](./PLAN.md) — phased build (Phase 0 → 11), then V2 (Redis, Kafka, hosted demo).

## Contributing / Security / Conduct

- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [SECURITY.md](./SECURITY.md)
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)

## License

[MIT](./LICENSE) © 2026 Tejas Deshmane
