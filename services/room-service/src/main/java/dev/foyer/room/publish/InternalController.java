package dev.foyer.room.publish;

import dev.foyer.contracts.RoomConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/** For queue-service's cold start. Not routed by the gateway; NetworkPolicy limits it to queue-service. */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final ConfigPublisher publisher;

    public InternalController(ConfigPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping("/rooms")
    public List<RoomConfig> rooms() {
        return publisher.roomIds().stream().map(id -> publisher.load(id).orElseThrow()).toList();
    }

    @GetMapping("/rooms/{id}")
    public RoomConfig room(@PathVariable String id) {
        return publisher.load(id).orElseThrow(() -> new NoSuchElementException("No room " + id));
    }
}
