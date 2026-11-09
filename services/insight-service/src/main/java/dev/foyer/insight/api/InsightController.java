package dev.foyer.insight.api;

import dev.foyer.core.Lottery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What happened in a sale: per-minute flow, wait times, rejections and the fairness report. */
@RestController
@RequestMapping("/api/console/insight/rooms/{roomId}")
public class InsightController {

    private final JdbcClient jdbc;

    public InsightController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static void signedIn(HttpServletRequest req) {
        if (req.getHeader("X-User-Id") == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in");
    }

    private int epoch(String roomId, Integer epoch) {
        if (epoch != null) return epoch;
        return jdbc.sql("SELECT coalesce(max(epoch), 1) FROM room_minute WHERE room_id = ?").param(roomId).query(Integer.class).single();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@PathVariable String roomId, @RequestParam(required = false) Integer epoch,
                                        @RequestParam(defaultValue = "60") int minutes, HttpServletRequest req) {
        signedIn(req);
        int ep = epoch(roomId, epoch);
        int span = Math.max(5, Math.min(24 * 60, minutes));
        Instant from = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(span - 1, ChronoUnit.MINUTES);
        Map<Instant, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT minute, joins, lobby_joins, rejected_rate, rejected_pow, rejected_other, admitted, claimed, completed, purchases, wait_sum_ms
                        FROM room_minute WHERE room_id = ? AND epoch = ? AND minute >= ? ORDER BY minute""")
                .params(roomId, ep, Timestamp.from(from)).query((rs, n) -> rows.put(rs.getTimestamp(1).toInstant(), Map.of(
                        "joins", rs.getLong(2), "lobbyJoins", rs.getLong(3), "rejected", rs.getLong(4) + rs.getLong(5) + rs.getLong(6),
                        "admitted", rs.getLong(7), "claimed", rs.getLong(8), "completed", rs.getLong(9)))).list();
        List<Map<String, Object>> series = new ArrayList<>();
        for (Instant t = from; !t.isAfter(Instant.now()); t = t.plus(1, ChronoUnit.MINUTES)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", t);
            Map<String, Object> v = rows.get(t);
            for (String k : List.of("joins", "lobbyJoins", "rejected", "admitted", "claimed", "completed")) p.put(k, v == null ? 0L : v.get(k));
            series.add(p);
        }
        Map<String, Object> totals = jdbc.sql("""
                        SELECT coalesce(sum(joins), 0) AS joins, coalesce(sum(lobby_joins), 0) AS "lobbyJoins",
                               coalesce(sum(rejected_rate), 0) AS "rejectedRate", coalesce(sum(rejected_pow), 0) AS "rejectedPow",
                               coalesce(sum(rejected_other), 0) AS "rejectedOther", coalesce(sum(admitted), 0) AS admitted,
                               coalesce(sum(claimed), 0) AS claimed, coalesce(sum(completed), 0) AS completed, coalesce(sum(purchases), 0) AS purchases
                        FROM room_minute WHERE room_id = ? AND epoch = ?""").params(roomId, ep).query().singleRow();
        Map<String, Object> waits = jdbc.sql("""
                        SELECT count(*) AS n,
                               coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY waited_ms), 0) AS p50,
                               coalesce(percentile_cont(0.9) WITHIN GROUP (ORDER BY waited_ms), 0) AS p90,
                               coalesce(max(waited_ms), 0) AS max
                        FROM events WHERE room_id = ? AND epoch = ? AND type = 'CLAIMED'""").params(roomId, ep).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("epoch", ep);
        out.put("series", series);
        out.put("totals", totals);
        out.put("waits", waits);
        out.put("sale", jdbc.sql("SELECT first_join AS \"firstJoin\", opened_at AS \"openedAt\", lobby_size AS \"lobbySize\", sold_out_at AS \"soldOutAt\" FROM sales WHERE room_id = ? AND epoch = ?")
                .params(roomId, ep).query().listOfRows().stream().findFirst().orElse(Map.of()));
        out.put("networks", jdbc.sql("""
                        SELECT subnet, count(*) FILTER (WHERE type = 'JOINED') AS joins, count(*) FILTER (WHERE type = 'REJECTED') AS rejected
                        FROM events WHERE room_id = ? AND epoch = ? AND subnet IS NOT NULL
                        GROUP BY subnet ORDER BY count(*) FILTER (WHERE type = 'REJECTED') DESC, count(*) DESC LIMIT 8""")
                .params(roomId, ep).query().listOfRows());
        return out;
    }

    /**
     * Evidence that the lobby lottery was fair: the revealed seed matches the published commitment (checked by the caller
     * with the room's commitment), and arriving earlier in the lobby gave no better place (rank correlation near zero; the
     * average place is the same for every tenth of the lobby).
     */
    @GetMapping("/fairness")
    public Map<String, Object> fairness(@PathVariable String roomId, @RequestParam(required = false) Integer epoch,
                                        @RequestParam(required = false) String commitment, HttpServletRequest req) {
        signedIn(req);
        int ep = epoch(roomId, epoch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("epoch", ep);
        var sale = jdbc.sql("SELECT lobby_size, seed, opened_at FROM sales WHERE room_id = ? AND epoch = ?").params(roomId, ep)
                .query((rs, n) -> new Object[]{rs.getObject(1), rs.getString(2), rs.getTimestamp(3)}).optional();
        Long lobbySize = sale.map(s -> s[0] == null ? null : ((Number) s[0]).longValue()).orElse(null);
        String seed = sale.map(s -> (String) s[1]).orElse(null);
        out.put("lobbySize", lobbySize);
        out.put("seed", seed);
        out.put("openedAt", sale.map(s -> s[2]).orElse(null));
        if (seed != null && commitment != null && commitment.matches("[0-9a-f]{64}")) {
            out.put("commitmentMatches", Lottery.verifyCommitment(seed, commitment));
        }
        Map<String, Object> rho = jdbc.sql("""
                        WITH ranked AS (
                          SELECT rank() OVER (ORDER BY idx) AS r1, rank() OVER (ORDER BY position) AS r2
                          FROM lobby_entries WHERE room_id = ? AND epoch = ? AND position IS NOT NULL AND idx IS NOT NULL)
                        SELECT count(*) AS n, coalesce(sum((r1 - r2) * (r1 - r2)), 0) AS d2 FROM ranked""")
                .params(roomId, ep).query().singleRow();
        long n = ((Number) rho.get("n")).longValue();
        double d2 = ((Number) rho.get("d2")).doubleValue();
        out.put("claimedLobbyMembers", n);
        if (n >= 3) {
            double spearman = 1 - 6 * d2 / ((double) n * ((double) n * n - 1));
            double threshold = 2.58 / Math.sqrt(n - 1);
            out.put("spearman", Math.round(spearman * 10_000) / 10_000.0);
            out.put("significanceThreshold", Math.round(threshold * 10_000) / 10_000.0);
            out.put("noEarlyBirdAdvantage", Math.abs(spearman) < threshold);
        }
        if (lobbySize != null && lobbySize > 0) {
            out.put("deciles", jdbc.sql("""
                            SELECT decile, count(*) AS members, round(avg(position)::numeric / ?, 3) AS "averagePlace"
                            FROM (SELECT ntile(10) OVER (ORDER BY idx) AS decile, position FROM lobby_entries
                                  WHERE room_id = ? AND epoch = ? AND position IS NOT NULL AND idx IS NOT NULL) t
                            GROUP BY decile ORDER BY decile""").params(Math.max(1, lobbySize - 1), roomId, ep).query().listOfRows());
        }
        out.put("rejections", jdbc.sql("""
                        SELECT reason, count(*) AS count FROM events WHERE room_id = ? AND epoch = ? AND type = 'REJECTED'
                        GROUP BY reason ORDER BY count DESC""").params(roomId, ep).query().listOfRows());
        return out;
    }

    @GetMapping("/epochs")
    public List<Map<String, Object>> epochs(@PathVariable String roomId, HttpServletRequest req) {
        signedIn(req);
        return jdbc.sql("SELECT DISTINCT epoch FROM room_minute WHERE room_id = ? ORDER BY epoch DESC").param(roomId).query().listOfRows();
    }
}
