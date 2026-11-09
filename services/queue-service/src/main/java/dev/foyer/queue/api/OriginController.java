package dev.foyer.queue.api;

import dev.foyer.contracts.RoomConfig;
import dev.foyer.core.Codec;
import dev.foyer.core.Passes;
import dev.foyer.queue.events.EventPublisher;
import dev.foyer.queue.rooms.RoomStore;
import dev.foyer.queue.state.QueueState;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;

/**
 * Called by origin sites with the room's origin key: a purchase finished (ends the session, marks the pass used, frees a
 * slot) or the stock ran out (everyone still waiting is told at once instead of waiting for nothing).
 */
@RestController
@RequestMapping("/api/origin")
public class OriginController {

    private final PublicKey publicKey;
    private final RoomStore rooms;
    private final QueueState state;
    private final EventPublisher events;

    public OriginController(PublicKey publicKey, RoomStore rooms, QueueState state, EventPublisher events) {
        this.publicKey = publicKey;
        this.rooms = rooms;
        this.state = state;
        this.events = events;
    }

    public record Complete(String pass, Integer purchases) {
    }

    @PostMapping("/complete")
    public Mono<Map<String, Object>> complete(@RequestBody Complete body, @RequestHeader(value = "X-Foyer-Origin-Key", required = false) String key) {
        Passes.Verification v = Passes.verify(publicKey, body.pass(), null, System.currentTimeMillis() - 3_600_000L);
        if (v.claims() == null || v.status() == Passes.Status.BAD_SIGNATURE || v.status() == Passes.Status.MALFORMED) {
            return Mono.error(new ApiError(HttpStatus.BAD_REQUEST, "bad_pass", "Not a valid Foyer pass"));
        }
        RoomConfig r = authorised(v.claims().roomId(), key);
        return state.complete(r, v.claims().passId(), Duration.ofDays(2)).map(first -> {
            if (first) {
                events.publish(EventPublisher.event("COMPLETED", r).visitor(v.claims().visitorId()).pass(v.claims().passId())
                        .index(body.purchases() == null ? 1 : Math.max(0, body.purchases())).build());
            }
            return Map.<String, Object>of("ok", true, "alreadyCompleted", !first);
        });
    }

    public record SoldOut(String roomId) {
    }

    @PostMapping("/sold-out")
    public Mono<Map<String, Object>> soldOut(@RequestBody SoldOut body, @RequestHeader(value = "X-Foyer-Origin-Key", required = false) String key) {
        RoomConfig r = authorised(body.roomId(), key);
        return state.markSoldOut(r).map(first -> {
            if (first) events.publish(EventPublisher.event("SOLD_OUT", r).build());
            return Map.<String, Object>of("ok", true);
        });
    }

    private RoomConfig authorised(String roomId, String key) {
        RoomConfig r = rooms.byId(roomId).orElseThrow(() -> ApiError.notFound("Room " + roomId));
        if (key == null || !r.originKeyHashes().contains(Codec.hex(Codec.sha256(key)))) {
            throw new ApiError(HttpStatus.UNAUTHORIZED, "bad_origin_key", "Unknown origin key for this room");
        }
        return r;
    }
}
