package dev.foyer.queue.rooms;

import dev.foyer.contracts.RoomConfig;
import dev.foyer.contracts.Topics;
import dev.foyer.queue.config.JsonCodec;
import dev.foyer.queue.config.QueueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Every room's configuration in memory: pushed through the compacted Kafka topic, pulled over HTTP at start-up. */
@Component
public class RoomStore {

    private static final Logger log = LoggerFactory.getLogger(RoomStore.class);

    private final Map<String, RoomConfig> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idBySlug = new ConcurrentHashMap<>();
    private final JsonCodec json;
    private final WebClient roomService;

    public RoomStore(JsonCodec json, QueueProperties props, WebClient.Builder web) {
        this.json = json;
        this.roomService = web.baseUrl(props.roomServiceUrl()).build();
    }

    public Optional<RoomConfig> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<RoomConfig> bySlug(String slug) {
        String id = idBySlug.get(slug);
        return id == null ? Optional.empty() : byId(id);
    }

    public Collection<RoomConfig> all() {
        return byId.values();
    }

    @KafkaListener(topics = Topics.ROOMS, containerFactory = "broadcastFactory")
    public void onRoom(String payload) {
        apply(json.read(payload, RoomConfig.class), "kafka");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        roomService.get().uri("/internal/rooms").retrieve().bodyToFlux(RoomConfig.class)
                .retryWhen(Retry.backoff(30, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(15)))
                .subscribe(r -> apply(r, "http"), e -> log.warn("Could not bootstrap rooms over HTTP; relying on Kafka: {}", e.toString()));
    }

    public synchronized void apply(RoomConfig r, String via) {
        RoomConfig current = byId.get(r.id());
        if (current != null && current.version() >= r.version()) return;
        if (current != null && !current.slug().equals(r.slug())) idBySlug.remove(current.slug());
        byId.put(r.id(), r);
        idBySlug.put(r.slug(), r.id());
        log.info("Room {} ({}) now at v{}, epoch {}, via {}", r.slug(), r.id(), r.version(), r.epoch(), via);
    }
}
