package dev.foyer.room.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param queueUrl   queue-service base URL, for crowd rehearsals
 * @param shopUrl    the demo origin's base URL (seeded rooms send admitted visitors there)
 * @param seedDemo   create demo operators and rooms on first start
 * @param simKey     shared secret that lets the rehearsal simulator present simulated IP addresses to queue-service
 */
@ConfigurationProperties("foyer")
public record AppProperties(String jwtSecret, String queueUrl, String shopUrl, boolean seedDemo, String simKey) {
}
