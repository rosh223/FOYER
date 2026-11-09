# Foyer build progress (handover file) — DONE 2026-10-15

Second project of 2026-10-15 (after ~/Desktop/projects/veil, which is DONE). Plan: docs/PLAN.md, tests: docs/TEST-PLAN.md.
Ports: UI 3005, edge-gateway 8086, demo shop 3006. Stop the Veil stack (`docker compose stop` in ../veil) before running Foyer.

- [x] Research, plan, test plan
- [x] foyer-core (permutation, PoW, passes) + property/statistical tests
- [x] contracts
- [x] room-service
- [x] queue-service
- [x] insight-service
- [x] edge-gateway
- [x] foyer-guard plugin + demo-shop
- [x] docker compose (all services healthy)
- [x] smoke test: scripts/smoke-test.sh — 59/59 (3 consecutive runs)
- [x] Angular: waiting room, verify, console, landing
- [x] Playwright e2e: scripts/browser-e2e.sh — 17/17 (screenshots in e2e/screenshots)
- [x] k6 load: scripts/load-test.sh (numbers below)
- [x] chaos: scripts/chaos-test.sh — all pass (Kafka, insight, room-service down; Redis restart; leader kill → lease moved in 4 s)
- [x] k8s: base + keda overlay (cron pre-scale + Kafka lag); scripts/k8s-validate.sh 35/35 each
- [x] docs (README, HOW-IT-WORKS, TESTING, PLAN corrections), CI workflow

## Notes
- Reuse Veil patterns: pom/Dockerfile/compose/outbox/Testcontainers (Kafka 3.8.1 + TESTCONTAINERS_HOST_OVERRIDE), Kafka
  broadcast consumers built from Boot's ConsumerFactory, RESP2 for any RediSearch use, Playwright screenshots via Docker.

## Bugs found so far (for docs/TESTING.md)
- Session cap over-admitted (6 vs cap 3): admitted-but-unclaimed people weren't counted → QueueState.outstanding().
- Spoofable client IP: queue-service rate-limits on the first X-Forwarded-For entry; nginx now overwrites XFF with $remote_addr.
- "Lobby now" preset was +1 minute (room still SCHEDULED) → fixed rounding in room-form preset().
- .card etc. were unlayered CSS and beat Tailwind bg-* utilities (washed-out dark cards) → moved into @layer components.
- Mobile overflow 84px on /console and /console/integrations: grids without grid-cols-1 → added everywhere.
- History "room.open-nowby" (Angular drops whitespace between elements) → margin.
- Lobby count stale right after joining → reload room 1.5 s after a lobby join.
- Expected-behaviour note: Java guard verifies passes offline, so a completed pass still opens pages until expiry; the
  shop's per-pass booking limit stops reuse (e2e asserts this). /api/passes/verify (nginx) does reject completed passes.
- Admin "open now": lobby visitors learn within their lobby poll (≤30 s).
- Ed25519: JDK throws SignatureException (not false) for some corrupted signatures → reported MALFORMED; now BAD_SIGNATURE,
  unit test flips every signature byte.
- Admission fell to 79% of target when queue-service was saturated: the ticker made 5-6 sequential Redis calls (each queued
  behind visitor traffic) so ticks exceeded the 5 s catch-up cap, and lastTick was committed before the admit. Now: inputs
  fetched in parallel, catch-up cap 15 s, lastTick/carry committed only after the admit. 2x load: 1199 vs ~1200-1280.

## Load numbers (single queue-service, 4 GiB Docker VM shared with k6, after warm-up)
- 100 joins/s + 1000 polls/s, 60 s: 0 errors / 72.6k req; status p50 1.35 ms p95 3.35 ms p99 10.5 ms; join (challenge+join)
  p95 8.8 ms; admitted 1254 vs target 1200/min. Repeat run: status p95 3.9 ms, join p95 10.5 ms, admitted 1257.
- 200 joins/s + 2000 polls/s (saturation, ~2100 req/s): 0 errors / 132k req, status p95 560 ms, join p95 1.8 s, 9% iterations
  dropped by k6; admission rate held (1199). → one instance ≈ 2k req/s; scale out (KEDA overlay).
- First run right after a rebuild (cold JIT): status p95 1.16 s — warm-up matters; readiness should include warm-up.
- With Kafka down, live "eventsBuffered" read 0: the producer holds unsent records itself until delivery timeout, so only
  failures reached our buffer. Gauge now = retry buffer + in-flight sends. publish() also parks events if the worker pool
  rejects the task, so a saturated pool can never fail a visitor's join.
- Chaos test's own mistakes fixed: arithmetic (191 not 201), line fully served before the failover step.
