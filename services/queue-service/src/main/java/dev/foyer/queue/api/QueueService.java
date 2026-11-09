package dev.foyer.queue.api;

import dev.foyer.contracts.RoomConfig;
import dev.foyer.core.Admission;
import dev.foyer.core.Codec;
import dev.foyer.core.Lottery;
import dev.foyer.core.Passes;
import dev.foyer.core.ProofOfWork;
import dev.foyer.core.Tickets;
import dev.foyer.queue.events.EventPublisher;
import dev.foyer.queue.rooms.Phase;
import dev.foyer.queue.rooms.RoomStore;
import dev.foyer.queue.state.QueueState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What a visitor can do: look at a room, get a challenge, join, and ask where they are (and receive a pass). */
@Service
public class QueueService {

    static final Duration CHALLENGE_TTL = Duration.ofMinutes(2);

    private final RoomStore rooms;
    private final QueueState state;
    private final ProofOfWork pow;
    private final Tickets tickets;
    private final PrivateKey signingKey;
    private final EventPublisher events;
    private final Counter joins;
    private final Counter rejections;
    private final Counter polls;

    public QueueService(RoomStore rooms, QueueState state, ProofOfWork pow, Tickets tickets, PrivateKey signingKey,
                        EventPublisher events, MeterRegistry meters) {
        this.rooms = rooms;
        this.state = state;
        this.pow = pow;
        this.tickets = tickets;
        this.signingKey = signingKey;
        this.events = events;
        this.joins = Counter.builder("foyer.joins").register(meters);
        this.rejections = Counter.builder("foyer.joins.rejected").register(meters);
        this.polls = Counter.builder("foyer.status.polls").register(meters);
    }

    RoomConfig room(String slug) {
        return rooms.bySlug(slug).orElseThrow(() -> ApiError.notFound("Room " + slug));
    }

    static long minute(long millis) {
        return millis / 60_000;
    }

    // ------------------------------------------------------------------------------------------------ room info

    public Mono<Map<String, Object>> info(String slug) {
        RoomConfig r = room(slug);
        Instant now = Instant.now();
        return state.snapshot(r).map(s -> {
            Phase phase = Phase.of(r, now, s.soldOut());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("slug", r.slug());
            m.put("name", r.name());
            m.put("venue", r.venue());
            m.put("description", r.description());
            m.put("phase", phase.name());
            m.put("lobbyOpensAt", r.lobbyOpensAt());
            m.put("opensAt", r.opensAt());
            m.put("ratePerMinute", r.ratePerMinute());
            m.put("epoch", r.epoch());
            m.put("seedCommitment", r.seedCommitment());
            // The seed is revealed only once the lobby has been frozen, so nobody can predict the lottery in advance.
            m.put("seed", s.opened() ? r.seed() : null);
            m.put("lobbySize", s.opened() ? s.lobbySize() : null);
            m.put("lobbyJoined", s.lobbyJoined());
            m.put("waiting", s.waiting());
            m.put("served", s.served());
            m.put("now", now);
            return m;
        });
    }

    // ------------------------------------------------------------------------------------------------ joining

    public Mono<Map<String, Object>> challenge(String slug, String ip) {
        RoomConfig r = room(slug);
        long now = System.currentTimeMillis();
        return state.snapshot(r).flatMap(s -> {
            Phase phase = Phase.of(r, Instant.ofEpochMilli(now), s.soldOut());
            if (!phase.joinable()) return Mono.error(notJoinable(phase));
            return state.subnetJoins(r, ClientAddress.subnet(ip), minute(now)).map(recent -> {
                int difficulty = difficulty(r.powDifficulty(), recent);
                var c = pow.issue(r.id() + ":" + r.epoch(), difficulty, CHALLENGE_TTL.toMillis(), now);
                return Map.<String, Object>of("token", c.token(), "difficulty", difficulty, "expiresAt", c.expiresAt());
            });
        });
    }

    /** Busy networks pay more: +2 bits (4x the work) per step of sustained joins from the same subnet this minute. */
    static int difficulty(int base, long subnetJoinsThisMinute) {
        int bump = subnetJoinsThisMinute < 20 ? 0 : subnetJoinsThisMinute < 80 ? 2 : subnetJoinsThisMinute < 320 ? 4 : 6;
        return Math.min(28, base + bump);
    }

    public record JoinRequest(String challenge, String nonce, String ticket) {
    }

    public Mono<Map<String, Object>> join(String slug, String ip, String subnetHash, JoinRequest req) {
        RoomConfig r = room(slug);
        String scope = r.id() + ":" + r.epoch();
        long now = System.currentTimeMillis();
        // Same browser asking again: keep its place.
        if (req.ticket() != null) {
            Tickets.Ticket t = tickets.verify(req.ticket());
            if (t != null && t.roomId().equals(scope)) return Mono.just(joined(req.ticket(), t));
        }
        return state.snapshot(r).flatMap(s -> {
            Phase phase = Phase.of(r, Instant.ofEpochMilli(now), s.soldOut());
            if (!phase.joinable()) return reject(r, subnetHash, phase == Phase.SOLD_OUT ? "SOLD_OUT" : "NOT_OPEN", notJoinable(phase));
            ProofOfWork.Challenge parsed = ProofOfWork.parse(req.challenge());
            if (parsed == null) return reject(r, subnetHash, "POW_INVALID", new ApiError(HttpStatus.BAD_REQUEST, "pow_invalid", "Get a new challenge and try again"));
            ProofOfWork.Result result = pow.verify(req.challenge(), req.nonce(), scope, now);
            if (result != ProofOfWork.Result.OK) {
                return reject(r, subnetHash, "POW_INVALID", new ApiError(HttpStatus.BAD_REQUEST, "pow_invalid",
                        result == ProofOfWork.Result.EXPIRED ? "That check expired; get a new challenge" : "The browser check didn't pass; get a new challenge"));
            }
            return state.consumeChallenge(parsed.id(), CHALLENGE_TTL.plusMinutes(1)).flatMap(fresh -> {
                if (!fresh) return reject(r, subnetHash, "POW_REUSED", new ApiError(HttpStatus.BAD_REQUEST, "pow_reused", "That check was already used"));
                return state.join(r, ip, ClientAddress.subnet(ip), minute(now)).flatMap(j -> {
                    if (j.rateLimited()) {
                        return reject(r, subnetHash, "RATE_LIMITED", new ApiError(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                                "Too many joins from your network; wait a minute", 60));
                    }
                    String visitor = "v_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                    var ticket = new Tickets.Ticket(scope, visitor, j.lobby() ? Tickets.Kind.L : Tickets.Kind.Q, j.index(), now);
                    String token = tickets.issue(ticket);
                    joins.increment();
                    events.publish(EventPublisher.event("JOINED", r).visitor(visitor).kind(ticket.kind().name()).index(j.index())
                            .difficulty(parsed.difficulty()).subnet(subnetHash).build());
                    return Mono.just(joined(token, ticket));
                });
            });
        });
    }

    private Mono<Map<String, Object>> reject(RoomConfig r, String subnetHash, String reason, ApiError error) {
        rejections.increment();
        events.publish(EventPublisher.event("REJECTED", r).reason(reason).subnet(subnetHash).build());
        return Mono.error(error);
    }

    private static Map<String, Object> joined(String token, Tickets.Ticket t) {
        return Map.of("ticket", token, "kind", t.kind().name(), "index", t.index(), "visitorId", t.visitorId());
    }

    private static ApiError notJoinable(Phase phase) {
        return switch (phase) {
            case SCHEDULED -> new ApiError(HttpStatus.CONFLICT, "not_open", "The lobby isn't open yet");
            case SOLD_OUT -> new ApiError(HttpStatus.GONE, "sold_out", "Sold out");
            default -> new ApiError(HttpStatus.GONE, "closed", "This sale is closed");
        };
    }

    // ------------------------------------------------------------------------------------------------ status

    public Mono<Map<String, Object>> status(String slug, String ticketToken) {
        RoomConfig r = room(slug);
        polls.increment();
        Tickets.Ticket t = ticketToken == null ? null : tickets.verify(ticketToken);
        if (t == null) return Mono.error(new ApiError(HttpStatus.UNAUTHORIZED, "no_ticket", "Join the queue first"));
        if (!t.roomId().equals(r.id() + ":" + r.epoch())) {
            return Mono.error(new ApiError(HttpStatus.GONE, "old_ticket", "This ticket is for an earlier sale; join again"));
        }
        long now = System.currentTimeMillis();
        return Mono.zip(state.snapshot(r), state.claimed(r, t.visitorId()).defaultIfEmpty("")).flatMap(tuple -> {
            QueueState.Snapshot s = tuple.getT1();
            String claimed = tuple.getT2();
            Phase phase = Phase.of(r, Instant.ofEpochMilli(now), s.soldOut());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", phase.name());
            m.put("kind", t.kind().name());
            m.put("ratePerMinute", r.ratePerMinute());
            m.put("opensAt", r.opensAt());
            m.put("now", Instant.ofEpochMilli(now));
            if (t.kind() == Tickets.Kind.L) m.put("lobbyNumber", t.index() + 1);
            if (s.opened()) m.put("lobbySize", s.lobbySize());

            if (!claimed.isEmpty()) return Mono.just(withClaim(r, m, claimed, now));

            Long position = position(r, t, s);
            if (position == null) {
                m.put("pollSeconds", phase == Phase.LOBBY ? lobbyPoll(r, now) : 2);
                return Mono.just(m);
            }
            m.put("position", position + 1);
            if (phase == Phase.CLOSED || phase == Phase.SOLD_OUT) {
                m.put("pollSeconds", 60);
                return Mono.just(m);
            }
            if (position < s.served()) return claim(r, t, position, s, now, m);
            long ahead = position - s.served();
            m.put("ahead", ahead);
            m.put("etaSeconds", r.ratePerMinute() <= 0 ? null : Math.round(ahead * 60.0 / r.ratePerMinute()));
            m.put("pollSeconds", phase == Phase.PAUSED ? 15 : Admission.pollSeconds(ahead, r.ratePerMinute()));
            return Mono.just(m);
        });
    }

    /** Lobby members poll rarely until a few seconds before opening. */
    private static int lobbyPoll(RoomConfig r, long now) {
        long untilOpen = (r.opensAt().toEpochMilli() - now) / 1000;
        return (int) Math.max(2, Math.min(30, untilOpen / 4));
    }

    /** Place in line (0-based): lottery for lobby members, arrival order afterwards. Null until the lottery has run. */
    static Long position(RoomConfig r, Tickets.Ticket t, QueueState.Snapshot s) {
        if (!s.opened()) return null;
        if (t.kind() == Tickets.Kind.Q) return s.lobbySize() + t.index();
        if (t.index() >= s.lobbySize()) return null; // impossible by construction (the join and the freeze are atomic)
        return Lottery.permute(t.index(), s.lobbySize(), r.seed());
    }

    private Mono<Map<String, Object>> claim(RoomConfig r, Tickets.Ticket t, long position, QueueState.Snapshot s, long now, Map<String, Object> m) {
        String passId = "ps_" + Codec.hex(Codec.sha256(r.id() + "|" + r.epoch() + "|" + t.visitorId())).substring(0, 20);
        long ttl = r.passTtlSeconds() * 1000L;
        String pass = Passes.issue(signingKey, new Passes.Claims(r.id(), t.visitorId(), passId, now - 5_000, now + ttl));
        String value = pass + "|" + now + "|" + position;
        return state.claim(r, t.visitorId(), passId, value, now + ttl, ttl + 3_600_000, now).map(c -> {
            if (c.first()) {
                long waitedFrom = t.kind() == Tickets.Kind.L ? Math.max(t.joinedAt(), s.openedAt()) : t.joinedAt();
                events.publish(EventPublisher.event("CLAIMED", r).visitor(t.visitorId()).kind(t.kind().name()).index(t.index())
                        .position(position).waited(Math.max(0, now - waitedFrom)).pass(passId).build());
            }
            return withClaim(r, m, c.value(), now);
        });
    }

    private Map<String, Object> withClaim(RoomConfig r, Map<String, Object> m, String claimed, long now) {
        String[] parts = claimed.split("\\|");
        String pass = parts[0];
        long claimedAt = Long.parseLong(parts[1]);
        m.put("position", Long.parseLong(parts[2]) + 1);
        long expiresAt = claimedAt + r.passTtlSeconds() * 1000L;
        if (now >= expiresAt) {
            m.put("state", "EXPIRED");
            m.put("pollSeconds", 60);
            return m;
        }
        m.put("state", "ADMITTED");
        m.put("pass", pass);
        m.put("passExpiresAt", Instant.ofEpochMilli(expiresAt));
        String sep = r.originUrl().contains("?") ? "&" : "?";
        m.put("continueUrl", r.originUrl() + sep + "foyer_pass=" + pass);
        m.put("pollSeconds", 30);
        return m;
    }
}
