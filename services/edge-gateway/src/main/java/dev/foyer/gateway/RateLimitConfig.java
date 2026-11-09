package dev.foyer.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

@Configuration
public class RateLimitConfig {

    /**
     * Per signed-in operator for the console; per client address for visitors. Behind nginx the address is the first
     * X-Forwarded-For entry. Limits for visitors are generous because thousands of people can share one address
     * (campus networks, mobile carrier NAT).
     */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> {
            Object user = exchange.getAttributes().get(AuthenticationFilter.USER_ATTRIBUTE);
            if (user != null) return Mono.just("user:" + user);
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            String ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim()
                    : Optional.ofNullable(exchange.getRequest().getRemoteAddress()).map(InetSocketAddress::getHostString).orElse("unknown");
            return Mono.just("ip:" + ip);
        };
    }
}
