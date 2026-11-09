package dev.foyer.queue.admission;

import dev.foyer.contracts.RoomConfig;
import dev.foyer.core.Admission;
import dev.foyer.queue.config.QueueProperties;
import dev.foyer.queue.events.EventPublisher;
import dev.foyer.queue.rooms.Phase;
import dev.foyer.queue.rooms.RoomStore;
import dev.foyer.queue.state.QueueState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * Once a second, for every room: open it when its time comes (freeze the lobby, reveal the seed) and admit the next
 * people. Each room has a Redis lease, so exactly one queue-service instance drives it at a time; if that instance dies,
 * another takes over within a few seconds.
 */
@Component
public class AdmissionTicker {

    private static final Logger log = LoggerFactory.getLogger(AdmissionTicker.class);
    private static final Duration LEASE = Duration.ofSeconds(4);
    private static final Duration MAX_CATCH_UP = Duration.ofSeconds(15);

    private final RoomStore rooms;
    private final QueueState state;
    private final EventPublisher events;
    private final String instanceId;
    private final Map<String, Double> carry = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTick = new ConcurrentHashMap<>();
    private final Counter admitted;

    public AdmissionTicker(RoomStore rooms, QueueState state, EventPublisher events, QueueProperties props, MeterRegistry meters) {
        this.rooms = rooms;
        this.state = state;
        this.events = events;
        this.instanceId = props.instanceId() == null || props.instanceId().isBlank() ? "q-" + UUID.randomUUID().toString().substring(0, 8) : props.instanceId();
        this.admitted = Counter.builder("foyer.admitted").register(meters);
    }

    public String instanceId() {
        return instanceId;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        for (RoomConfig r : rooms.all()) {
            try {
                tick(r, System.currentTimeMillis());
            } catch (RuntimeException e) {
                log.warn("Tick failed for room {}: {}", r.slug(), e.toString());
            }
        }
    }

    void tick(RoomConfig r, long now) {
        var s = state.snapshot(r).block(Duration.ofSeconds(2));
        if (s == null) return;
        Phase phase = Phase.of(r, Instant.ofEpochMilli(now), s.soldOut());
        if (phase != Phase.OPEN && phase != Phase.PAUSED) {
            lastTick.remove(key(r));
            return;
        }
        Boolean leader = state.lease(r, instanceId, LEASE).block(Duration.ofSeconds(2));
        if (!Boolean.TRUE.equals(leader)) return;

        if (!s.opened()) {
            var opening = state.open(r, now).block(Duration.ofSeconds(2));
            if (opening != null && opening.openedNow()) {
                log.info("Room {} opened: lobby of {} frozen, seed revealed", r.slug(), opening.lobbySize());
                events.publish(EventPublisher.event("OPENED", r).range(opening.lobbySize(), opening.lobbySize()).detail(r.seed()).build());
            }
            s = state.snapshot(r).block(Duration.ofSeconds(2));
            if (s == null) return;
        }
        if (phase == Phase.PAUSED) {
            lastTick.put(key(r), now);
            return;
        }
        long minute = now / 60_000;
        // Read everything the step needs in parallel: under heavy visitor traffic each Redis call waits its turn on the shared
        // connection, and five calls in a row made ticks so slow that admissions fell behind the configured rate.
        var inputs = Mono.zip(
                r.maxActive() > 0 ? state.activeSessions(r, now) : Mono.just(0L),
                r.maxActive() > 0 ? state.outstanding(r, now) : Mono.just(0L),
                state.claimRatio(r, minute)).block(Duration.ofSeconds(3));
        if (inputs == null) return;
        long free = r.maxActive() > 0 ? Math.max(0, r.maxActive() - inputs.getT1() - inputs.getT2()) : Long.MAX_VALUE;
        double ratio = inputs.getT3();
        long previous = lastTick.getOrDefault(key(r), now - 1000);
        // Slow ticks catch up on the time they missed (up to MAX_CATCH_UP, so a stall can't release a flood).
        long elapsed = Math.max(0, Math.min(MAX_CATCH_UP.toMillis(), now - previous));
        var step = Admission.step(r.ratePerMinute(), elapsed, carry.getOrDefault(key(r), 0.0), ratio, free, s.waiting());
        if (step.admit() > 0) {
            var range = state.admit(r, step.admit(), now).block(Duration.ofSeconds(3));
            if (range != null && range.to() > range.from()) {
                admitted.increment(range.to() - range.from());
                events.publish(EventPublisher.event("ADMITTED", r).range(range.from(), range.to()).build());
            }
        }
        // Only now is this interval accounted for: if anything above failed, the next tick covers it.
        lastTick.put(key(r), now);
        carry.put(key(r), step.carry());
    }

    private static String key(RoomConfig r) {
        return r.id() + ":" + r.epoch();
    }
}
