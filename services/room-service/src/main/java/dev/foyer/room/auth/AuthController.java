package dev.foyer.room.auth;

import dev.foyer.room.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Operator sign-in (BCrypt, lockout after five failures). The gateway verifies the JWT on every console call. */
@RestController
public class AuthController {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final SecretKey key;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    public AuthController(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/api/console/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Login req) {
        String email = req.email() == null ? "" : req.email().trim().toLowerCase(Locale.ROOT);
        Attempts a = failures.get(email);
        if (a != null && a.count() >= MAX_ATTEMPTS && a.until().isAfter(Instant.now())) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many failed attempts. Try again in 15 minutes."));
        }
        var row = jdbc.sql("SELECT id, name, password_hash, role FROM operators WHERE email = ?").param(email)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}).optional();
        if (row.isEmpty() || req.password() == null || !bcrypt.matches(req.password(), row.get()[2])) {
            failures.merge(email, new Attempts(1, Instant.now().plus(LOCKOUT)), (old, x) -> new Attempts(old.count() + 1, Instant.now().plus(LOCKOUT)));
            return ResponseEntity.status(401).body(Map.of("error", "Wrong email or password"));
        }
        failures.remove(email);
        String[] u = row.get();
        Instant now = Instant.now();
        String token = Jwts.builder().subject(u[0]).claim("name", u[1]).claim("role", u[3]).issuer("foyer")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(Duration.ofHours(12)))).signWith(key).compact();
        return ResponseEntity.ok(Map.of("accessToken", token, "user", Map.of("id", u[0], "name", u[1], "email", email, "role", u[3])));
    }

    public record Login(String email, String password) {
    }

    record Attempts(int count, Instant until) {
    }
}
