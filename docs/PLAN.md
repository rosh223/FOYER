# Foyer: product plan and system design

Written before the code. [RESEARCH.md](RESEARCH.md) explains why this problem was chosen.

## 1. Product

**Foyer is an open-source virtual waiting room for flash crowds.** Put it in front of a ticket sale, a Tatkal-style booking
window, an admission form or a product drop: visitors wait in Foyer, the site only receives the traffic it can handle, bots
get no head start, and everyone can check that the order was fair.

### Ways to adopt (all free, self-hosted)

| Adoption | Strength | How |
|---|---|---|
| **Java filter / Spring Boot starter** (`foyer-guard`) | Hard: the origin refuses visitors without a valid pass | Add the dependency and two properties. Passes are verified **offline** with Foyer's public key. |
| **nginx `auth_request`** | Hard, any stack (PHP, Node, Python…) | A 10-line nginx snippet calls Foyer's `/api/passes/verify`. |
| **JS tag** | Soft (sends people to the queue; doesn't stop direct requests) | `<script src="https://foyer.example/foyer.js" data-room="coldplay-mumbai">` |
| **REST API** | For mobile apps | Join, poll, receive a pass. |

### Personas and screens (Angular)

| Screen | Who | Purpose |
|---|---|---|
| Landing `/` | Everyone | The story, a live animation of a lobby lottery, adoption paths, architecture |
| Waiting room `/q/:slug` | Visitors (millions) | Lobby countdown, lottery, place in line, estimated wait, "your turn", sold out. Mobile-first, English and Hindi, no CAPTCHA |
| Fairness check `/q/:slug/verify` | Visitors, journalists | Enter your lobby number: recompute your place from the revealed seed, check the seed against the published commitment |
| Console `/console` | Operators | Rooms, live dashboard per room (queue, arrivals, admissions, wait, bots blocked), controls (rate, pause, open, close), fairness report, integration snippets, rehearsal (simulated crowd with bots) |
| Demo shop (separate app, `:3006`) | Demo | "Tixly", a ticket shop protected by `foyer-guard`; buying reports completion to Foyer |

## 2. Architecture

```
 Visitors ──► nginx / CDN ──► Angular waiting room ──► edge-gateway ──► queue-service ×N (WebFlux)
                                                       (per-IP limits)      │  join · status · passes · verify
 Origin sites ── foyer-guard (offline verify) / nginx auth_request ─────────┘  admission ticker (Redis lease per room)
                                                                            │
 Operators ──► console ──► edge-gateway (JWT) ──► room-service ── outbox ──► Kafka foyer.rooms (compacted) ──► queue-service
                                               └► insight-service ◄── Kafka foyer.events ◄── queue-service
 Redis: tickets, frontier counters, sessions, leases, rate limits, used challenges      Postgres: rooms (room), stats (insight)
```

| Module | Tech | Responsibility |
|---|---|---|
| `foyer-core` | Plain Java 21 | Lottery permutation (keyed Feistel network with cycle walking), commit-reveal, proof-of-work, Ed25519 passes, HMAC tickets. No dependencies, so the guard plugin can use it. |
| `contracts` | Java records | Room config snapshot, queue events |
| `queue-service` | Spring WebFlux, Lettuce (Redis), Kafka | The hot path: challenge, join, status, pass issue, verify, completion callback; time-based room transitions; the admission ticker |
| `room-service` | Spring MVC, JDBC, Flyway | Operators, rooms, schedules, origin keys, seeds and commitments, outbox; rehearsal simulator |
| `insight-service` | Spring MVC, JDBC, Kafka | Event store, per-minute stats, wait-time distribution, fairness report |
| `edge-gateway` | Spring Cloud Gateway | Console JWT and roles; public API rate limits per IP |
| `foyer-guard` | Java (Servlet API) + Spring Boot auto-configuration | The origin plugin |
| `demo-shop` | Spring MVC | The protected demo origin |
| `frontend` | Angular 22, Tailwind v4 | Landing, waiting room, verify page, console |

## 3. Core algorithms

**Tickets and the frontier (O(1) per poll).** Each room keeps a frontier counter `served`: everyone whose position is below it
has been admitted. A visitor's place in line is `position − served`. No sorted set scan, no per-visitor writes on each poll,
so millions of polls cost one Redis `MGET` each.

**Lobby lottery.** Visitors who join before opening get a lobby number `i` (an atomic `INCR`). At opening, the lobby size `N`
is frozen and the seed is revealed; position = `permute(i, N, seed)`, a keyed Feistel permutation over `[0, N)` (cycle
walking for non-power-of-two sizes). It's a bijection, so every lobby member gets exactly one distinct position, computed on
demand, never materialised. Later arrivals get positions `N, N+1, …` in arrival order.

**Commit-reveal.** At room creation, a 256-bit seed is generated and `SHA-256(seed)` is published on the room page. The seed
is revealed when the room opens. Anyone can recompute every lobby position and check the seed against the commitment;
the operator can't change the order after seeing who joined.

**Admission ticker.** Each second, for each open room, the instance holding the room's Redis lease advances `served` by
`rate/60` (fractions carried over), but never beyond the queue length and never while the number of active sessions is at
the room's cap. Passes not claimed within a few minutes don't count as active. If many admitted visitors never claim, the
ticker admits proportionally more (bounded) so the origin still gets its target rate.

**Proof of work, no CAPTCHA.** `POST /challenge` returns a signed, short-lived challenge; the browser (Web Worker) finds a nonce
so that `SHA-256(challenge ‖ nonce)` has `d` leading zero bits (about a second for a phone at the default difficulty).
Challenges are single-use. Difficulty rises for IP addresses and subnets that join often, and joins are rate-limited per IP.

**Passes.** `fp1.<payload>.<Ed25519 signature>` with room, visitor, pass id, not-before and expiry (10 minutes by default). The
public key is published at `/.well-known/foyer-keys`, so origins verify without calling Foyer. The origin reports a
completed purchase (`POST /api/origin/complete` with the room's origin key), which ends the session and marks the pass used.

## 4. Data and events

- **Kafka `foyer.rooms`** (compacted, key room id): room config snapshots (rate, caps, schedule, desired state, seed).
  Every queue-service instance consumes it with its own consumer group.
- **Kafka `foyer.events`** (key room id): `JOINED`, `REJECTED` (reason), `OPENED` (lobby size, seed), `ADMITTED` (range),
  `CLAIMED` (waited ms), `COMPLETED`, `STATE`.
- **Postgres (room-service):** `operators`, `rooms`, `room_epochs`, `origin_keys`, `admin_audit`, `outbox_events`.
- **Postgres (insight-service):** `events` (idempotent by event id), `room_minute` rollups, `lobby_entries` (lobby number → place, for the fairness report), `sales`.

## 5. Scale and resilience

- queue-service is stateless (state in Redis), so it scales horizontally; the waiting page is static and polls with a
  server-suggested interval that grows with distance from the front (people far back poll less often).
- One Redis round trip per poll; joins are a short Lua script (dedupe, rate check, ticket).
- If Kafka is down, events are buffered and the queue keeps working; if room-service is down, rooms keep running on their
  last config; if insight-service is down, only the dashboards lag.
- Kubernetes: HPA on queue-service, PDBs, NetworkPolicies, KEDA overlay for insight on consumer lag.

## 6. Build order

1. `foyer-core` (permutation, PoW, passes) with property tests.
2. `contracts`, `room-service`, `queue-service`, `insight-service`, `edge-gateway`, `foyer-guard`, `demo-shop`.
3. Compose stack + smoke test.
4. Angular: waiting room, verify page, console, landing.
5. Tests per [TEST-PLAN.md](TEST-PLAN.md): load (k6), chaos, browser e2e (Playwright), fairness statistics.
6. Kubernetes, docs.

## 7. Changes made while building

- The console's live numbers come from a 1-second poll of queue-service (`/api/console/live/{room}`, straight from Redis),
  not a server-sent event stream from insight-service: simpler, and correct even when Kafka or insight is down.
- Admitted-but-unclaimed people hold a slot under the session cap for 40 seconds (10-second buckets), not "a few minutes":
  rehearsals showed unclaimed bot admissions stalling a small cap with a 2-minute window.
- Rooms are reset into numbered *epochs* (rounds): each has its own seed and commitment, and old rounds stay verifiable.
- The waiting page, while in the lobby, also watches the cacheable room info every ~5 s so an early opening reaches
  everyone within seconds (the status poll in the lobby is up to 30 s).
- The KEDA overlay pre-scales queue-service on a cron schedule before a sale, because cold JVMs showed much worse tails.
