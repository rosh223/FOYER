package dev.foyer.insight.ingest;

import dev.foyer.contracts.QueueEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Stores events idempotently (primary key = event id) and only updates statistics for events that were new, so a
 * redelivered batch never double-counts.
 */
@Component
public class EventStore {

    private final JdbcClient jdbc;

    public EventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int store(List<QueueEvent> events) {
        int fresh = 0;
        for (QueueEvent e : events) {
            if (insert(e)) {
                fresh++;
                apply(e);
            }
        }
        return fresh;
    }

    private boolean insert(QueueEvent e) {
        return jdbc.sql("""
                        INSERT INTO events (id, room_id, epoch, type, visitor_id, kind, idx, position, waited_ms, reason, range_from, range_to,
                          difficulty, subnet, pass_id, detail, at)
                        VALUES (CAST(:id AS uuid), :room, :epoch, :type, :visitor, :kind, :idx, :position, :waited, :reason, :from, :to,
                          :difficulty, :subnet, :pass, :detail, :at)
                        ON CONFLICT (id) DO NOTHING""")
                .param("id", e.id()).param("room", e.roomId()).param("epoch", e.epoch() == null ? 0 : e.epoch()).param("type", e.type())
                .param("visitor", e.visitorId()).param("kind", e.kind()).param("idx", e.index()).param("position", e.position())
                .param("waited", e.waitedMs()).param("reason", e.reason()).param("from", e.from()).param("to", e.to())
                .param("difficulty", e.difficulty()).param("subnet", e.subnet()).param("pass", e.passId()).param("detail", e.detail())
                .param("at", Timestamp.from(e.at())).update() == 1;
    }

    private void apply(QueueEvent e) {
        int epoch = e.epoch() == null ? 0 : e.epoch();
        Timestamp at = Timestamp.from(e.at());
        Timestamp minute = Timestamp.from(e.at().truncatedTo(ChronoUnit.MINUTES));
        switch (e.type()) {
            case "JOINED" -> {
                bump(e.roomId(), epoch, minute, "joins", 1);
                if ("L".equals(e.kind())) {
                    bump(e.roomId(), epoch, minute, "lobby_joins", 1);
                    jdbc.sql("""
                                    INSERT INTO lobby_entries (room_id, epoch, visitor_id, idx, joined_at) VALUES (?, ?, ?, ?, ?)
                                    ON CONFLICT (room_id, epoch, visitor_id) DO UPDATE SET idx = excluded.idx, joined_at = excluded.joined_at""")
                            .params(e.roomId(), epoch, e.visitorId(), e.index(), at).update();
                }
                sale(e.roomId(), epoch);
                jdbc.sql("UPDATE sales SET first_join = LEAST(coalesce(first_join, ?), ?) WHERE room_id = ? AND epoch = ?")
                        .params(at, at, e.roomId(), epoch).update();
            }
            case "REJECTED" -> bump(e.roomId(), epoch, minute, switch (String.valueOf(e.reason())) {
                case "RATE_LIMITED" -> "rejected_rate";
                case "POW_INVALID", "POW_REUSED" -> "rejected_pow";
                default -> "rejected_other";
            }, 1);
            case "ADMITTED" -> bump(e.roomId(), epoch, minute, "admitted", e.to() - e.from());
            case "CLAIMED" -> {
                bump(e.roomId(), epoch, minute, "claimed", 1);
                bump(e.roomId(), epoch, minute, "wait_sum_ms", e.waitedMs() == null ? 0 : e.waitedMs());
                if ("L".equals(e.kind())) {
                    jdbc.sql("""
                                    INSERT INTO lobby_entries (room_id, epoch, visitor_id, idx, position, claimed_at, waited_ms) VALUES (?, ?, ?, ?, ?, ?, ?)
                                    ON CONFLICT (room_id, epoch, visitor_id) DO UPDATE SET position = excluded.position,
                                      claimed_at = excluded.claimed_at, waited_ms = excluded.waited_ms, idx = coalesce(lobby_entries.idx, excluded.idx)""")
                            .params(e.roomId(), epoch, e.visitorId(), e.index(), e.position(), at, e.waitedMs()).update();
                }
            }
            case "COMPLETED" -> {
                bump(e.roomId(), epoch, minute, "completed", 1);
                bump(e.roomId(), epoch, minute, "purchases", e.index() == null ? 1 : e.index());
            }
            case "OPENED" -> {
                sale(e.roomId(), epoch);
                jdbc.sql("UPDATE sales SET opened_at = ?, lobby_size = ?, seed = ? WHERE room_id = ? AND epoch = ?")
                        .params(at, e.from(), e.detail(), e.roomId(), epoch).update();
            }
            case "SOLD_OUT" -> {
                sale(e.roomId(), epoch);
                jdbc.sql("UPDATE sales SET sold_out_at = coalesce(sold_out_at, ?) WHERE room_id = ? AND epoch = ?").params(at, e.roomId(), epoch).update();
            }
            default -> { }
        }
    }

    private void sale(String room, int epoch) {
        jdbc.sql("INSERT INTO sales (room_id, epoch) VALUES (?, ?) ON CONFLICT DO NOTHING").params(room, epoch).update();
    }

    private void bump(String room, int epoch, Timestamp minute, String column, long by) {
        // Column names come from the fixed set above, never from input.
        jdbc.sql("INSERT INTO room_minute AS m (room_id, epoch, minute, " + column + ") VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (room_id, epoch, minute) DO UPDATE SET " + column + " = m." + column + " + excluded." + column)
                .params(room, epoch, minute, by).update();
    }
}
