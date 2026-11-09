package dev.foyer.contracts;

import java.time.Instant;

/**
 * One thing that happened in a queue. Only the fields relevant to the type are set.
 *
 * @param epoch    the room's epoch (one sale); a reset starts a new one
 * @param type     JOINED, REJECTED, OPENED, ADMITTED, CLAIMED, COMPLETED, STATE, SOLD_OUT
 * @param kind     L (lobby) or Q (joined after opening), for JOINED and CLAIMED
 * @param index    lobby number or queue number
 * @param position place in line (CLAIMED)
 * @param waitedMs from joining (or opening, for lobby members) to claiming the pass
 * @param reason   why a join was rejected: RATE_LIMITED, POW_INVALID, POW_REUSED, NOT_OPEN, SOLD_OUT
 * @param from     first position admitted (ADMITTED), or lobby size (OPENED)
 * @param to       end of the admitted range, exclusive (ADMITTED)
 * @param subnet   hashed /24 (IPv4) or /48 (IPv6) network of the visitor, never the raw address
 * @param detail   free text: the revealed seed (OPENED), the new state (STATE)
 */
public record QueueEvent(
        String id,
        String type,
        String roomId,
        Integer epoch,
        String visitorId,
        String kind,
        Long index,
        Long position,
        Long waitedMs,
        String reason,
        Long from,
        Long to,
        Integer difficulty,
        String subnet,
        String passId,
        String detail,
        Instant at) {

    /** Fluent builder: events have many optional fields. */
    public static final class Builder {
        private final String type;
        private final String roomId;
        private final Integer epoch;
        private String visitorId;
        private String kind;
        private Long index;
        private Long position;
        private Long waitedMs;
        private String reason;
        private Long from;
        private Long to;
        private Integer difficulty;
        private String subnet;
        private String passId;
        private String detail;

        public Builder(String type, String roomId, Integer epoch) {
            this.type = type;
            this.roomId = roomId;
            this.epoch = epoch;
        }

        public Builder visitor(String v) { this.visitorId = v; return this; }
        public Builder kind(String v) { this.kind = v; return this; }
        public Builder index(long v) { this.index = v; return this; }
        public Builder position(long v) { this.position = v; return this; }
        public Builder waited(long v) { this.waitedMs = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder range(long f, long t) { this.from = f; this.to = t; return this; }
        public Builder difficulty(int v) { this.difficulty = v; return this; }
        public Builder subnet(String v) { this.subnet = v; return this; }
        public Builder pass(String v) { this.passId = v; return this; }
        public Builder detail(String v) { this.detail = v; return this; }

        public QueueEvent build() {
            return new QueueEvent(java.util.UUID.randomUUID().toString(), type, roomId, epoch, visitorId, kind, index, position, waitedMs,
                    reason, from, to, difficulty, subnet, passId, detail, java.time.Instant.now());
        }
    }
}
