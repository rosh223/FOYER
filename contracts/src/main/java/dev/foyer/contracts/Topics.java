package dev.foyer.contracts;

/** Kafka topics, keyed by room id so each room's events stay in order. */
public final class Topics {
    /** Room configuration snapshots (compacted): room-service to every queue-service instance. */
    public static final String ROOMS = "foyer.rooms";
    /** Everything that happens in a queue: queue-service to insight-service. */
    public static final String EVENTS = "foyer.events";

    private Topics() {
    }
}
