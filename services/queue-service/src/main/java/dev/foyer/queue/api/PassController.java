package dev.foyer.queue.api;

import dev.foyer.core.Passes;
import dev.foyer.queue.config.QueueProperties;
import dev.foyer.queue.rooms.RoomStore;
import dev.foyer.queue.state.QueueState;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * For origins: the public key to verify passes offline, and an online check for nginx {@code auth_request} (which also knows
 * whether a pass has already been used for a purchase).
 */
@RestController
public class PassController {

    private final PublicKey publicKey;
    private final QueueProperties props;
    private final RoomStore rooms;
    private final QueueState state;

    public PassController(PublicKey publicKey, QueueProperties props, RoomStore rooms, QueueState state) {
        this.publicKey = publicKey;
        this.props = props;
        this.rooms = rooms;
        this.state = state;
    }

    @GetMapping("/.well-known/foyer-keys")
    public Map<String, Object> keys() {
        return Map.of("keys", List.of(Map.of("kid", props.keyId(), "alg", "Ed25519",
                "publicKey", Base64.getEncoder().encodeToString(publicKey.getEncoded()))));
    }

    /**
     * 200 with X-Foyer-Room / X-Foyer-Visitor headers if the pass is valid (for this room, when given), 401 otherwise.
     * The pass may come in the X-Foyer-Pass header, the foyer_pass cookie, or the query string (nginx forwards whichever).
     */
    @GetMapping("/api/passes/verify")
    public Mono<ResponseEntity<Map<String, Object>>> verify(@RequestParam(required = false) String room, ServerWebExchange exchange) {
        var req = exchange.getRequest();
        String pass = req.getHeaders().getFirst("X-Foyer-Pass");
        if (pass == null) {
            HttpCookie c = req.getCookies().getFirst("foyer_pass");
            if (c != null) pass = c.getValue();
        }
        if (pass == null) pass = req.getQueryParams().getFirst("foyer_pass");
        String roomId = room != null ? room : req.getHeaders().getFirst("X-Foyer-Room");
        Passes.Verification v = Passes.verify(publicKey, pass, roomId, System.currentTimeMillis());
        if (!v.valid()) return Mono.just(ResponseEntity.status(401).body(Map.of("valid", false, "reason", v.status().name())));
        var cfg = rooms.byId(v.claims().roomId()).orElse(null);
        if (cfg == null) return Mono.just(ResponseEntity.status(401).body(Map.of("valid", false, "reason", "UNKNOWN_ROOM")));
        return state.used(cfg, v.claims().passId()).map(used -> used
                ? ResponseEntity.status(401).<Map<String, Object>>body(Map.of("valid", false, "reason", "ALREADY_USED"))
                : ResponseEntity.ok().header("X-Foyer-Room", v.claims().roomId()).header("X-Foyer-Visitor", v.claims().visitorId())
                .header("X-Foyer-Pass-Id", v.claims().passId()).<Map<String, Object>>body(Map.of("valid", true, "roomId", v.claims().roomId(),
                        "visitorId", v.claims().visitorId(), "expiresAt", v.claims().expiresAt())));
    }
}
