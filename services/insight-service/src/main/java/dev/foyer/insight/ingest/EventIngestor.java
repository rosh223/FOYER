package dev.foyer.insight.ingest;

import dev.foyer.contracts.QueueEvent;
import dev.foyer.contracts.Topics;
import dev.foyer.insight.config.JsonCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Batches from Kafka into one transaction; on failure, one by one, skipping (and counting) anything unstorable. */
@Component
public class EventIngestor {

    private static final Logger log = LoggerFactory.getLogger(EventIngestor.class);

    private final EventStore store;
    private final JsonCodec json;
    private final Counter stored;
    private final Counter skipped;

    public EventIngestor(EventStore store, JsonCodec json, MeterRegistry meters) {
        this.store = store;
        this.json = json;
        this.stored = Counter.builder("foyer.insight.stored").register(meters);
        this.skipped = Counter.builder("foyer.insight.skipped").register(meters);
    }

    @KafkaListener(topics = Topics.EVENTS, containerFactory = "eventsBatchFactory")
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        List<QueueEvent> events = new ArrayList<>(records.size());
        for (var r : records) {
            try {
                events.add(json.read(r.value(), QueueEvent.class));
            } catch (RuntimeException e) {
                skipped.increment();
                log.warn("Skipping unreadable event at offset {}", r.offset());
            }
        }
        try {
            stored.increment(store.store(events));
        } catch (RuntimeException batch) {
            log.warn("Batch of {} failed ({}); storing one by one", events.size(), batch.toString());
            for (QueueEvent e : events) {
                try {
                    stored.increment(store.store(List.of(e)));
                } catch (RuntimeException single) {
                    skipped.increment();
                    log.error("Event {} could not be stored", e.id(), single);
                }
            }
        }
    }
}
