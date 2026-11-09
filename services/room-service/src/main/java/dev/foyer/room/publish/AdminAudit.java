package dev.foyer.room.publish;

import dev.foyer.room.config.Caller;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Who changed what: every operator action on a room, for the room's history and for disputes after a sale. */
@Component
public class AdminAudit {

    private final JdbcClient jdbc;

    public AdminAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Caller c, String action, String target, String detail) {
        jdbc.sql("INSERT INTO admin_audit (actor, action, target, detail) VALUES (?, ?, ?, ?)")
                .params(c.display(), action, target, detail).update();
    }

    public List<Entry> forTarget(String target, int limit) {
        return jdbc.sql("SELECT id, actor, action, target, detail, at FROM admin_audit WHERE target = ? ORDER BY id DESC LIMIT ?")
                .params(target, limit).query(Entry.class).list();
    }

    public record Entry(long id, String actor, String action, String target, String detail, Instant at) {
    }
}
