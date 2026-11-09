# Foyer test plan

Written before the code; results go in [TESTING.md](TESTING.md). A waiting room fails in three ways: it lets too many
people through (the origin crashes), it is unfair (or can't be shown to be fair), or it falls over itself. The plan targets
all three.

## Levels

| Level | Tooling | Proves |
|---|---|---|
| Unit / property | JUnit 5 | The permutation is a bijection for every size (1…10,000 and random large sizes), different seeds give different orders, commit-reveal verifies; PoW accepts valid and rejects wrong nonces, expired or reused challenges; passes reject tampering, wrong key, expiry, wrong room |
| Statistical fairness | JUnit | Lobby lottery positions are uniform: chi-square test across buckets; Spearman correlation between join order and position ≈ 0 |
| Integration | Testcontainers (Redis 8, Kafka, Postgres) | Join dedupe, frontier advance at the configured rate, active-session cap, no-show compensation, lobby freeze at opening, events on Kafka, config push, idempotent event storage, rollups |
| Concurrency | JUnit + parallel clients | 10,000 concurrent joins produce 10,000 distinct tickets; no position is admitted twice; the ticker runs on exactly one instance per room |
| Plugin | JUnit + embedded servlet container | `foyer-guard` admits valid passes, redirects missing or invalid ones, verifies offline |
| API / e2e | `scripts/smoke-test.sh` | Full flow through the gateway: operator login, create room, lobby, open, lottery reveal, join with PoW, position, admission, pass, origin access, completion, stats |
| Browser e2e | Playwright | Waiting room in a real browser: PoW in a Web Worker, countdown, place in line, "your turn", redirect to the demo shop, purchase; verify page recomputes the position |
| Load | k6 | Status polls per second at p95 < 50 ms; joins per second; frontier correctness under load |
| Chaos | `scripts/chaos-test.sh` | Kafka down (queue keeps working), room-service down, insight-service down, one queue-service instance killed mid-sale (lease moves) |
| Infrastructure | kustomize + kubeconform | Manifests valid |

## Exit criteria

- All automated suites pass; the fairness tests pass at p > 0.01.
- The origin never receives more concurrent visitors than the room's cap during a rehearsal with bots.
- Every page works on a 390 px phone and a desktop, with no console errors.
- A visitor can verify their lottery position from the public seed in the browser.
