package dev.foyer.room.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in operator, from headers the gateway sets after verifying the JWT (client-sent copies are stripped). */
public record Caller(String userId, String role, String name) {

    public static Caller from(HttpServletRequest req) {
        String user = req.getHeader("X-User-Id");
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        return new Caller(user, req.getHeader("X-User-Role"), req.getHeader("X-User-Name"));
    }

    public Caller requireAdmin() {
        if (!"ADMIN".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        return this;
    }

    public String display() {
        return name == null ? userId : name;
    }
}
