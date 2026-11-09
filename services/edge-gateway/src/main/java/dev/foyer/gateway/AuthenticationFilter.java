package dev.foyer.gateway;

import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Console APIs need an operator JWT; changes need the ADMIN role. The public queue API (visitors) and the origin API
 * (origin keys) are open here and protected by rate limits and their own credentials. Client-sent X-User-* headers are
 * always stripped.
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    public static final String USER_ATTRIBUTE = "foyer.user";
    private static final List<String> IDENTITY_HEADERS = List.of("X-User-Id", "X-User-Role", "X-User-Name");

    private final Jwt jwt;

    public AuthenticationFilter(Jwt jwt) {
        this.jwt = jwt;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        ServerHttpRequest stripped = request.mutate().headers(h -> IDENTITY_HEADERS.forEach(h::remove)).build();
        if (!path.startsWith("/api/console/") || path.startsWith("/api/console/auth/")) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }
        Optional<Claims> claims = token(exchange).flatMap(jwt::verify);
        if (claims.isEmpty()) return reject(exchange, HttpStatus.UNAUTHORIZED, "Sign in again: your session is missing or expired");
        Claims c = claims.get();
        String role = String.valueOf(c.get("role"));
        if (!"ADMIN".equals(role) && writes(request.getMethod())) {
            return reject(exchange, HttpStatus.FORBIDDEN, "Your role can view but not change rooms");
        }
        ServerHttpRequest withIdentity = stripped.mutate()
                .header("X-User-Id", c.getSubject())
                .header("X-User-Role", role)
                .header("X-User-Name", String.valueOf(c.get("name")))
                .build();
        exchange.getAttributes().put(USER_ATTRIBUTE, c.getSubject());
        return chain.filter(exchange.mutate().request(withIdentity).build());
    }

    static boolean writes(HttpMethod method) {
        return !(method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS);
    }

    private static Optional<String> token(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) return Optional.of(header.substring(7));
        return Optional.empty();
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
