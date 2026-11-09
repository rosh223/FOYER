package dev.foyer.queue.api;

import dev.foyer.queue.events.EventPublisher;
import dev.foyer.queue.rooms.Phase;
import dev.foyer.queue.rooms.RoomStore;
import dev.foyer.queue.state.QueueState;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The live numbers behind the console's room dashboard, straight from Redis (signed-in operators, through the gateway). */
@RestController
public class ConsoleLiveController {

    private final RoomStore rooms;
    private final QueueState state;
    private final EventPublisher events;

    public ConsoleLiveController(RoomStore rooms, QueueState state, EventPublisher events) {
        this.rooms = rooms;
        this.state = state;
        this.events = events;
    }

    @GetMapping("/api/console/live/{roomId}")
    public Mono<Map<String, Object>> live(@PathVariable String roomId, ServerWebExchange exchange) {
        if (exchange.getRequest().getHeaders().getFirst("X-User-Id") == null) {
            return Mono.error(new ApiError(HttpStatus.UNAUTHORIZED, "unauthorised", "Sign in"));
        }
        var r = rooms.byId(roomId).orElseThrow(() -> ApiError.notFound("Room " + roomId));
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        return Mono.zip(state.snapshot(r), state.activeSessions(r, now), state.claimRatio(r, minute), state.lastMinute(r, minute),
                state.leaseHolder(r).defaultIfEmpty("")).map(t -> {
            var s = t.getT1();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomId", r.id());
            m.put("epoch", r.epoch());
            m.put("phase", Phase.of(r, Instant.ofEpochMilli(now), s.soldOut()).name());
            m.put("opened", s.opened());
            m.put("lobbyJoined", s.lobbyJoined());
            m.put("lobbySize", s.opened() ? s.lobbySize() : null);
            m.put("queueJoined", s.queueJoined());
            m.put("total", s.total());
            m.put("served", s.served());
            m.put("waiting", s.waiting());
            m.put("activeSessions", t.getT2());
            m.put("maxActive", r.maxActive());
            m.put("ratePerMinute", r.ratePerMinute());
            m.put("claimRatio", Math.round(t.getT3() * 1000) / 1000.0);
            m.put("admittedThisMinute", t.getT4()[0]);
            m.put("admittedLastMinute", t.getT4()[1]);
            m.put("claimedThisMinute", t.getT4()[2]);
            m.put("claimedLastMinute", t.getT4()[3]);
            m.put("etaForLastSeconds", r.ratePerMinute() <= 0 ? null : Math.round(s.waiting() * 60.0 / r.ratePerMinute()));
            m.put("leaseHolder", t.getT5());
            m.put("eventsBuffered", events.buffered());
            m.put("now", Instant.ofEpochMilli(now));
            return m;
        });
    }

}
