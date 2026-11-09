# How Foyer works

A walkthrough of one sale, from the organiser creating a room to the last person checking out. Code references are to
the modules in the repository root.

## 1. A room and its rounds

An organiser creates a room in the console (`room-service`, `RoomService.create`): an address (`/q/echoes-mumbai`), the
site admitted people go to, a **lobby opening** and a **sale opening** time, a rate (people per minute), a session cap,
the pass lifetime and the bot toll (proof-of-work difficulty).

At creation, room-service draws a 256-bit random **seed** and stores `SHA-256(seed)` as the **commitment**. The commitment
is public from that moment (room info, the verify page, the console). The seed stays secret until the sale opens.

A room can be **reset** into a new round (an *epoch*): new seed, new commitment, empty line. Every Redis key is namespaced
`fy:<room>:<epoch>:…`, so a reset is instant and old tickets simply stop matching (`410 old_ticket`). Past rounds keep their
seed and commitment and stay verifiable (`GET /api/public/rooms/{sale}/epochs`).

Once a room has opened, its schedule is frozen: moving the opening time after people joined would change who was in the
lobby. Rate and cap can change any time (`PUT /rooms/{id}/rate`), and take effect within a second.

**Config flow.** room-service writes the room and an outbox row in one transaction; the outbox publisher sends a full
snapshot (`RoomConfig`, including the seed) to the compacted Kafka topic `foyer.rooms`. Every queue-service instance
consumes that topic with its own consumer group and keeps all rooms in memory (`RoomStore`); on start-up it also loads them
from `room-service /internal/rooms`. So a queue-service never asks room-service anything on the visitor's path, and keeps
selling if room-service is down.

## 2. Phases

`Phase.of(room, now, soldOut)` (queue-service) is computed, not stored:

| Phase | When | Joining |
|---|---|---|
| `SCHEDULED` | before the lobby opens | refused (`409 not_open`) |
| `LOBBY` | lobby open, sale not yet | lobby ticket |
| `OPEN` | after opening | queue ticket |
| `PAUSED` | organiser paused | queue ticket (nobody is admitted) |
| `SOLD_OUT` | the site said so | refused (`410`) |
| `CLOSED` | organiser closed | refused (`410`) |

## 3. Joining: proof of work, then a ticket

1. `POST /api/q/{sale}/challenge` returns `fc1.<payload>.<HMAC>`: room scope, a random id, the difficulty, an expiry
   (`foyer-core ProofOfWork`). Difficulty is the room's base plus a bump for busy networks: +2 bits from 20 joins a minute
   from the same /24 (or /48), +4 from 80, +6 from 320.
2. The browser finds a nonce such that `SHA-256(challenge ":" nonce)` starts with that many zero bits. It runs in a Web
   Worker (`frontend/src/app/queue/pow.worker.ts`) with a small synchronous SHA-256, so the page stays responsive; WebCrypto
   is asynchronous and far too slow for hundreds of thousands of tiny hashes.
3. `POST /join` verifies the signature, expiry, scope and the work, then marks the challenge id used (`SET NX`) so a
   solution can't be replayed. A single Lua script (`QueueState.JOIN`) then counts the join against the per-IP and
   per-subnet minute counters and, atomically, takes the next **lobby number** (`INCR pre`) or, if the room has opened,
   the next **queue number** (`INCR post`). Because the "has it opened?" check and the increment are in the same script, no
   one can slip into the lobby after it was frozen.
4. The response is a ticket `ft1.<payload>.<HMAC>` (room and epoch, visitor id, kind L/Q, number, joined-at). The ticket is
   the visitor's whole state on the client: the page keeps it in `localStorage`, and sending it back to `/join` returns the
   same ticket (no new place).

The client IP is the first `X-Forwarded-For` entry. That is only safe because the edge **replaces** the header: the
frontend nginx sets it to `$remote_addr` (compose) and ingress-nginx does the same in Kubernetes. The rehearsal simulator
may present simulated addresses, but only with the shared rehearsal key.

## 4. Opening: the lottery

Each second, the admission ticker (§6) checks every room. The first tick at or after the opening time runs the `OPEN`
script: it freezes the lobby size `N` (`preN = pre`) and records the opening time, exactly once even with many instances.

A lobby member's place is `permute(lobbyNumber, N, seed)` (`foyer-core Lottery`):

- A **Feistel network**: split the number into two halves of `b` bits (2b ≥ bits needed for N), run 6 rounds of
  `L, R = R, L xor F(round, R)`, where `F` is the first 64 bits of `HMAC-SHA256(seed, round ‖ R)` masked to `b` bits.
  A Feistel network is a permutation of `[0, 2^2b)` whatever `F` is.
- **Cycle walking**: if the result is ≥ N, apply the network again until it lands in `[0, N)`. Because the network is a
  permutation, this is a permutation of `[0, N)`.

Nothing is stored: each place is computed when the visitor polls. The browser has an identical implementation
(`frontend/src/app/core/lottery.ts`), pinned to the Java one by shared test vectors, so the verify page can recompute any
place. Queue members (joined after opening) get places `N, N+1, …` in arrival order.

`LotteryTest` checks that it is a permutation for every N from 1 to 2,000 and for large random sizes, that places are uniform
(chi-square) and uncorrelated with arrival order (Spearman), and the cross-language vectors.

## 5. Waiting: O(1) status

The line is a single number, the **frontier** `served`: every place below it has been admitted. A status poll is one
`MGET` (opened?, lobby size, counters, served, sold out) plus a lookup of the visitor's claimed pass, then arithmetic:

- `position` from the ticket (lottery or arrival order),
- `ahead = position − served`, `eta = ahead / rate`,
- `pollSeconds` grows with distance from the front (3 s within a minute of the front, up to 30 s far back), so a big crowd
  costs little. The page adds ±20% jitter so polls spread out.

In the lobby, the status poll is slow (up to 30 s). To notice an early opening quickly, the page also watches the room's
public info (`GET /api/q/{sale}`, cacheable for a second, so a CDN absorbs it) every ~5 s, and asks for its place as soon as
the phase changes.

## 6. Admission: the ticker

`AdmissionTicker` runs every second on every queue-service instance; for each open room, only the instance holding the
room's **lease** (a Redis key with a 4-second TTL, renewed each tick) does anything. If that instance dies, another takes
the lease within a few seconds (measured: 4 s).

Each tick (`Admission.step`):

- `want = rate × elapsed / 60 × boost + carry` — by **elapsed time since the last successful tick** (capped at 15 s), so a
  slow or late tick catches up instead of losing admissions; fractions carry over.
- **Session cap**: `free = cap − active sessions − outstanding`, where *outstanding* is people admitted in the last 40
  seconds who haven't claimed their pass yet (10-second buckets). Without it, the cap would let in a burst between someone
  being admitted and their next poll; with a longer window, no-shows (and bots that paid the toll but never return) would
  stall the line.
- **No-show boost**: if only some admitted people come back, admit proportionally more (bounded), so the site still gets
  the rate it asked for.
- `ADMIT` (Lua) moves the frontier by `min(want, free, waiting)`.

The ticker reads its inputs in parallel and commits its clock only after admitting: under saturation, five sequential Redis
calls each waited behind visitor traffic, ticks took more than 5 seconds and admissions fell to 79% of the target.

## 7. Your turn: the pass

When a poll finds `position < served`, queue-service issues a pass (`foyer-core Passes`):

```
fp1.<base64url(roomId | visitorId | passId | notBefore | expiresAt)>.<Ed25519 signature>
```

The `CLAIM` script stores it against the visitor (`SET NX`, so every later poll returns the same pass), adds it to the
room's active-session set (scored by expiry), and counts the claim. The response carries `continueUrl` (the room's site with
`?foyer_pass=…`). The page sends the visitor back to the page they came from instead if the guard's `return` parameter is on
the **same origin**, so the waiting room can't be used as an open redirect.

## 8. At the site

- **`foyer-guard`** (servlet filter, Spring Boot auto-configured): on protected paths, a pass in the query string is
  verified and moved into an `HttpOnly`, `SameSite=Lax` cookie with a redirect that cleans the URL; a pass in the cookie is
  verified **offline** with the public key (signature, room, not-before, expiry). Browsers without a valid pass are
  redirected to the waiting room with `return=`; API calls get `403` JSON. The claims are exposed as a request attribute.
- **nginx**: `auth_request` to `/api/passes/verify?room=…`, which also refuses passes already completed.
- **JS tag** (`/foyer.js`): stores a pass from the URL, checks room and expiry, and otherwise redirects to the waiting room.
  Soft protection only.
- **Callbacks** with the room's origin key (`X-Foyer-Origin-Key`, stored as a SHA-256 hash): `POST /api/origin/complete`
  ends the session (freeing the slot) and marks the pass used; `POST /api/origin/sold-out` flips the room to sold out, and
  every waiting page shows it on its next poll.

## 9. Events and insight

queue-service publishes `JOINED`, `REJECTED` (with reason), `OPENED` (lobby size, seed), `ADMITTED` (range), `CLAIMED`
(waited ms), `COMPLETED` and `SOLD_OUT` to `foyer.events` (12 partitions, keyed by room). Publishing never touches the
visitor's request: sends happen on a worker thread; if Kafka is unavailable, events wait in a bounded buffer and are retried
every two seconds (the console shows the backlog).

insight-service consumes them in batches (one consumer group across replicas) and stores them idempotently by event id, so
redelivery never double-counts. It keeps per-minute rollups, `lobby_entries` (lobby number → place) and per-sale facts, and
serves:

- **overview**: flow per minute, totals by rejection reason, wait percentiles, the busiest networks (hashed /24s),
- **fairness**: whether the stored seed matches the commitment, Spearman's ρ between arrival order and place (with the 99%
  noise threshold `2.58 / √(n−1)`), and the average place for each tenth of the lobby.

## 10. The console

Angular 22 (standalone components, signals, zoneless). The room page polls `GET /api/console/live/{room}` every second —
answered straight from Redis by queue-service — and the insight overview every five seconds. Controls: rate and cap, open
now, pause/resume, close, reset; rehearsal; origin keys (secret shown once); audit history. Viewers can see everything and
change nothing (the gateway refuses non-GET console calls for the VIEWER role, and the services check again).

**Rehearsal** (`room-service RehearsalService`): virtual threads play simulated people (a spike plus a trickle, 12%
no-shows, 3–15 s of shopping, then a completion) and bots (fake proof of work, reused challenges, a few shared addresses)
through the real visitor API. For rooms other than the demo ones, it creates a temporary origin key so simulated shoppers
can finish their sessions, and revokes it afterwards.

## 11. Deployment

- **Compose**: one of everything, memory-capped to fit in 4 GiB; Redis with an append-only file (the line survives a Redis
  restart — tested).
- **Kubernetes** (`k8s/`): queue-service ×3 with an HPA to 30, PDBs, topology spread, non-root read-only containers,
  NetworkPolicies (each service only accepts traffic from the pods that need it), Redis as a StatefulSet with a volume and
  `noeviction`, Ingress with `/api` straight to the gateway.
- **KEDA overlay**: queue-service is **pre-scaled on a cron schedule** before a sale (a flash crowd is a spike you can see
  coming, and cold JVMs have much worse latency tails), with CPU scaling on top; insight-service scales on Kafka lag.
