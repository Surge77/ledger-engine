## What & why
Describe the change and the motivation.

## Phase
Which phase of [`PLAN.md`](../PLAN.md) does this advance?

## Test plan
- [ ] `./mvnw verify` passes (compile + tests)
- [ ] New/changed transfer-path logic has invariant + concurrency tests
- [ ] Coverage ≥ 80% on touched business logic

## Money-safety checklist
- [ ] No floating-point money (integer minor units only)
- [ ] Parameterized SQL only — no string-built queries
- [ ] No secrets in the diff
- [ ] Double-entry invariant (Σ entries = 0) preserved

## Notes
