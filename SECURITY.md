# Security Policy

## Reporting a vulnerability

If you find a security issue, **do not open a public issue.** Email
**tejasdeshmane66@gmail.com** with details and reproduction steps. You'll get an
acknowledgement within 72 hours and a fix timeline after triage.

## Scope (this being a financial ledger, these matter most)

- **Money integrity:** any way to create, destroy, or duplicate value; break the
  double-entry invariant (Σ entries ≠ 0); bypass idempotency to double-post a transfer.
- **Concurrency:** races that cause lost updates, negative balances, or partial transfers.
- **Injection:** SQL injection — all queries must be parameterized; no string-built SQL.
- **Authz:** unauthenticated access to transfer/admin endpoints.
- **Secret exposure:** credentials in code, logs, or history.

## Practices enforced in this repo

- Parameterized SQL only (`JdbcTemplate` with bind params).
- Secrets via environment / `.env` (gitignored); never committed. `.env.example` is the template.
- Input validated at the API boundary (Bean Validation) before reaching the service/DB.
- Money handled as integer minor units — no floating-point arithmetic.
- API-key auth on state-changing and admin endpoints.
- Errors return generic messages to clients; details stay in server logs.

## Supported versions

Pre-1.0: only the latest `main` receives fixes.
