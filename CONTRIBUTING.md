# Contributing to Ledger Engine

Thanks for your interest. This is a portfolio-grade project with production discipline —
contributions are welcome if they keep the bar high.

## Ground rules

- **Correctness first.** This is a money ledger. Any change to the transfer/entry path
  must keep the invariant (Σ entries = 0) and ship with tests proving it.
- **No floats for money.** Integer minor units only.
- **Parameterized SQL only.** No string concatenation into queries.
- Follow the phased plan in [`PLAN.md`](./PLAN.md); don't skip the test gates.

## Workflow

1. Branch from `main`: `feature/<short-description>` or `fix/<short-description>`
   (never commit directly to `main`).
2. Make one logical change per commit. Use **Conventional Commits**:
   `feat(ledger): …`, `fix(api): …`, `test(concurrency): …`, `docs: …`, `chore: …`.
3. Run the gate before committing: `./mvnw verify` (compile + tests must pass).
4. Keep new code ≥ 80% covered (100% on the transfer/entry business logic).
5. Open a PR; fill the template; describe the test plan. Merge with `--no-ff`.

## Local setup

JDK 21 + Maven + local PostgreSQL. See [README](./README.md#getting-started).

```bash
createdb ledger
cp .env.example .env     # fill DB url/creds
./mvnw spring-boot:run
./mvnw test
```

## What gets a PR rejected

- A transfer-path change without a concurrency/invariant test.
- Floating-point money, raw SQL, or secrets in the diff.
- Mixing unrelated changes in one PR.

## Code of conduct

By participating you agree to the [Code of Conduct](./CODE_OF_CONDUCT.md).
