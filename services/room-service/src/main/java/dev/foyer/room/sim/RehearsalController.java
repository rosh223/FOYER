package dev.foyer.room.sim;

import dev.foyer.room.config.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/rooms/{id}/rehearsal")
public class RehearsalController {

    private final RehearsalService rehearsals;

    public RehearsalController(RehearsalService rehearsals) {
        this.rehearsals = rehearsals;
    }

    public record Start(int humans, int bots, int spreadSeconds) {
    }

    @PostMapping
    public RehearsalService.Status start(@PathVariable String id, @RequestBody Start body, HttpServletRequest req) {
        Caller.from(req).requireAdmin();
        return rehearsals.start(id, body.humans(), body.bots(), body.spreadSeconds());
    }

    @GetMapping
    public ResponseEntity<RehearsalService.Status> status(@PathVariable String id, HttpServletRequest req) {
        Caller.from(req);
        var s = rehearsals.status(id);
        return s == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(s);
    }

    @PostMapping("/stop")
    public ResponseEntity<RehearsalService.Status> stop(@PathVariable String id, HttpServletRequest req) {
        Caller.from(req).requireAdmin();
        var s = rehearsals.stop(id);
        return s == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(s);
    }
}
