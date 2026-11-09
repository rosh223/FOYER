package dev.foyer.queue.events;

import dev.foyer.contracts.QueueEvent;
import dev.foyer.contracts.Topics;
import dev.foyer.queue.config.JsonCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends queue events to Kafka without ever slowing a visitor down: sends happen on a worker thread, and if Kafka is
 * unavailable events wait in a bounded buffer and are retried. The queue itself never depends on Kafka being up.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonCodec json;
    private final BlockingQueue<QueueEvent> buffer = new ArrayBlockingQueue<>(50_000);
    private final Counter dropped;
    /** Handed to the Kafka producer but not yet acknowledged (the producer holds these itself while Kafka is away). */
    private final AtomicInteger inFlight = new AtomicInteger();

    public EventPublisher(KafkaTemplate<String, String> kafka, JsonCodec json, MeterRegistry meters) {
        this.kafka = kafka;
        this.json = json;
        this.dropped = Counter.builder("foyer.events.dropped").register(meters);
        Gauge.builder("foyer.events.buffered", this, EventPublisher::buffered).register(meters);
    }

    public static QueueEvent.Builder event(String type, dev.foyer.contracts.RoomConfig room) {
        return new QueueEvent.Builder(type, room.id(), room.epoch());
    }

    public void publish(QueueEvent e) {
        try {
            Schedulers.boundedElastic().schedule(() -> send(e));
        } catch (RuntimeException rejected) {
            park(e); // the worker pool is saturated: never let that fail the visitor's request
        }
    }

    private void send(QueueEvent e) {
        inFlight.incrementAndGet();
        try {
            kafka.send(Topics.EVENTS, e.roomId(), json.write(e)).whenComplete((r, ex) -> {
                inFlight.decrementAndGet();
                if (ex != null) park(e);
            });
        } catch (RuntimeException ex) {
            inFlight.decrementAndGet();
            park(e);
        }
    }

    private void park(QueueEvent e) {
        if (!buffer.offer(e)) {
            dropped.increment();
            log.error("Event buffer full; dropped {}", e.id());
        }
    }

    @Scheduled(fixedDelay = 2000)
    void retry() {
        for (int i = buffer.size(); i > 0; i--) {
            QueueEvent e = buffer.poll();
            if (e == null) return;
            send(e);
        }
    }

    /** Events not yet safely in Kafka: waiting for a retry, or sent and awaiting acknowledgement. */
    public int buffered() {
        return buffer.size() + inFlight.get();
    }


}
