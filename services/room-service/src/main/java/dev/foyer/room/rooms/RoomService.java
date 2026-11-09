package dev.foyer.room.rooms;

import dev.foyer.core.Codec;
import dev.foyer.core.Lottery;
import dev.foyer.room.config.Caller;
import dev.foyer.room.publish.AdminAudit;
import dev.foyer.room.publish.ConfigPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Rooms and their lifecycle. The schedule is the source of truth (queue-service follows the clock); operators can open
 * early, pause, close, change the admission rate live, or reset a room into a new epoch with a fresh lottery seed.
 * Once a room has opened, its schedule and seed are frozen for that epoch, so the lottery can't be re-rolled quietly.
 */
@Service
public class RoomService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final ConfigPublisher publisher;
    private final AdminAudit audit;

    public RoomService(JdbcClient jdbc, ConfigPublisher publisher, AdminAudit audit) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.audit = audit;
    }

    public record RoomView(String id, String slug, String name, String venue, String description, String originUrl, int ratePerMinute,
                           int maxActive, int passTtlSeconds, Instant lobbyOpensAt, Instant opensAt, String desiredState,
                           int powDifficulty, int joinsPerIpPerMinute, String seedCommitment, String seed, int epoch, long version,
                           String phase, String createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record RoomRequest(String slug, String name, String venue, String description, String originUrl, Integer ratePerMinute,
                              Integer maxActive, Integer passTtlSeconds, Instant lobbyOpensAt, Instant opensAt, Integer powDifficulty,
                              Integer joinsPerIpPerMinute) {
    }

    public record OriginKey(String id, String label, String last4, Instant createdAt, Instant revokedAt) {
    }

    public List<RoomView> list() {
        return jdbc.sql(SELECT + " ORDER BY opens_at DESC").query((rs, n) -> view(rs)).list();
    }

    public RoomView get(String id) {
        return jdbc.sql(SELECT + " WHERE id = ? OR slug = ?").params(id, id).query((rs, n) -> view(rs)).optional()
                .orElseThrow(() -> new NoSuchElementException("No room " + id));
    }

    @Transactional
    public RoomView create(Caller c, RoomRequest r) {
        return createWithId(c, "rm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), r);
    }

    /** With a chosen id: the demo seeder uses stable ids that the demo shop is configured with. */
    @Transactional
    public RoomView createWithId(Caller c, String id, RoomRequest r) {
        validate(r, true);
        String seed = Lottery.newSeed();
        try {
            jdbc.sql("""
                            INSERT INTO rooms (id, slug, name, venue, description, origin_url, rate_per_minute, max_active, pass_ttl_seconds,
                              lobby_opens_at, opens_at, pow_difficulty, joins_per_ip_per_minute, seed, seed_commitment, created_by)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, r.slug().trim(), r.name().trim(), nz(r.venue()), nz(r.description()), r.originUrl().trim(), r.ratePerMinute(),
                            or(r.maxActive(), 0), or(r.passTtlSeconds(), 600), ts(r.lobbyOpensAt()), ts(r.opensAt()), or(r.powDifficulty(), 16),
                            or(r.joinsPerIpPerMinute(), 120), seed, Lottery.commitment(seed), c.display())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("The address /q/" + r.slug() + " is already taken");
        }
        recordEpoch(id);
        audit.record(c, "room.create", id, r.name());
        publisher.publish(id);
        return get(id);
    }

    @Transactional
    public RoomView update(Caller c, String id, RoomRequest r) {
        RoomView current = get(id);
        validate(r, false);
        boolean opened = !current.opensAt().isAfter(Instant.now());
        boolean scheduleChanged = r.opensAt() != null && !r.opensAt().equals(current.opensAt())
                || r.lobbyOpensAt() != null && !r.lobbyOpensAt().equals(current.lobbyOpensAt());
        if (opened && scheduleChanged) {
            throw new IllegalArgumentException("This room has already opened, so its schedule is frozen. Reset it to start a new sale.");
        }
        Instant lobby = r.lobbyOpensAt() != null ? r.lobbyOpensAt() : current.lobbyOpensAt();
        Instant opens = r.opensAt() != null ? r.opensAt() : current.opensAt();
        if (lobby.isAfter(opens)) throw new IllegalArgumentException("The lobby must open before (or when) the room opens");
        jdbc.sql("""
                        UPDATE rooms SET name = ?, venue = ?, description = ?, origin_url = ?, rate_per_minute = ?, max_active = ?,
                          pass_ttl_seconds = ?, lobby_opens_at = ?, opens_at = ?, pow_difficulty = ?, joins_per_ip_per_minute = ?
                        WHERE id = ?""")
                .params(r.name() != null ? r.name().trim() : current.name(), r.venue() != null ? r.venue() : current.venue(),
                        r.description() != null ? r.description() : current.description(),
                        r.originUrl() != null ? r.originUrl().trim() : current.originUrl(), or(r.ratePerMinute(), current.ratePerMinute()),
                        or(r.maxActive(), current.maxActive()), or(r.passTtlSeconds(), current.passTtlSeconds()), ts(lobby), ts(opens),
                        or(r.powDifficulty(), current.powDifficulty()), or(r.joinsPerIpPerMinute(), current.joinsPerIpPerMinute()), current.id())
                .update();
        if (scheduleChanged) jdbc.sql("UPDATE room_epochs SET opens_at = ? WHERE room_id = ? AND epoch = ?").params(ts(opens), current.id(), current.epoch()).update();
        audit.record(c, "room.update", current.id(), "rate " + or(r.ratePerMinute(), current.ratePerMinute()) + "/min, cap " + or(r.maxActive(), current.maxActive()));
        publisher.publish(current.id());
        return get(current.id());
    }

    @Transactional
    public RoomView setRate(Caller c, String id, int ratePerMinute, int maxActive) {
        if (ratePerMinute < 1 || ratePerMinute > 1_000_000) throw new IllegalArgumentException("Rate must be 1 to 1,000,000 per minute");
        if (maxActive < 0) throw new IllegalArgumentException("Session cap must be 0 (none) or more");
        RoomView r = get(id);
        jdbc.sql("UPDATE rooms SET rate_per_minute = ?, max_active = ? WHERE id = ?").params(ratePerMinute, maxActive, r.id()).update();
        audit.record(c, "room.rate", r.id(), ratePerMinute + "/min, cap " + maxActive);
        publisher.publish(r.id());
        return get(r.id());
    }

    @Transactional
    public RoomView openNow(Caller c, String id) {
        RoomView r = get(id);
        Instant now = Instant.now();
        if (!r.opensAt().isAfter(now)) throw new IllegalArgumentException("The room is already open");
        jdbc.sql("UPDATE rooms SET opens_at = ?, lobby_opens_at = LEAST(lobby_opens_at, ?), desired_state = 'SCHEDULED' WHERE id = ?")
                .params(ts(now), ts(now), r.id()).update();
        jdbc.sql("UPDATE room_epochs SET opens_at = ? WHERE room_id = ? AND epoch = ?").params(ts(now), r.id(), r.epoch()).update();
        audit.record(c, "room.open-now", r.id(), null);
        publisher.publish(r.id());
        return get(r.id());
    }

    @Transactional
    public RoomView setState(Caller c, String id, String state) {
        if (!List.of("SCHEDULED", "PAUSED", "CLOSED").contains(state)) throw new IllegalArgumentException("Unknown state " + state);
        RoomView r = get(id);
        jdbc.sql("UPDATE rooms SET desired_state = ? WHERE id = ?").params(state, r.id()).update();
        audit.record(c, "room.state", r.id(), state);
        publisher.publish(r.id());
        return get(r.id());
    }

    /** Starts a fresh sale: new epoch (clean queue), new seed and commitment, new schedule. */
    @Transactional
    public RoomView reset(Caller c, String id, int lobbyInMinutes, int opensInMinutes) {
        if (lobbyInMinutes < 0 || opensInMinutes < lobbyInMinutes) throw new IllegalArgumentException("Opening must be after the lobby");
        RoomView r = get(id);
        Instant now = Instant.now();
        String seed = Lottery.newSeed();
        jdbc.sql("""
                        UPDATE rooms SET epoch = epoch + 1, seed = ?, seed_commitment = ?, lobby_opens_at = ?, opens_at = ?,
                          desired_state = 'SCHEDULED' WHERE id = ?""")
                .params(seed, Lottery.commitment(seed), ts(now.plus(Duration.ofMinutes(lobbyInMinutes))),
                        ts(now.plus(Duration.ofMinutes(opensInMinutes))), r.id()).update();
        recordEpoch(r.id());
        audit.record(c, "room.reset", r.id(), "epoch " + (r.epoch() + 1));
        publisher.publish(r.id());
        return get(r.id());
    }

    public record CreatedKey(OriginKey key, String secret) {
    }

    @Transactional
    public CreatedKey createOriginKey(Caller c, String roomId, String label) {
        RoomView r = get(roomId);
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String secret = "fok_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        return createOriginKeyWithSecret(c, r.id(), label, secret);
    }

    @Transactional
    public CreatedKey createOriginKeyWithSecret(Caller c, String roomId, String label, String secret) {
        String id = "ok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.sql("INSERT INTO origin_keys (id, room_id, key_hash, last4, label) VALUES (?, ?, ?, ?, ?)")
                .params(id, roomId, Codec.hex(Codec.sha256(secret)), secret.substring(secret.length() - 4), label == null || label.isBlank() ? "Origin" : label.trim())
                .update();
        audit.record(c, "origin-key.create", roomId, label);
        publisher.publish(roomId);
        return new CreatedKey(originKeys(roomId).stream().filter(k -> k.id().equals(id)).findFirst().orElseThrow(), secret);
    }

    @Transactional
    public void revokeOriginKey(Caller c, String roomId, String keyId) {
        RoomView r = get(roomId);
        if (jdbc.sql("UPDATE origin_keys SET revoked_at = now() WHERE id = ? AND room_id = ? AND revoked_at IS NULL").params(keyId, r.id()).update() == 0) {
            throw new NoSuchElementException("No active key " + keyId);
        }
        audit.record(c, "origin-key.revoke", r.id(), keyId);
        publisher.publish(r.id());
    }

    public List<OriginKey> originKeys(String roomId) {
        return jdbc.sql("SELECT id, label, last4, created_at, revoked_at FROM origin_keys WHERE room_id = ? ORDER BY created_at")
                .param(get(roomId).id()).query(OriginKey.class).list();
    }

    public record Epoch(int epoch, String seedCommitment, String seed, Instant opensAt) {
    }

    /** Past and current lotteries; seeds are shown only for epochs that have opened. */
    public List<Epoch> epochs(String roomId) {
        return jdbc.sql("""
                        SELECT epoch, seed_commitment, CASE WHEN opens_at <= now() THEN seed END AS seed, opens_at
                        FROM room_epochs WHERE room_id = ? ORDER BY epoch DESC""")
                .param(get(roomId).id()).query(Epoch.class).list();
    }

    private void recordEpoch(String roomId) {
        jdbc.sql("""
                        INSERT INTO room_epochs (room_id, epoch, seed, seed_commitment, opens_at)
                        SELECT id, epoch, seed, seed_commitment, opens_at FROM rooms WHERE id = ?""").param(roomId).update();
    }

    static void validate(RoomRequest r, boolean creating) {
        if (creating) {
            if (r.slug() == null || !r.slug().trim().matches("[a-z0-9][a-z0-9-]{2,59}")) {
                throw new IllegalArgumentException("Address: 3-60 lowercase letters, digits and dashes");
            }
            if (r.name() == null || r.name().isBlank()) throw new IllegalArgumentException("Name is required");
            if (r.originUrl() == null) throw new IllegalArgumentException("Origin URL is required");
            if (r.ratePerMinute() == null) throw new IllegalArgumentException("Rate is required");
            if (r.lobbyOpensAt() == null || r.opensAt() == null) throw new IllegalArgumentException("Lobby and opening times are required");
            if (r.lobbyOpensAt().isAfter(r.opensAt())) throw new IllegalArgumentException("The lobby must open before (or when) the room opens");
        }
        if (r.originUrl() != null) {
            URI u = URI.create(r.originUrl().trim());
            if (u.getScheme() == null || !u.getScheme().startsWith("http") || u.getHost() == null) {
                throw new IllegalArgumentException("Origin URL must be an http(s) URL");
            }
        }
        if (r.ratePerMinute() != null && (r.ratePerMinute() < 1 || r.ratePerMinute() > 1_000_000)) throw new IllegalArgumentException("Rate must be 1 to 1,000,000 per minute");
        if (r.maxActive() != null && r.maxActive() < 0) throw new IllegalArgumentException("Session cap must be 0 (none) or more");
        if (r.passTtlSeconds() != null && (r.passTtlSeconds() < 30 || r.passTtlSeconds() > 86_400)) throw new IllegalArgumentException("Pass lifetime: 30 s to 24 h");
        if (r.powDifficulty() != null && (r.powDifficulty() < 0 || r.powDifficulty() > 28)) throw new IllegalArgumentException("Difficulty: 0 to 28 bits");
        if (r.joinsPerIpPerMinute() != null && r.joinsPerIpPerMinute() < 1) throw new IllegalArgumentException("Joins per IP must be at least 1");
    }

    private static final String SELECT = """
            SELECT id, slug, name, venue, description, origin_url, rate_per_minute, max_active, pass_ttl_seconds, lobby_opens_at, opens_at,
                   desired_state, pow_difficulty, joins_per_ip_per_minute, seed_commitment, seed, epoch, version, created_by, created_at, updated_at
            FROM rooms""";

    private static RoomView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant now = Instant.now();
        Instant lobby = rs.getTimestamp("lobby_opens_at").toInstant();
        Instant opens = rs.getTimestamp("opens_at").toInstant();
        String desired = rs.getString("desired_state");
        String phase = "CLOSED".equals(desired) ? "CLOSED" : now.isBefore(lobby) ? "SCHEDULED" : now.isBefore(opens) ? "LOBBY"
                : "PAUSED".equals(desired) ? "PAUSED" : "OPEN";
        return new RoomView(rs.getString("id"), rs.getString("slug"), rs.getString("name"), rs.getString("venue"), rs.getString("description"),
                rs.getString("origin_url"), rs.getInt("rate_per_minute"), rs.getInt("max_active"), rs.getInt("pass_ttl_seconds"), lobby, opens,
                desired, rs.getInt("pow_difficulty"), rs.getInt("joins_per_ip_per_minute"), rs.getString("seed_commitment"),
                opens.isAfter(now) ? null : rs.getString("seed"), rs.getInt("epoch"), rs.getLong("version"), phase, rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static int or(Integer v, int d) {
        return v == null ? d : v;
    }
}
