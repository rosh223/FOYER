package dev.foyer.room.publish;

import dev.foyer.contracts.RoomConfig;
import dev.foyer.contracts.Topics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Builds a room's snapshot and queues it for Kafka, inside the transaction that changed the room. */
@Component
public class ConfigPublisher {

    private final JdbcClient jdbc;
    private final Outbox outbox;

    public ConfigPublisher(JdbcClient jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RoomConfig publish(String roomId) {
        jdbc.sql("UPDATE rooms SET version = version + 1, updated_at = now() WHERE id = ?").param(roomId).update();
        RoomConfig config = load(roomId).orElseThrow();
        outbox.add(Topics.ROOMS, roomId, config);
        return config;
    }

    public Optional<RoomConfig> load(String roomId) {
        List<String> keys = jdbc.sql("SELECT key_hash FROM origin_keys WHERE room_id = ? AND revoked_at IS NULL ORDER BY id")
                .param(roomId).query(String.class).list();
        return jdbc.sql("""
                        SELECT id, slug, name, venue, description, origin_url, rate_per_minute, max_active, pass_ttl_seconds,
                               lobby_opens_at, opens_at, desired_state, pow_difficulty, joins_per_ip_per_minute, seed, seed_commitment,
                               epoch, version, updated_at
                        FROM rooms WHERE id = ?""")
                .param(roomId).query((rs, n) -> new RoomConfig(rs.getString("id"), rs.getString("slug"), rs.getString("name"),
                        rs.getString("venue"), rs.getString("description"), rs.getString("origin_url"), rs.getInt("rate_per_minute"),
                        rs.getInt("max_active"), rs.getInt("pass_ttl_seconds"), rs.getTimestamp("lobby_opens_at").toInstant(),
                        rs.getTimestamp("opens_at").toInstant(), rs.getString("desired_state"), rs.getInt("pow_difficulty"),
                        rs.getInt("joins_per_ip_per_minute"), rs.getString("seed"), rs.getString("seed_commitment"), keys,
                        rs.getInt("epoch"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public List<String> roomIds() {
        return jdbc.sql("SELECT id FROM rooms ORDER BY created_at").query(String.class).list();
    }
}
