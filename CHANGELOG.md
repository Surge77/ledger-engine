# Changelog

All notable changes to this project are documented here. Format based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/).

## [Unreleased]
Hardening pass (review-driven) + the deposit boundary and API docs.

### Added
- **Deposit endpoint** `POST /accounts/{id}/deposit`: money enters the ledger by
  debiting a per-currency **system account** (the only account allowed to run
  negative) and crediting the target — idempotent, currency-checked, Σ stays zero.
- **OpenAPI 3 / Swagger UI** (springdoc 2.6.0) at `/swagger-ui` and `/v3/api-docs`,
  public and documenting the `X-Api-Key` scheme.
- Bounds + overflow guards: `@Max` on transfer/deposit amounts, overflow-safe
  post-balance arithmetic, `@Size` on the `Idempotency-Key` header, `long` pagination
  offset (deep pages no longer 500).
- Tests now 34 (added deposit, concurrent-reversal race, amount/header bounds,
  deep-pagination, scheduled-reconcile).

### Fixed
- **Double-reversal race (critical):** a partial `UNIQUE` index on
  `transactions.reverses_tx_id` makes at most one reversal per transaction
  race-safe; `reverse()` now retries on transient errors and maps the lost race to
  the same "already reversed" response.
- Balance trigger now enforces `Σ(amount_minor)=0` **per currency**, not just in
  aggregate (closes a cross-currency value-leak at the DB layer).
- Bounded the `/admin/reconcile` drift query (`LIMIT` + `truncated` flag).

### Changed
- Retry backoff has jitter; virtual threads enabled (`spring.threads.virtual`).
- Entry-currency uppercase CHECK and outbox `event_type` CHECK (parity with existing enums).

## [0.1.0] — 2026-06-22
First working V1: a correct, concurrent, crash-safe double-entry ledger.

### Added
- Spring Boot 3 / Java 21 service: Spring Web, Validation, Spring JDBC, Flyway, Actuator.
- Flyway `V1` schema: `accounts`, `transactions`, `entries`, `outbox` with CHECK
  constraints, indexes, and a deferred constraint trigger enforcing `Σ(entries)=0` per
  transaction at commit.
- Endpoints: create/fetch account, post transfer (idempotent), balance, paginated
  history, reverse, `/admin/reconcile`, `/health`.
- Transfer engine: one transaction, ordered `FOR UPDATE` locks, `READ COMMITTED`,
  balanced double-entry insert, no-overdraft check, transactional outbox.
- Idempotency via unique `idempotency_key` (replay + concurrent-duplicate handling) and
  bounded retry on transient lock/serialization errors.
- Cross-cutting: API-key filter (constant-time), request-id logging filter, uniform
  error envelope, strict 404.
- Scheduled reconciliation job + outbox poller.
- Tests (26): accounts, transfer engine (invariant, idempotency, insufficient funds,
  currency mismatch), reads/reversal, security/404, ops, error paths, atomicity rollback,
  100-thread concurrency. JaCoCo line coverage ~90%.
- Scheduling (outbox poller + reconciliation) gated behind `ledger.scheduling.enabled`
  (default on); disabled on the test classpath so background threads can't race a test's
  shared database or `@MockBean` — removes a flaky-test source and keeps the suite
  deterministic.
- `scripts/crash-recovery-test.ps1` (hard-kill proof) and `LoadBenchmark` (throughput/p99).
- Maven wrapper.

### Project governance (pre-existing)
- Project plan ([`PLAN.md`](./PLAN.md)), README, LICENSE (MIT), SECURITY, CONTRIBUTING,
  CODE_OF_CONDUCT, issue/PR templates, `.gitignore`, `.env.example`.
