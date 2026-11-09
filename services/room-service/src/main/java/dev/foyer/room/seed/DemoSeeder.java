package dev.foyer.room.seed;

import dev.foyer.room.config.AppProperties;
import dev.foyer.room.config.Caller;
import dev.foyer.room.rooms.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Demo data on first start: two operators and three rooms modelled on real flash crowds (a stadium concert on-sale, a
 * Tatkal-style booking window, an admission form), with origin keys the demo shop knows.
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    public static final String ECHOES_ORIGIN_KEY = "fok_demo_echoes_shop_0b7f4c2e9a1d";
    public static final String TATKAL_ORIGIN_KEY = "fok_demo_tatkal_shop_5e2a8c1f7b3d";
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RoomService rooms;
    private final AppProperties props;

    public DemoSeeder(JdbcClient jdbc, TransactionTemplate tx, RoomService rooms, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rooms = rooms;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seedDemo()) return;
        tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(7171)").query().singleRow();
            if (jdbc.sql("SELECT count(*) FROM operators").query(Integer.class).single() > 0) return;
            seed();
            log.info("Seeded demo operators and rooms");
        });
    }

    private void seed() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        jdbc.sql("INSERT INTO operators (id, email, name, password_hash, role) VALUES (?, ?, ?, ?, ?)")
                .params("op_admin", "admin@foyer.test", "Meera Nair", bcrypt.encode("foyer-admin"), "ADMIN").update();
        jdbc.sql("INSERT INTO operators (id, email, name, password_hash, role) VALUES (?, ?, ?, ?, ?)")
                .params("op_viewer", "ops@foyer.test", "Kabir Sethi", bcrypt.encode("foyer-viewer"), "VIEWER").update();
        Caller system = new Caller("system", "ADMIN", "Foyer setup");
        Instant now = Instant.now();
        String shop = props.shopUrl().replaceAll("/+$", "");

        var echoes = rooms.createWithId(system, "rm_echoes", new RoomService.RoomRequest("echoes-mumbai", "Echoes World Tour · Mumbai",
                "Starlight Arena, Navi Mumbai · 18 & 19 January", "Tickets go on sale at the opening time. Everyone who arrives before then gets the same chance.",
                shop + "/events/echoes-mumbai", 120, 300, 600, now, now.plus(Duration.ofMinutes(10)), 16, 120));
        rooms.createOriginKeyWithSecret(system, echoes.id(), "Tixly demo shop", ECHOES_ORIGIN_KEY);

        var tatkal = rooms.createWithId(system, "rm_tatkal", new RoomService.RoomRequest("tatkal-demo", "Tatkal booking window (demo)",
                "Mumbai CSMT → New Delhi · Rajdhani Express", "A booking window that is open now: join and you'll be admitted in arrival order.",
                shop + "/events/tatkal-demo", 600, 0, 600, now.minus(Duration.ofMinutes(1)), now, 14, 240));
        rooms.createOriginKeyWithSecret(system, tatkal.id(), "Tixly demo shop", TATKAL_ORIGIN_KEY);

        ZoneId ist = ZoneId.of("Asia/Kolkata");
        Instant tomorrow10 = LocalDate.now(ist).plusDays(1).atTime(10, 0).atZone(ist).toInstant();
        rooms.createWithId(system, "rm_fyjc", new RoomService.RoomRequest("fyjc-admissions", "Class 11 admission form · Round 1",
                "State online admission portal", "The form opens at 11:00. Join the lobby from 10:00; your place is decided by lottery at 11:00.",
                shop + "/events/fyjc-admissions", 2000, 5000, 1800, tomorrow10, tomorrow10.plus(Duration.ofHours(1)), 16, 600));
    }
}
