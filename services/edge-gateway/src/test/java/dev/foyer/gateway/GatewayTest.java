package dev.foyer.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayTest {

    static final String SECRET = "change-me-in-production-this-is-a-32+-byte-dev-secret";

    @Test
    void onlyReadsAreAllowedForViewers() {
        assertThat(AuthenticationFilter.writes(HttpMethod.GET)).isFalse();
        assertThat(AuthenticationFilter.writes(HttpMethod.POST)).isTrue();
        assertThat(AuthenticationFilter.writes(HttpMethod.PUT)).isTrue();
        assertThat(AuthenticationFilter.writes(HttpMethod.DELETE)).isTrue();
    }

    @Test
    void tokensMustBeFoyerSignedAndUnexpired() {
        Jwt jwt = new Jwt(SECRET);
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThat(jwt.verify(Jwts.builder().subject("u").issuer("foyer").expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact())).isPresent();
        assertThat(jwt.verify(Jwts.builder().subject("u").issuer("veil").signWith(key).compact())).isEmpty();
        assertThat(jwt.verify(Jwts.builder().subject("u").issuer("foyer").expiration(Date.from(Instant.now().minusSeconds(5))).signWith(key).compact())).isEmpty();
        assertThat(jwt.verify("x.y.z")).isEmpty();
    }
}
