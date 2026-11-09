package dev.foyer.queue;

import dev.foyer.contracts.RoomConfig;
import dev.foyer.contracts.Topics;
import dev.foyer.core.Codec;
import dev.foyer.core.Lottery;
import dev.foyer.core.Passes;
import dev.foyer.core.ProofOfWork;
import dev.foyer.queue.rooms.RoomStore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The queue end to end against real Redis and Kafka: joins, the lobby lottery, admissions, passes and abuse. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "foyer.room-service-url=http://localhost:1")
class QueueIT {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORIGIN_KEY = "fok_test_origin_key_123456";

    @LocalServerPort
    int port;

    @Autowired
    RoomStore rooms;

    @Autowired
    PublicKey publicKey;

    final HttpClient http = HttpClient.newHttpClient();

    RoomConfig room(String slug, Instant lobby, Instant opens, int rate, int maxActive, int joinsPerIp, int epoch) {
        String seed = Lottery.newSeed();
        RoomConfig r = new RoomConfig("rm_" + slug, slug, "Test " + slug, "Venue", "", "https://shop.test/" + slug, rate, maxActive, 600,
                lobby, opens, "SCHEDULED", 4, joinsPerIp, seed, Lottery.commitment(seed), List.of(Codec.hex(Codec.sha256(ORIGIN_KEY))),
                epoch, System.nanoTime(), Instant.now());
        rooms.apply(r, "test");
        return r;
    }

    HttpResponse<String> call(String method, String path, Object body, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    /** Challenge + proof of work + join, as a browser would, from a given (simulated) address. */
    String join(String slug, String ip) throws Exception {
        var sim = Map.of("X-Foyer-Sim-Key", "dev-rehearsal-key-change-me", "X-Foyer-Sim-Ip", ip);
        JsonNode c = json(call("POST", "/api/q/" + slug + "/challenge", Map.of(), sim));
        String token = c.path("token").asString();
        String nonce = ProofOfWork.solve(token, c.path("difficulty").asInt());
        var res = call("POST", "/api/q/" + slug + "/join", Map.of("challenge", token, "nonce", nonce), sim);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json(res).path("ticket").asString();
    }

    JsonNode status(String slug, String ticket) throws Exception {
        return json(call("GET", "/api/q/" + slug + "/status", null, Map.of("X-Foyer-Ticket", ticket)));
    }

    static void await(BooleanSupplier c, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (c.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met in " + seconds + " s");
    }

    @Test
    void lobbyLotteryGivesEveryMemberADistinctPlaceFromTheRevealedSeed() throws Exception {
        Instant opens = Instant.now().plusSeconds(4);
        RoomConfig r = room("lottery", Instant.now().minusSeconds(5), opens, 6000, 0, 1000, 1);
        List<String> tickets = new ArrayList<>();
        for (int i = 0; i < 40; i++) tickets.add(join("lottery", "10.0.0." + i));
        JsonNode before = status("lottery", tickets.getFirst());
        assertThat(before.path("state").asString()).isEqualTo("LOBBY");
        assertThat(before.has("position")).as("no place before the lottery").isFalse();
        JsonNode info = json(call("GET", "/api/q/lottery", null, Map.of()));
        assertThat(info.path("seed").isNull()).as("seed hidden before opening").isTrue();
        assertThat(info.path("seedCommitment").asString()).isEqualTo(r.seedCommitment());

        await(() -> {
            try {
                return json(call("GET", "/api/q/lottery", null, Map.of())).path("lobbySize").asInt(-1) == 40;
            } catch (Exception e) {
                return false;
            }
        }, 15);
        JsonNode after = json(call("GET", "/api/q/lottery", null, Map.of()));
        assertThat(after.path("seed").asString()).isEqualTo(r.seed());
        assertThat(Lottery.verifyCommitment(after.path("seed").asString(), after.path("seedCommitment").asString())).isTrue();

        Set<Long> places = new HashSet<>();
        for (String t : tickets) {
            JsonNode s = status("lottery", t);
            long lobbyNumber = s.path("lobbyNumber").asLong();
            long place = s.path("position").asLong();
            assertThat(place).isEqualTo(Lottery.permute(lobbyNumber - 1, 40, r.seed()) + 1);
            places.add(place);
        }
        assertThat(places).hasSize(40);
        // Someone joining after opening goes behind the whole lobby.
        assertThat(status("lottery", join("lottery", "10.0.1.1")).path("position").asLong()).isEqualTo(41);
    }

    @Test
    void admissionsFollowTheRateAndPassesWorkOnceAtTheOrigin() throws Exception {
        room("flow", Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 120, 0, 1000, 1); // 2 per second
        List<String> tickets = new ArrayList<>();
        for (int i = 0; i < 12; i++) tickets.add(join("flow", "10.1.0." + i));
        Thread.sleep(3500);
        long admitted = 0;
        String pass = null;
        for (String t : tickets) {
            JsonNode s = status("flow", t);
            if ("ADMITTED".equals(s.path("state").asString())) {
                admitted++;
                if (pass == null) pass = s.path("pass").asString();
                assertThat(s.path("continueUrl").asString()).startsWith("https://shop.test/flow?foyer_pass=");
            } else {
                assertThat(s.path("ahead").asLong()).isNotNegative();
                assertThat(s.path("etaSeconds").asLong()).isNotNegative();
            }
        }
        assertThat(admitted).as("about 2/s for ~3.5 s").isBetween(4L, 10L);

        // Offline verification with the published key.
        String published = json(call("GET", "/.well-known/foyer-keys", null, Map.of())).path("keys").get(0).path("publicKey").asString();
        assertThat(Passes.verify(Passes.publicKey(published), pass, "rm_flow", System.currentTimeMillis()).valid()).isTrue();
        // Online verification (nginx auth_request), then completion makes it single-use.
        assertThat(call("GET", "/api/passes/verify?room=rm_flow", null, Map.of("X-Foyer-Pass", pass)).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/origin/complete", Map.of("pass", pass, "purchases", 2), Map.of()).statusCode()).isEqualTo(401);
        var done = json(call("POST", "/api/origin/complete", Map.of("pass", pass, "purchases", 2), Map.of("X-Foyer-Origin-Key", ORIGIN_KEY)));
        assertThat(done.path("alreadyCompleted").asBoolean()).isFalse();
        assertThat(json(call("POST", "/api/origin/complete", Map.of("pass", pass), Map.of("X-Foyer-Origin-Key", ORIGIN_KEY))).path("alreadyCompleted").asBoolean()).isTrue();
        var used = call("GET", "/api/passes/verify?room=rm_flow", null, Map.of("X-Foyer-Pass", pass));
        assertThat(used.statusCode()).isEqualTo(401);
        assertThat(used.body()).contains("ALREADY_USED");
        // The same visitor polling again gets the same pass, not a new one.
        int matches = 0;
        for (String t : tickets) if (status("flow", t).path("pass").asString("").equals(pass)) matches++;
        assertThat(matches).isEqualTo(1);
    }

    @Test
    void theSessionCapHoldsAdmissionsUntilSessionsEnd() throws Exception {
        room("capped", Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 6000, 3, 1000, 1);
        List<String> tickets = new ArrayList<>();
        for (int i = 0; i < 8; i++) tickets.add(join("capped", "10.2.0." + i));
        Thread.sleep(2500);
        List<String> passes = new ArrayList<>();
        for (String t : tickets) {
            JsonNode s = status("capped", t);
            if (s.has("pass")) passes.add(s.path("pass").asString());
        }
        assertThat(passes).as("only 3 sessions at a time").hasSize(3);
        for (String p : passes) call("POST", "/api/origin/complete", Map.of("pass", p), Map.of("X-Foyer-Origin-Key", ORIGIN_KEY));
        Thread.sleep(2500);
        long now = 0;
        for (String t : tickets) if (status("capped", t).has("pass")) now++;
        assertThat(now).as("three more admitted after three purchases").isEqualTo(6);
    }

    @Test
    void forgedReusedAndExcessiveJoinsAreRejected() throws Exception {
        room("abuse", Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 60, 0, 3, 1);
        var sim = Map.of("X-Foyer-Sim-Key", "dev-rehearsal-key-change-me", "X-Foyer-Sim-Ip", "203.0.113.9");
        JsonNode c = json(call("POST", "/api/q/abuse/challenge", Map.of(), sim));
        String token = c.path("token").asString();
        // At test difficulty (4 bits) a random nonce passes 1 time in 16, so pick one that is known not to.
        String lazy = "no-work-0";
        for (int i = 1; ProofOfWork.leadingZeroBits(Codec.sha256(token + ":" + lazy)) >= c.path("difficulty").asInt(); i++) lazy = "no-work-" + i;
        var bad = call("POST", "/api/q/abuse/join", Map.of("challenge", token, "nonce", lazy), sim);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("pow_invalid");
        String nonce = ProofOfWork.solve(token, c.path("difficulty").asInt());
        var good = call("POST", "/api/q/abuse/join", Map.of("challenge", token, "nonce", nonce), sim);
        assertThat(good.statusCode()).as(good.body()).isEqualTo(200);
        var reused = call("POST", "/api/q/abuse/join", Map.of("challenge", token, "nonce", nonce), sim);
        assertThat(reused.statusCode()).isEqualTo(400);
        assertThat(reused.body()).contains("pow_reused");
        // 3 joins per address per minute: the 4th is refused with Retry-After.
        join("abuse", "203.0.113.9");
        join("abuse", "203.0.113.9");
        JsonNode c2 = json(call("POST", "/api/q/abuse/challenge", Map.of(), sim));
        var limited = call("POST", "/api/q/abuse/join", Map.of("challenge", c2.path("token").asString(),
                "nonce", ProofOfWork.solve(c2.path("token").asString(), c2.path("difficulty").asInt())), sim);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        // Tampered tickets are refused.
        assertThat(call("GET", "/api/q/abuse/status", null, Map.of("X-Foyer-Ticket", "ft1.eyJ.abc")).statusCode()).isEqualTo(401);
    }

    @Test
    void concurrentJoinsGetDistinctTickets() throws Exception {
        room("rush", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, 0, 100_000, 1);
        Set<Long> numbers = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newFixedThreadPool(32)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    String t = join("rush", "10.3." + (k / 250) + "." + (k % 250));
                    numbers.add(status("rush", t).path("lobbyNumber").asLong());
                    return null;
                }));
            }
            for (var f : futures) f.get();
        }
        assertThat(numbers).hasSize(300);
        assertThat(numbers).allSatisfy(n -> assertThat(n).isBetween(1L, 300L));
    }

    @Test
    void soldOutAndResetEpochs() throws Exception {
        RoomConfig r = room("drop", Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 60, 0, 1000, 1);
        String ticket = join("drop", "10.4.0.1");
        assertThat(call("POST", "/api/origin/sold-out", Map.of("roomId", "rm_drop"), Map.of("X-Foyer-Origin-Key", ORIGIN_KEY)).statusCode()).isEqualTo(200);
        assertThat(status("drop", ticket).path("state").asString()).isEqualTo("SOLD_OUT");
        var c = call("POST", "/api/q/drop/challenge", Map.of(), Map.of());
        assertThat(c.statusCode()).isEqualTo(410);
        // A reset (new epoch) starts a clean sale; old tickets are recognised as stale.
        room("drop", Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 60, 0, 1000, 2);
        assertThat(call("GET", "/api/q/drop/status", null, Map.of("X-Foyer-Ticket", ticket)).statusCode()).isEqualTo(410);
        assertThat(status("drop", join("drop", "10.4.0.2")).path("state").asString()).isIn("OPEN", "ADMITTED");
        assertThat(r.epoch()).isEqualTo(1);
    }

    @Test
    void eventsReachKafka() throws Exception {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        room("evts", Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), 6000, 0, 1000, 1);
        String t = join("evts", "10.5.0.1");
        Thread.sleep(2500);
        status("evts", t);
        Set<String> types = new HashSet<>();
        try (var consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.EVENTS));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && !types.containsAll(Set.of("JOINED", "OPENED", "ADMITTED", "CLAIMED"))) {
                for (var rec : consumer.poll(Duration.ofMillis(300))) {
                    JsonNode e = JSON.readTree(rec.value());
                    if ("rm_evts".equals(e.path("roomId").asString())) {
                        types.add(e.path("type").asString());
                        assertThat(rec.value()).doesNotContain("10.5.0.1");
                    }
                }
            }
        }
        assertThat(types).contains("JOINED", "OPENED", "ADMITTED", "CLAIMED");
        assertThat(publicKey).isNotNull();
    }
}
