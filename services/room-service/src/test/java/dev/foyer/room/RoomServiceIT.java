package dev.foyer.room;

import dev.foyer.contracts.RoomConfig;
import dev.foyer.contracts.Topics;
import dev.foyer.core.Codec;
import dev.foyer.core.Lottery;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Rooms, schedules, the lottery seed lifecycle and config push, against real Postgres and Kafka. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "foyer.queue-url=http://localhost:1")
class RoomServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    RestClient admin;
    RestClient viewer;
    RestClient anon;

    @BeforeEach
    void clients() {
        anon = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(HttpStatusCode::isError, (q, r) -> { }).build();
        admin = anon.mutate().defaultHeader("X-User-Id", "op_admin").defaultHeader("X-User-Role", "ADMIN").defaultHeader("X-User-Name", "Meera Nair").build();
        viewer = anon.mutate().defaultHeader("X-User-Id", "op_viewer").defaultHeader("X-User-Role", "VIEWER").build();
    }

    Map<String, Object> newRoom(String slug, Instant lobby, Instant opens) {
        return Map.of("slug", slug, "name", "Sale " + slug, "originUrl", "https://shop.test/" + slug, "ratePerMinute", 100,
                "maxActive", 50, "lobbyOpensAt", lobby.toString(), "opensAt", opens.toString());
    }

    @Test
    void demoSeedAndLogin() {
        assertThat(jdbc.sql("SELECT count(*) FROM rooms").query(Integer.class).single()).isGreaterThanOrEqualTo(3);
        var ok = anon.post().uri("/api/console/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "admin@foyer.test", "password", "foyer-admin")).retrieve().toEntity(Map.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).containsKey("accessToken");
        for (int i = 0; i < 5; i++) {
            anon.post().uri("/api/console/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", "ops@foyer.test", "password", "nope")).retrieve().toBodilessEntity();
        }
        assertThat(anon.post().uri("/api/console/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "ops@foyer.test", "password", "foyer-viewer")).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void creatingARoomPushesASnapshotWithACommitmentButTheSeedStaysPrivate() throws Exception {
        try (var consumer = consumer()) {
            String slug = "sale-" + UUID.randomUUID().toString().substring(0, 6);
            var created = admin.post().uri("/api/console/rooms").contentType(MediaType.APPLICATION_JSON)
                    .body(newRoom(slug, Instant.now(), Instant.now().plusSeconds(3600))).retrieve().toEntity(Map.class);
            assertThat(created.getStatusCode().value()).isEqualTo(201);
            assertThat(created.getBody().get("seed")).as("seed hidden before opening").isNull();
            String id = (String) created.getBody().get("id");
            RoomConfig snap = poll(consumer, id);
            assertThat(Lottery.verifyCommitment(snap.seed(), snap.seedCommitment())).isTrue();
            assertThat(snap.seedCommitment()).isEqualTo(created.getBody().get("seedCommitment"));
            assertThat(anon.get().uri("/api/public/rooms/{s}/epochs", slug).retrieve().body(String.class)).doesNotContain(snap.seed());

            // Origin keys are stored as hashes and travel as hashes.
            var key = admin.post().uri("/api/console/rooms/{id}/origin-keys", id).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("label", "Shop")).retrieve().body(Map.class);
            String secret = (String) key.get("secret");
            assertThat(jdbc.sql("SELECT count(*) FROM origin_keys WHERE key_hash = ?").param(Codec.hex(Codec.sha256(secret))).query(Integer.class).single()).isEqualTo(1);
            assertThat(poll(consumer, id).originKeyHashes()).contains(Codec.hex(Codec.sha256(secret)));
        }
    }

    @Test
    void scheduleIsFrozenOnceOpenAndResetStartsANewEpoch() {
        String slug = "frozen-" + UUID.randomUUID().toString().substring(0, 6);
        var r = admin.post().uri("/api/console/rooms").contentType(MediaType.APPLICATION_JSON)
                .body(newRoom(slug, Instant.now(), Instant.now().plusSeconds(3600))).retrieve().body(Map.class);
        String id = (String) r.get("id");
        var opened = admin.post().uri("/api/console/rooms/{id}/open-now", id).retrieve().body(Map.class);
        assertThat(opened.get("phase")).isEqualTo("OPEN");
        assertThat(opened.get("seed")).as("revealed once open").isNotNull();
        var change = admin.put().uri("/api/console/rooms/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("opensAt", Instant.now().plusSeconds(600).toString())).retrieve().toEntity(Map.class);
        assertThat(change.getStatusCode().value()).isEqualTo(400);
        // Live rate changes are always allowed.
        assertThat(admin.put().uri("/api/console/rooms/{id}/rate", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ratePerMinute", 500, "maxActive", 10)).retrieve().body(Map.class).get("ratePerMinute")).isEqualTo(500);
        var reset = admin.post().uri("/api/console/rooms/{id}/reset", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lobbyInMinutes", 0, "opensInMinutes", 5)).retrieve().body(Map.class);
        assertThat(reset.get("epoch")).isEqualTo(2);
        assertThat(reset.get("seedCommitment")).isNotEqualTo(opened.get("seedCommitment"));
        List<?> epochs = anon.get().uri("/api/public/rooms/{s}/epochs", slug).retrieve().body(List.class);
        assertThat(epochs).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) epochs.get(1);
        assertThat(Lottery.verifyCommitment((String) first.get("seed"), (String) first.get("seedCommitment"))).as("past lottery stays verifiable").isTrue();
        assertThat(((Map<?, ?>) epochs.get(0)).get("seed")).isNull();
    }

    @Test
    void validationAndRoles() {
        var bad = admin.post().uri("/api/console/rooms").contentType(MediaType.APPLICATION_JSON)
                .body(newRoom("x", Instant.now().plusSeconds(10), Instant.now())).retrieve().toEntity(Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(viewer.post().uri("/api/console/rooms").contentType(MediaType.APPLICATION_JSON)
                .body(newRoom("viewer-room", Instant.now(), Instant.now())).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(403);
        assertThat(anon.get().uri("/api/console/rooms").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
        assertThat(viewer.get().uri("/api/console/rooms").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        var dup = admin.post().uri("/api/console/rooms").contentType(MediaType.APPLICATION_JSON)
                .body(newRoom("echoes-mumbai", Instant.now(), Instant.now())).retrieve().toEntity(Map.class);
        assertThat(dup.getStatusCode().value()).isEqualTo(400);
    }

    private KafkaConsumer<String, String> consumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
        c.subscribe(List.of(Topics.ROOMS));
        return c;
    }

    private RoomConfig poll(KafkaConsumer<String, String> c, String roomId) {
        long deadline = System.currentTimeMillis() + 20_000;
        RoomConfig last = null;
        while (System.currentTimeMillis() < deadline) {
            for (var r : c.poll(Duration.ofMillis(300))) {
                RoomConfig cfg = json.readValue(r.value(), RoomConfig.class);
                if (cfg.id().equals(roomId)) last = cfg;
            }
            if (last != null) return last;
        }
        throw new AssertionError("No snapshot for " + roomId);
    }
}
