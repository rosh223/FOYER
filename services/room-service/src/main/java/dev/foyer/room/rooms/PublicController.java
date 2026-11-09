package dev.foyer.room.rooms;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public: every lottery a room has run, with seeds for the ones that have opened, so anyone can re-check them later. */
@RestController
public class PublicController {

    private final RoomService rooms;

    public PublicController(RoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/api/public/rooms/{slug}/epochs")
    public List<RoomService.Epoch> epochs(@PathVariable String slug) {
        return rooms.epochs(slug);
    }
}
