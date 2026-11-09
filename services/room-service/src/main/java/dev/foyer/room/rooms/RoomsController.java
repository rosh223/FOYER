package dev.foyer.room.rooms;

import dev.foyer.room.config.Caller;
import dev.foyer.room.publish.AdminAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/console/rooms")
public class RoomsController {

    private final RoomService rooms;
    private final AdminAudit audit;

    public RoomsController(RoomService rooms, AdminAudit audit) {
        this.rooms = rooms;
        this.audit = audit;
    }

    @GetMapping
    public List<RoomService.RoomView> list(HttpServletRequest req) {
        Caller.from(req);
        return rooms.list();
    }

    @GetMapping("/{id}")
    public RoomService.RoomView get(@PathVariable String id, HttpServletRequest req) {
        Caller.from(req);
        return rooms.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomService.RoomView create(@RequestBody RoomService.RoomRequest body, HttpServletRequest req) {
        return rooms.create(Caller.from(req).requireAdmin(), body);
    }

    @PutMapping("/{id}")
    public RoomService.RoomView update(@PathVariable String id, @RequestBody RoomService.RoomRequest body, HttpServletRequest req) {
        return rooms.update(Caller.from(req).requireAdmin(), id, body);
    }

    public record Rate(int ratePerMinute, int maxActive) {
    }

    @PutMapping("/{id}/rate")
    public RoomService.RoomView rate(@PathVariable String id, @RequestBody Rate body, HttpServletRequest req) {
        return rooms.setRate(Caller.from(req).requireAdmin(), id, body.ratePerMinute(), body.maxActive());
    }

    @PostMapping("/{id}/open-now")
    public RoomService.RoomView openNow(@PathVariable String id, HttpServletRequest req) {
        return rooms.openNow(Caller.from(req).requireAdmin(), id);
    }

    @PostMapping("/{id}/pause")
    public RoomService.RoomView pause(@PathVariable String id, HttpServletRequest req) {
        return rooms.setState(Caller.from(req).requireAdmin(), id, "PAUSED");
    }

    @PostMapping("/{id}/resume")
    public RoomService.RoomView resume(@PathVariable String id, HttpServletRequest req) {
        return rooms.setState(Caller.from(req).requireAdmin(), id, "SCHEDULED");
    }

    @PostMapping("/{id}/close")
    public RoomService.RoomView close(@PathVariable String id, HttpServletRequest req) {
        return rooms.setState(Caller.from(req).requireAdmin(), id, "CLOSED");
    }

    public record Reset(int lobbyInMinutes, int opensInMinutes) {
    }

    @PostMapping("/{id}/reset")
    public RoomService.RoomView reset(@PathVariable String id, @RequestBody Reset body, HttpServletRequest req) {
        return rooms.reset(Caller.from(req).requireAdmin(), id, body.lobbyInMinutes(), body.opensInMinutes());
    }

    @GetMapping("/{id}/origin-keys")
    public List<RoomService.OriginKey> keys(@PathVariable String id, HttpServletRequest req) {
        Caller.from(req);
        return rooms.originKeys(id);
    }

    @PostMapping("/{id}/origin-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomService.CreatedKey createKey(@PathVariable String id, @RequestBody Map<String, String> body, HttpServletRequest req) {
        return rooms.createOriginKey(Caller.from(req).requireAdmin(), id, body.get("label"));
    }

    @DeleteMapping("/{id}/origin-keys/{keyId}")
    public void revokeKey(@PathVariable String id, @PathVariable String keyId, HttpServletRequest req) {
        rooms.revokeOriginKey(Caller.from(req).requireAdmin(), id, keyId);
    }

    @GetMapping("/{id}/epochs")
    public List<RoomService.Epoch> epochs(@PathVariable String id, HttpServletRequest req) {
        Caller.from(req);
        return rooms.epochs(id);
    }

    @GetMapping("/{id}/history")
    public List<AdminAudit.Entry> history(@PathVariable String id, HttpServletRequest req) {
        Caller.from(req);
        return audit.forTarget(rooms.get(id).id(), 100);
    }
}
