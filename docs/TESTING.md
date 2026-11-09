# Testing Foyer

Results of the plan in [TEST-PLAN.md](TEST-PLAN.md), run on 2026-10-15 on one laptop: Docker Desktop with 4 GiB for the whole
stack, k6 and Chromium on the same machine. The final run started from empty volumes (`docker compose down -v`, then
`up --build`).

## Automated suites

| Suite | Command | Result |
|---|---|---|
| Java unit, property, statistical and integration tests | `./mvnw verify` | **36 tests, 0 failures** (8 classes) |
| Frontend unit tests (Vitest) | `cd frontend && npx ng test --watch=false` | **16 tests** |
| API smoke test | `./scripts/smoke-test.sh` | **59 / 59** on every run (5 runs, 2 on the final fresh stack) |
| Real-browser end to end (Playwright, Chromium) | `./scripts/browser-e2e.sh` | **17 / 17**, three consecutive runs on the final build |
| Chaos | `./scripts/chaos-test.sh` | **22 / 22** |
| Session cap with bots | `./scripts/rehearsal-cap-check.sh` | **cap held** (peak 24 vs cap 25) |
| Load (k6) | `./scripts/load-test.sh` | see below |
| Kubernetes manifests | `./scripts/k8s-validate.sh` | **35 / 35 valid** in each overlay (`-strict`, KEDA CRD schemas) |

### Java tests

| Class | What it proves |
|---|---|
| `LotteryTest` (7) | Permutation for every lobby size 1…2,000 and large random sizes; different seeds → different orders; pinned vectors shared with the browser implementation; uniform places (chi-square); no correlation between arrival and place (Spearman); commit-reveal |
| `TokensTest` (4) | Proof of work accepts valid work and rejects wrong, expired, wrong-scope and forged challenges; passes reject tampering (every single-byte signature change), the wrong key, the wrong room, expiry, not-yet-valid; tickets are tamper-proof |
| `AdmissionTest` (4) | The admission step: rate by elapsed time, carry, cap, no-show boost, poll intervals |
| `FoyerGuardFilterTest` (6) | The plugin: valid pass passes, query → cookie redirect, browsers redirected with `return`, APIs get 403, wrong room and expired refused |
| `GatewayTest` (2) | JWT required on console routes; viewers can't write |
| `RoomServiceIT` (4, Testcontainers Postgres + Kafka) | Demo seed and login lockout; a snapshot with the commitment reaches Kafka while the seed stays private; schedule frozen once open, reset starts a new epoch, old seeds stay verifiable; validation and roles |
| `QueueIT` (7, Testcontainers Redis + Kafka) | Lobby lottery gives distinct places that match the revealed seed; admission follows the rate and passes work once at the origin; the session cap holds; forged, reused and excessive joins rejected; 300 concurrent joins get distinct numbers; sold out and reset; events reach Kafka |
| `InsightIT` (2, Testcontainers Postgres + Kafka) | A fair lottery yields a fair report and redelivered events aren't double-counted; an unfair (first-come) order is detected |

### Smoke test (59 checks, `scripts/smoke-test.sh`)

Sign-in and roles (forged tokens, a spoofed `X-User-Role` header, viewers can't write); public room info; proof of work
(wrong nonce, reused challenge, forged challenge); tickets (rejoin keeps the place, tampered ticket refused); admission and
passes (same pass on every poll, verifies only for its room, tampered signature refused); the protected shop (browser
redirect, API 403, pass → cookie, booking, completion makes the pass spent, a second booking refused); a lottery from lobby
to verification (six lobby members get places that are a permutation of 1…6, the revealed seed hashes to the commitment,
a late joiner goes to place 7); origin keys, sold out and reset; bot defences (per-IP limit, **a spoofed X-Forwarded-For
doesn't get round it**, simulator headers need the key); insight counts and the fairness report.

### Browser end to end (17 tests, `scripts/browser-e2e.sh`)

Landing; **shop → waiting room → proof of work in a Web Worker → admitted → back to the shop → purchase**, pass moved into an
HttpOnly cookie and the same pass refused for a second booking; a reload keeps the same ticket; the `return` parameter is not
an open redirect; Hindi; unknown sale; an admin creates a room in the console, three visitors wait in the lobby with lobby
numbers 1–3, the admin opens the sale early, all three are placed by the lottery and admitted (about 5 s), the public verify
page recomputes each visitor's place in the browser and matches the server, and the fairness report shows the commitment
check; live controls (rate change, rehearsal with bots); integrations snippets; a viewer can't change anything; signed-out
redirect; **no horizontal overflow on 9 pages at 390 px and 1440 px**; **no console errors** anywhere.

## Load (k6, `scripts/load-test.sh`)

Straight at one queue-service (the gateway's per-IP limit would throttle a single load generator), each request with its own
`X-Forwarded-For`. Joins solve real proof of work (difficulty 8 for the load room). Every run creates its own open room
admitting 1,200 a minute.

| Run | Requests | Errors | Status poll | Join (challenge + join) | Admitted vs target |
|---|---|---|---|---|---|
| 100 joins/s + 1,000 polls/s, 60 s, warm | 72,601 | 0 | p50 1.35 ms · p95 3.35 ms · p99 10.5 ms | p95 8.8 ms | 1,254 vs ≈1,280 |
| same, repeat (final build) | 72,602 | 0 | p50 1.45 ms · p95 4.24 ms · p99 11.8 ms | p95 11.0 ms | 1,257 vs ≈1,260 |
| same, on an instance restarted a minute earlier (cold JIT) | 70,662 | 0 | p95 49 ms · p99 1.74 s | p95 208 ms | 1,259 |
| 200 joins/s + 2,000 polls/s, 60 s (saturation) | 132,114 | 0 | p95 560 ms · p99 882 ms | p95 1.8 s | **1,199** vs ≈1,280 (after the ticker fix; 1,011 before) |

- One queue-service handles about **1,100 requests a second with millisecond latency** on this machine and saturates at about
  2,100 a second (still no errors). It is stateless, so capacity scales with replicas.
- Joins counted by k6 always matched the server's total (e.g. 5,720 + 300 in setup = 6,020).
- Cold instances are much slower for their first minute. Hence the KEDA overlay pre-scales before a sale.

## Chaos (`scripts/chaos-test.sh`)

A sale open at 60 a minute with 150+ people waiting, then:

| Fault | Checked | Result |
|---|---|---|
| Kafka stopped | 20 people join; admission continues; the event backlog shows in the console; after Kafka returns, every join reaches insight (171/171) | pass |
| insight-service stopped | joins and console live numbers unaffected; it catches up from Kafka (181/181) | pass |
| room-service stopped | room info, joins and status still served from the cached room; the console reports the outage (5xx) rather than wrong data; back to 200 after restart | pass |
| Redis restarted | the frontier and every visitor's place survive (append-only file); places equal the ones given at join time | pass |
| Admission leader killed (second instance running) | lease moved to the other instance in **4 s**; 29–43 admitted in the 20 s around the failover at 1/s (the no-show boost refills unclaimed slots) | pass |
| Afterwards | visitors' tickets still valid; insight joins equal tickets issued (271/271), nothing double-counted | pass |

## Session cap with bots (`scripts/rehearsal-cap-check.sh`)

Room capped at 25 people shopping at once, 600/min, a 20-second lobby; rehearsal with 600 simulated people and 200 bots;
live session count sampled twice a second for 90 seconds.

- **Peak 24 shopping at once (cap 25).** The cap held, and was used fully.
- 101 people admitted, 75 bought, 16 no-shows (refilled).
- Bots: 5,000 attempts, 4,910 stopped (fake work, reused challenges, per-IP limits). 90 paid the toll and joined — exactly
  the per-IP limit (3 addresses × 30 joins a minute) — and the lobby lottery spread them through the line.
- The same rehearsal **without a lobby** (room open immediately, so first come, first served) is instructive: bots arrive
  first, take the front of the line, never claim, and each holds a slot for the claim window. In a minute, 31 were let in
  with the original 2-minute window (bug 9), then 62 and 52 with the 40-second window — and in that last run only 2 of the
  52 were people. The cap held every time. It's exactly the case the lobby exists for.

## Bugs found by testing (and fixed)

1. **The session cap let in twice its size** (QueueIT: 6 admitted with a cap of 3). People admitted but not yet back to claim
   weren't counted. Fix: count admitted-but-unclaimed people against the cap (`QueueState.outstanding`).
2. **Visitors could choose their own IP address.** queue-service rate-limits on the first `X-Forwarded-For` entry, and the
   nginx config (copied from an earlier project) *appended* to whatever the client sent. Fix: the edge replaces the header
   with the real peer address; the smoke test now proves a spoofed header doesn't get round the per-IP limit.
3. **Admissions fell to 79% of the configured rate under saturation** (k6). Each tick made five or six Redis calls in a row,
   each queued behind visitor traffic, so ticks took over 5 seconds — past the catch-up cap — and the tick's clock was
   committed before the admit. Fix: inputs read in parallel, catch-up cap 15 s, clock committed only after admitting.
   Result: 1,199 vs ≈1,280.
4. **Some corrupted pass signatures were reported as "malformed"**, not "bad signature": the JDK's Ed25519 verifier throws for
   an invalid curve point instead of returning false. (Rejected either way.) Fix: map it to BAD_SIGNATURE; a unit test now
   flips every byte of a signature.
5. **"Events buffered" read 0 while Kafka was down** (chaos test). The Kafka producer holds unsent records itself until its
   delivery timeout, so only failed sends reached our buffer. Fix: the gauge counts in-flight sends too. Also, if the worker
   pool ever rejects a publish, the event is parked instead of failing the visitor's join.
6. **An early opening took up to 36 seconds to reach the lobby** (browser e2e, flaky). Lobby members poll status every 30 s.
   Fix: the page also watches the cacheable room info every ~5 s. Now about 5 s.
7. **The "Lobby now" schedule preset created a room that wasn't open** (browser e2e): it added a minute of slack. Fix: "now"
   rounds down, later times round up.
8. **Rehearsals in new rooms never finished sessions** — only the two demo rooms had an origin key the simulator knew, so
   simulated shoppers held their slots until passes expired. Fix: a temporary origin key per rehearsal, revoked at the end.
9. **A few dozen unclaimed admissions stalled a small cap** (cap check: 31 admitted in a minute at 600/min). Admitted people
   who never came back held a slot for 2 minutes. Fix: a 40-second window in 10-second buckets (the slowest legitimate poll
   is 36 s). Throughput doubled; the cap still held.
10. **Dark sections of the landing page were washed out** (screenshots): the `.card`/`.btn` classes were unlayered CSS and
    beat Tailwind's `bg-*` utilities. Fix: component classes moved into `@layer components`.
11. **84 px of horizontal scrolling on phones** on the rooms list and integrations pages: grids without a mobile column
    count size their single track to the content. Fix: `grid-cols-1` on every responsive grid; the e2e suite checks 9 pages
    at two widths.
12. **"room.open-nowby Meera Nair"** in the history list: Angular drops whitespace between elements. Fix: a margin.
13. **The lobby count read 0 right after joining.** Fix: reload room info shortly after a lobby join.

Mistakes in the tests themselves, also fixed: the e2e suite expected a used pass to be bounced (the offline guard can't know;
the shop's one-booking-per-pass rule is what stops reuse, and the test now checks that); a smoke check sent a form with a JSON
content type; a tamper check changed only the last base64 character, whose low bits don't affect the decoded bytes; the chaos
test miscounted (191, not 201) and ran the failover after the line was already empty. A final re-run (2026-10-16) found a flaky integration test. At the
4-bit test difficulty, the "no work" nonce in `forgedReusedAndExcessiveJoinsAreRejected` passed the proof of work 1
time in 16 and used up the challenge, so the valid join after it was correctly refused as a reuse. The test now
picks a nonce that is known to fail.

## Manual test script (about 15 minutes)

1. `docker compose up -d --build --wait`; open http://localhost:3005. The demo rooms show live phases.
2. **Join a live queue** (Tatkal). Watch the browser check, then "It's your turn", then the Tixly shop. Book. Back in the
   console's Tatkal room, *Purchases* goes up.
3. Open http://localhost:3005/q/echoes-mumbai within 10 minutes of start-up: you're in the lobby with a lobby number and a
   countdown. Open it in a second browser profile too. When the countdown ends, both get a place; click **Check the lottery
   yourself** and the page recomputes it.
4. Switch the waiting room to हिं.
5. Sign in to the console as the admin. Create a room with **Lobby now · opens in 5 min**; open its waiting room in two
   profiles; press **Open the sale now**; both are placed within seconds.
6. In that room, start a rehearsal (400 people, 200 bots); watch the live tiles, the chart and the bot-defence panel. Lower
   the rate to 60 and **Apply now**: the "let in, last minute" tile follows.
7. Open the **Fairness report**. Then **Reset for a new round**: the verify page now lists both rounds.
8. Sign in as `ops@foyer.test`: everything is visible, nothing can be changed.
9. `docker compose stop kafka`; join the Tatkal queue — it still works; `docker compose start kafka`.
