package dev.foyer.room.sim;

import dev.foyer.core.ProofOfWork;
import dev.foyer.room.config.AppProperties;
import dev.foyer.room.rooms.RoomService;
import dev.foyer.room.seed.DemoSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rehearse an on-sale before the real one: simulated humans and bots go through the REAL public queue API. Humans solve
 * the proof of work, join (spread over a window, with a spike near opening), poll at the interval the server suggests,
 * claim their pass and mostly buy (some never show up). Bots hammer joins from a few addresses with fake or reused work.
 * Each simulated visitor presents a simulated IP address, which queue-service only honours with the rehearsal key.
 */
@Service
public class RehearsalService {

    private static final Logger log = LoggerFactory.getLogger(RehearsalService.class);

    public record Status(String roomId, boolean running, int humans, int bots, int joined, int admitted, int purchased,
                         int noShows, int botAttempts, int botRejected, int botJoined, Instant startedAt) {
    }

    private static final class Run {
        final String roomId;
        final int humans;
        final int bots;
        final AtomicInteger joined = new AtomicInteger();
        final AtomicInteger admitted = new AtomicInteger();
        final AtomicInteger purchased = new AtomicInteger();
        final AtomicInteger noShows = new AtomicInteger();
        final AtomicInteger botAttempts = new AtomicInteger();
        final AtomicInteger botRejected = new AtomicInteger();
        final AtomicInteger botJoined = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final Instant startedAt = Instant.now();
        volatile boolean stopped;

        Run(String roomId, int humans, int bots) {
            this.roomId = roomId;
            this.humans = humans;
            this.bots = bots;
        }

        Status status() {
            return new Status(roomId, active.get() > 0 && !stopped, humans, bots, joined.get(), admitted.get(), purchased.get(), noShows.get(),
                    botAttempts.get(), botRejected.get(), botJoined.get(), startedAt);
        }
    }

    private static final dev.foyer.room.config.Caller REHEARSAL = new dev.foyer.room.config.Caller("system", "ADMIN", "Rehearsal");

    private final RestClient queue;
    private final RoomService rooms;
    private final AppProperties props;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public RehearsalService(AppProperties props, RoomService rooms) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.queue = RestClient.builder().baseUrl(props.queueUrl()).requestFactory(factory).build();
        this.rooms = rooms;
        this.props = props;
    }

    public Status start(String roomId, int humans, int bots, int spreadSeconds) {
        var room = rooms.get(roomId);
        int h = Math.max(0, Math.min(5000, humans));
        int b = Math.max(0, Math.min(200, bots));
        int spread = Math.max(1, Math.min(600, spreadSeconds));
        Run previous = runs.get(room.id());
        if (previous != null) previous.stopped = true;
        Run run = new Run(room.id(), h, b);
        runs.put(room.id(), run);
        String demoKey = room.slug().equals("echoes-mumbai") ? DemoSeeder.ECHOES_ORIGIN_KEY : room.slug().equals("tatkal-demo") ? DemoSeeder.TATKAL_ORIGIN_KEY : null;
        // Any other room gets a temporary origin key, so simulated shoppers finish their sessions as real ones would
        // (otherwise they'd hold their slots until their passes expired). It is revoked when the rehearsal ends.
        String tempKeyId = null;
        String originKey = demoKey;
        if (demoKey == null) {
            originKey = "fok_rehearsal_" + java.util.UUID.randomUUID().toString().replace("-", "");
            tempKeyId = rooms.createOriginKeyWithSecret(REHEARSAL, room.id(), "Rehearsal " + Instant.now().toString().substring(0, 16), originKey).key().id();
        }
        final String key = originKey;
        Random r = new Random();
        for (int i = 0; i < h; i++) {
            // Most people arrive in a spike; the rest trickle in across the window.
            long delay = r.nextDouble() < 0.6 ? (long) (Math.abs(r.nextGaussian()) * 3000) : (long) (r.nextDouble() * spread * 1000);
            String ip = "10." + (i % 200) + "." + r.nextInt(250) + "." + (1 + r.nextInt(250));
            run.active.incrementAndGet();
            pool.submit(() -> human(run, room.slug(), ip, delay, key, r.nextDouble() < 0.12));
        }
        for (int i = 0; i < b; i++) {
            String ip = "203.0.113." + (1 + i % 3); // a handful of addresses, as a small bot farm would use
            run.active.incrementAndGet();
            int k = i;
            pool.submit(() -> bot(run, room.slug(), ip, k));
        }
        if (tempKeyId != null) {
            String keyId = tempKeyId;
            pool.submit(() -> revokeWhenDone(run, room.id(), keyId));
        }
        log.info("Rehearsal for {}: {} humans, {} bots over {} s", room.slug(), h, b, spread);
        return run.status();
    }

    public Status status(String roomId) {
        Run run = runs.get(rooms.get(roomId).id());
        return run == null ? null : run.status();
    }

    public Status stop(String roomId) {
        Run run = runs.get(rooms.get(roomId).id());
        if (run != null) run.stopped = true;
        return run == null ? null : run.status();
    }

    private void revokeWhenDone(Run run, String roomId, String keyId) {
        while (run.active.get() > 0) sleep(1000);
        try {
            rooms.revokeOriginKey(REHEARSAL, roomId, keyId);
        } catch (RuntimeException e) {
            log.warn("Could not revoke rehearsal key {}: {}", keyId, e.toString());
        }
    }

    private void human(Run run, String slug, String ip, long delayMs, String originKey, boolean noShow) {
        try {
            sleep(delayMs);
            if (run.stopped) return;
            Map<?, ?> challenge = post("/api/q/" + slug + "/challenge", ip, Map.of());
            String token = (String) challenge.get("token");
            String nonce = ProofOfWork.solve(token, ((Number) challenge.get("difficulty")).intValue());
            Map<?, ?> joined = post("/api/q/" + slug + "/join", ip, Map.of("challenge", token, "nonce", nonce));
            String ticket = (String) joined.get("ticket");
            run.joined.incrementAndGet();
            while (!run.stopped) {
                Map<?, ?> s = queue.get().uri("/api/q/{slug}/status", slug).header("X-Foyer-Ticket", ticket)
                        .header("X-Foyer-Sim-Key", props.simKey()).header("X-Foyer-Sim-Ip", ip).retrieve().body(Map.class);
                Object pass = s.get("pass");
                if (pass != null) {
                    run.admitted.incrementAndGet();
                    if (noShow) {
                        run.noShows.incrementAndGet();
                        return;
                    }
                    sleep(3000 + (long) (Math.random() * 12_000)); // browsing and paying
                    if (originKey != null) {
                        queue.post().uri("/api/origin/complete").header("X-Foyer-Origin-Key", originKey).contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("pass", pass, "purchases", 1 + (int) (Math.random() * 3))).retrieve().toBodilessEntity();
                    }
                    run.purchased.incrementAndGet();
                    return;
                }
                if ("CLOSED".equals(s.get("state")) || "SOLD_OUT".equals(s.get("state"))) return;
                Object poll = s.get("pollSeconds");
                sleep(1000L * (poll instanceof Number p ? p.intValue() : 5));
            }
        } catch (RuntimeException e) {
            log.debug("Simulated visitor failed: {}", e.toString());
        } finally {
            run.active.decrementAndGet();
        }
    }

    /** A bot: hammers joins with made-up proof of work, then reuses one valid solution, from a shared address. */
    private void bot(Run run, String slug, String ip, int k) {
        try {
            String reused = null;
            String reusedNonce = null;
            for (int attempt = 0; attempt < 25 && !run.stopped; attempt++) {
                run.botAttempts.incrementAndGet();
                try {
                    Map<?, ?> c = post("/api/q/" + slug + "/challenge", ip, Map.of());
                    String token = (String) c.get("token");
                    String nonce;
                    if (k % 3 == 0 && reused != null) {
                        token = reused;
                        nonce = reusedNonce;
                    } else if (k % 3 == 1) {
                        nonce = "not-real-work-" + attempt;
                    } else {
                        nonce = ProofOfWork.solve(token, ((Number) c.get("difficulty")).intValue());
                        reused = token;
                        reusedNonce = nonce;
                    }
                    post("/api/q/" + slug + "/join", ip, Map.of("challenge", token, "nonce", nonce));
                    run.botJoined.incrementAndGet();
                } catch (RestClientResponseException e) {
                    run.botRejected.incrementAndGet();
                }
                sleep(50);
            }
        } finally {
            run.active.decrementAndGet();
        }
    }

    private Map<?, ?> post(String path, String ip, Map<String, ?> body) {
        return queue.post().uri(path).header("X-Foyer-Sim-Key", props.simKey()).header("X-Foyer-Sim-Ip", ip)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
