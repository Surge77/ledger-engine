# Ledger Engine — Implementation Plan

> A production-grade **double-entry payment ledger** exposed as a backend service.
> This document is the build spec. Implement it phase by phase; each phase ends with
> passing tests, a commit, and a push. Built following my own methodology
> ([Project Learning structure] + [Universal Backend Build Order]) adapted from the
> TypeScript/Express reference order to **Java 21 + Spring Boot**.

---

## 1. Clarity First — what, why, non-goals

**What:** A backend service that records money movement using double-entry accounting.
Balances are *derived* from an append-only list of entries, never edited in place.
Every transfer debits one account and credits another by the same amount, so the sum
of all entries in the system is always zero.

**Why (for me):** Pairs with `fraud-detection-engine` into one payments-company backend
narrative. It's the core every fintech (PhonePe, Razorpay, Groww, Slice, Stripe) is built
on, and it demonstrates correctness under crashes, retries, and concurrency — the exact
things backend interviews probe, with measurable numbers for the résumé.

**Non-goals (v1):**
- No UI beyond optional read-only views (this is an API/engine, not a web app).
- No multi-currency FX conversion (single currency per transfer; reject mismatches).
- No auth/users system beyond a simple API key (focus is ledger correctness).
- No Redis/Kafka in v1 — replaced by Postgres-native equivalents (see §2). Added in V2.

---

## 2. Stack Lockdown (no stack-hopping)

| Layer | Choice | Note |
|-------|--------|------|
| Language | **Java 21** | matches `fraud-detection-engine`, `limitbook-java` |
| Framework | **Spring Boot 3** (Web, Validation) | |
| Build | **Maven** | |
| DB | **PostgreSQL** (native Windows install, no Docker) | real `SERIALIZABLE` + `SELECT … FOR UPDATE` |
| Migrations | **Flyway** | versioned, reviewable schema |
| DB access | **Spring JDBC (`JdbcTemplate`)** | explicit SQL — the locking/atomicity is the point; an ORM hides it |
| Money | **`long` minor units (paise)** | never `float`/`double` |
| Validation | **Jakarta Bean Validation** (`@Valid`) | the Spring equivalent of Zod |
| Tests | **JUnit 5 + Spring Boot Test** against local Postgres | not H2 — H2 won't honor real isolation |
| Load test | **k6** or Apache `ab` | for p99 / throughput numbers |

**Deferred to V2 (don't install until needed — layer, not leap):**
- Redis (idempotency cache) → v1 uses a Postgres unique constraint.
- Kafka (event stream) → v1 uses an `outbox` table polled by `@Scheduled`.

---

## 3. Functional Requirements (FR)

| ID | Requirement | Acceptance |
|----|-------------|-----------|
| FR1 | Create an account (name, currency) | returns account id; starts at balance 0 |
| FR2 | Post a transfer between two accounts | writes 1 transaction + 2 balanced entries atomically |
| FR3 | Idempotent transfers via `Idempotency-Key` header | same key replayed → applied once, original result returned |
| FR4 | Reject invalid transfers | nonexistent account, currency mismatch, amount ≤ 0, insufficient funds → 4xx, **no** rows written |
| FR5 | Get account balance | derived from entries, not a stored mutable column |
| FR6 | Get account entry history (paginated) | append-only, newest first |
| FR7 | Reverse a transfer | creates a compensating transaction; never deletes/edits originals |
| FR8 | Reconciliation job | verifies Σ(entries) = 0 and derived balances match; reports drift |
| FR9 | Outbox event per posted transaction | a poller publishes/logs events (Kafka-ready) |

---

## 4. Non-Functional Requirements (NFR)

| ID | Property | How it's met | How it's proven |
|----|----------|--------------|-----------------|
| NFR1 | **Correctness** (double-entry invariant) | every transaction inserts balanced entries in one DB tx; DB CHECK + service guard | test: `Σ entries = 0` after any op |
| NFR2 | **Atomicity** | single `@Transactional` per transfer; both legs or neither | crash-recovery test (kill mid-batch) |
| NFR3 | **Idempotency / exactly-once** | unique constraint on `idempotency_key`; replay returns stored result | duplicate-key test |
| NFR4 | **Concurrency safety** | `SELECT … FOR UPDATE` row locks + `SERIALIZABLE`; ordered lock acquisition to avoid deadlock | 100-thread parallel test, no lost update / no negative balance |
| NFR5 | **Durability** | committed before ack; outbox in same tx as ledger write | restart test |
| NFR6 | **Performance** | indexed lookups, batched reads | record transfers/sec + p99 ms |
| NFR7 | **Observability** | structured logs, `/health`, request logging, metrics endpoint | manual + dashboards |
| NFR8 | **Security** | API key auth, parameterized SQL only, no secrets in repo, input validation at boundary | see SECURITY.md |
| NFR9 | **Testability** | 80%+ coverage on service/business logic; deterministic tests | coverage report |

---

## 5. Build-Backwards Skill Map

To ship the above, I need (reverse-engineered from the goal):
- Spring Boot REST + Bean Validation + global error handling
- Flyway migrations & relational schema design with constraints
- Postgres transaction isolation, row locking, deadlock avoidance
- Idempotency & the transactional outbox pattern
- Append-only / event-sourced balance derivation
- Concurrency testing (`ExecutorService`) + load testing (k6)

---

## 6. Domain Model & Schema

```
accounts        (id, name, currency, created_at)
transactions    (id, idempotency_key UNIQUE, type, status, created_at)
entries         (id, transaction_id FK, account_id FK, amount_minor, direction, created_at)
                  -- amount_minor: positive=credit, negative=debit; per-tx SUM must = 0
outbox          (id, transaction_id FK, payload, published_at NULL, created_at)
```

Invariants enforced at DB + service level:
- `entries.amount_minor` summed per `transaction_id` = 0.
- `idempotency_key` unique → no duplicate transfer.
- balance(account) = `SUM(amount_minor) WHERE account_id = ?` (derived).
- money stored as `BIGINT` minor units; no floats anywhere.

---

## 7. API Surface (Input → Processing → Output)

```
POST /accounts                 {"name","currency"}                        -> 201 {id}
POST /transfers                {"from","to","amount_minor","currency"}    -> 201 {transfer_id,status,balances}
   header: Idempotency-Key: <uuid>
GET  /accounts/{id}/balance                                               -> 200 {account,balance_minor}
GET  /accounts/{id}/entries?page=                                         -> 200 {entries[],page}
POST /transfers/{id}/reverse                                              -> 201 {reversal_id}
GET  /health                                                              -> 200
GET  /admin/reconcile                                                     -> 200 {invariant_ok,drift[]}
```

Transfer processing pipeline (the engine):
`validate → idempotency check → BEGIN(SERIALIZABLE) → lock both accounts (ordered) →
balance check → insert transaction + 2 entries (Σ=0) → insert outbox row → COMMIT →
scheduled poller ships outbox`.

---

## 8. Phased Build (Universal Build Order → Spring Boot)

Each phase: **goal → steps → exit tests → commit & push**. Mapped to my
MVP→V1→V2→V3→V4 layering.

### Phase 0 — Scaffold  *(MVP)*
- Spring Initializr: Java 21, Spring Web, Validation, JDBC, PostgreSQL driver, Flyway.
- Package layout: `controller/ service/ repository/ domain/ config/ exception/`.
- `GET /health` returns 200.
- **Exit:** app boots, `/health` works. **Commit:** `chore: scaffold spring boot + health`.

### Phase 1 — Configuration
- `application.yml` + `.env`-style externalized config; fail fast if DB URL missing.
- Single typed config source; never read raw env elsewhere.
- **Exit:** app refuses to start with a clear message if config absent. **Commit:** `feat(config): typed config + startup validation`.

### Phase 2 — Database + Migrations
- Local Postgres connection; Flyway `V1__init.sql` with the §6 schema + constraints + indexes.
- Connection check on startup.
- **Exit:** migration applies; tables + constraints exist. **Commit:** `feat(db): schema + flyway migration`.

### Phase 3 — Domain types & DTOs
- Domain records (`Account`, `Transaction`, `Entry`) + request/response DTOs.
- **Exit:** compiles, types model the schema. **Commit:** `feat(domain): core types + dtos`.

### Phase 4 — Validation
- Bean Validation on request DTOs; reusable validation error → 400 mapping.
- **Exit:** invalid bodies return field-level 400. **Commit:** `feat(validation): request validation + 400 mapping`.

### Phase 5 — First route end-to-end: `POST /accounts`  *(MVP done)*
- Controller → (temporary inline) → repository insert → return record.
- **Exit:** account creates + reads back (curl/Postman + 1 test). **Commit:** `feat(accounts): create + fetch account`.

### Phase 6 — The transfer engine (services)  *(V1: the heart)*
- `LedgerService.transfer(...)`: idempotency, `@Transactional(SERIALIZABLE)`, ordered
  `FOR UPDATE` locks, balanced double-entry insert, balance check, outbox write.
- Controller only: validate → call service → respond.
- **Exit tests (must pass):** happy path · invariant Σ=0 · idempotency replay · insufficient funds (no rows) · currency mismatch. **Commit:** `feat(ledger): atomic idempotent double-entry transfer`.

### Phase 7 — Remaining routes
- `GET balance`, `GET entries` (paginated), `POST reverse`.
- **Exit:** each validated → service → response, with tests. **Commit:** `feat(api): balance, history, reversal`.

### Phase 8 — Cross-cutting (middlewares equivalent)
- Global `@ControllerAdvice` error handler, request logging filter, API-key filter, 404 handling.
- **Exit:** uniform error envelope; unauthorized blocked. **Commit:** `feat(web): error handler, logging, api-key auth`.

### Phase 9 — Reconciliation + outbox poller
- `@Scheduled` reconciliation (Σ=0 + balances match → report drift); outbox poller logs/emits events.
- **Exit:** reconcile endpoint + job prove 0 drift. **Commit:** `feat(ops): reconciliation job + outbox poller`.

### Phase 10 — Concurrency & crash hardening  *(V1 hardened)*
- 100-thread parallel transfer test; deadlock-free ordered locking; kill/restart recovery test.
- **Exit:** no lost updates, no negative balance, no half-transfer after crash. **Commit:** `test(concurrency): parallel + crash-recovery proof`.

### Phase 11 — Polish, load test, docs  *(V1 shippable)*
- Remove any `Object`/raw types, ensure every path has error handling, env validated on boot.
- k6 load test → record transfers/sec + p99. Fill README numbers.
- **Exit:** coverage ≥80% on services; README has real numbers. **Commit:** `docs: results + load numbers; chore: polish`.

### V2 (stretch, after V1 is green)
- Swap idempotency cache to **Redis**; ship outbox to real **Kafka**; add Prometheus/Grafana metrics; deploy a hosted demo (Neon Postgres) + small read-only UI.

---

## 9. Verification & Test Matrix (definition of "really built")

| # | Test | Pass criteria | Proves |
|---|------|---------------|--------|
| 1 | Happy path transfer | 201, balances move, 2 entries | FR2 |
| 2 | Invariant | `SELECT SUM(amount_minor) FROM entries` = 0 | NFR1 |
| 3 | Idempotency | same key twice → applied once | NFR3 |
| 4 | Insufficient / invalid | rejected, **0 rows** written | FR4 |
| 5 | Concurrency (100 threads) | no lost update, no negative, Σ still 0 | NFR4 |
| 6 | Crash recovery | kill mid-batch, restart → no half-transfer | NFR2/5 |
| 7 | Reconciliation | derived balances == entry sums, 0 drift | FR8 |

Tests 2,3,5,6,7 are the bar. If they pass, the project is real.

**README headline numbers to earn:** Σ entries = 0 across N transfers · exactly-once under
concurrent retries · 0 drift over M transfers · X transfers/sec at p99 < Y ms.

---

## 10. Workflow & cadence (per my git rules)

- `main` is protected mentally: do each phase on a `feature/phaseN-...` branch.
- Run the type/compile gate + tests **before** every commit.
- Conventional Commits. Push after each phase's tests are green.
- Merge phase branches with `--no-ff` (commits are individually meaningful).
- Tag `v0.1.0` when V1 (Phase 11) is shippable.

---

## 11. Repo & Governance checklist (created up front)

`README.md` · `LICENSE` (MIT) · `SECURITY.md` · `CONTRIBUTING.md` · `CODE_OF_CONDUCT.md` ·
`.gitignore` · `CHANGELOG.md` · `.github/ISSUE_TEMPLATE/*` · `.github/PULL_REQUEST_TEMPLATE.md`.

---

## 12. Next action

Phase 0. Generate the Spring Boot skeleton, wire `/health`, point at local Postgres,
commit, push. Then proceed phase by phase, committing after each green test gate.
