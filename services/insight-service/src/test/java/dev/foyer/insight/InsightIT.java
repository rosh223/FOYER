package dev.foyer.insight;

import dev.foyer.contracts.QueueEvent;
import dev.foyer.contracts.Topics;
import dev.foyer.core.Lottery;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Event ingestion and the fairness report against real Kafka and Postgres. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsightIT {

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

    void send(List<QueueEvent> events) {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            for (QueueEvent e : events) producer.send(new ProducerRecord<>(Topics.EVENTS, e.roomId(), json.writeValueAsString(e)));
            producer.flush();
        }
    }

    static void await(BooleanSupplier c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (c.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met");
    }

    RestClient console() {
        return RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(HttpStatusCode::isError, (q, r) -> { })
                .defaultHeader("X-User-Id", "op_admin").build();
    }

    @Test
    void aFairLotteryProducesAFairReportAndRedeliveryDoesNotDoubleCount() throws Exception {
        String room = "rm_fair";
        int n = 400;
        String seed = Lottery.newSeed();
        List<QueueEvent> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            events.add(new QueueEvent.Builder("JOINED", room, 1).visitor("v" + i).kind("L").index(i).subnet("abc").build());
        }
        events.add(new QueueEvent.Builder("OPENED", room, 1).range(n, n).detail(seed).build());
        events.add(new QueueEvent.Builder("ADMITTED", room, 1).range(0, n).build());
        for (int i = 0; i < n; i++) {
            events.add(new QueueEvent.Builder("CLAIMED", room, 1).visitor("v" + i).kind("L").index(i)
                    .position(Lottery.permute(i, n, seed)).waited(1000L * i).build());
        }
        events.add(new QueueEvent.Builder("REJECTED", room, 1).reason("POW_INVALID").subnet("bad").build());
        send(events);
        send(events.subList(0, 50)); // redelivery
        await(() -> jdbc.sql("SELECT count(*) FROM lobby_entries WHERE room_id = ? AND position IS NOT NULL").param(room).query(Integer.class).single() == n);
        Thread.sleep(1000);

        Map<?, ?> overview = console().get().uri("/api/console/insight/rooms/{r}/overview", room).retrieve().body(Map.class);
        Map<?, ?> totals = (Map<?, ?>) overview.get("totals");
        assertThat(((Number) totals.get("joins")).longValue()).as("redelivered joins counted once").isEqualTo(n);
        assertThat(((Number) totals.get("claimed")).longValue()).isEqualTo(n);
        assertThat(((Number) totals.get("rejectedPow")).longValue()).isEqualTo(1);
        assertThat(((Number) ((Map<?, ?>) overview.get("waits")).get("p50")).doubleValue()).isGreaterThan(0);

        Map<?, ?> fair = console().get().uri("/api/console/insight/rooms/{r}/fairness?commitment={c}", room, Lottery.commitment(seed)).retrieve().body(Map.class);
        assertThat(fair.get("commitmentMatches")).isEqualTo(true);
        assertThat(fair.get("noEarlyBirdAdvantage")).isEqualTo(true);
        assertThat((List<?>) fair.get("deciles")).hasSize(10);
        assertThat(console().get().uri("/api/console/insight/rooms/{r}/fairness?commitment={c}", room, "0".repeat(64)).retrieve()
                .body(Map.class).get("commitmentMatches")).isEqualTo(false);
    }

    @Test
    void anUnfairOrderIsDetected() throws Exception {
        String room = "rm_unfair";
        int n = 200;
        List<QueueEvent> events = new ArrayList<>();
        for (int i = 0; i < n; i++) events.add(new QueueEvent.Builder("JOINED", room, 1).visitor("u" + i).kind("L").index(i).build());
        events.add(new QueueEvent.Builder("OPENED", room, 1).range(n, n).detail(Lottery.newSeed()).build());
        // First come, first served disguised as a lottery: early joiners get early places.
        for (int i = 0; i < n; i++) events.add(new QueueEvent.Builder("CLAIMED", room, 1).visitor("u" + i).kind("L").index(i).position(i).build());
        send(events);
        await(() -> jdbc.sql("SELECT count(*) FROM lobby_entries WHERE room_id = ? AND position IS NOT NULL").param(room).query(Integer.class).single() == n);
        Map<?, ?> fair = console().get().uri("/api/console/insight/rooms/{r}/fairness", room).retrieve().body(Map.class);
        assertThat(((Number) fair.get("spearman")).doubleValue()).isGreaterThan(0.99);
        assertThat(fair.get("noEarlyBirdAdvantage")).isEqualTo(false);
        assertThat(RestClient.create("http://localhost:" + port).get().uri("/api/console/insight/rooms/{r}/fairness", room).retrieve()
                .onStatus(HttpStatusCode::isError, (q, r) -> { }).toBodilessEntity().getStatusCode().value()).isEqualTo(401);
    }
}
