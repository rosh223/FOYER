package dev.foyer.queue.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/** The visitor-facing API used by the waiting room page, the JS tag and mobile apps. */
@RestController
@RequestMapping("/api/q/{slug}")
public class PublicController {

    private final QueueService queue;
    private final ClientAddress address;

    public PublicController(QueueService queue, ClientAddress address) {
        this.queue = queue;
        this.address = address;
    }

    /** Room information. Briefly cacheable: a CDN can absorb the crowd refreshing this page. */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> info(@PathVariable String slug) {
        return queue.info(slug).map(m -> ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(1)).cachePublic()).body(m));
    }

    @PostMapping("/challenge")
    public Mono<Map<String, Object>> challenge(@PathVariable String slug, ServerWebExchange exchange) {
        return queue.challenge(slug, address.ip(exchange.getRequest()));
    }

    @PostMapping("/join")
    public Mono<Map<String, Object>> join(@PathVariable String slug, @RequestBody QueueService.JoinRequest body, ServerWebExchange exchange) {
        String ip = address.ip(exchange.getRequest());
        return queue.join(slug, ip, address.subnetHash(ip), body);
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> status(@PathVariable String slug, @RequestHeader(value = "X-Foyer-Ticket", required = false) String ticket,
                                                            ServerWebExchange exchange) {
        String t = ticket != null ? ticket : exchange.getRequest().getQueryParams().getFirst("ticket");
        return queue.status(slug, t).map(m -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(m));
    }
}
