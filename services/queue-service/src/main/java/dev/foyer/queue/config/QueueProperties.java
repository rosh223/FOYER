package dev.foyer.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param signingPrivateKey Ed25519 PKCS#8, base64: signs admission passes (same on every instance)
 * @param signingPublicKey  the matching X.509 public key, base64: published for origins
 * @param keyId             identifies the key pair in /.well-known/foyer-keys, so keys can be rotated
 * @param ticketSecret      HMAC secret for queue tickets
 * @param powSecret         HMAC secret for proof-of-work challenges
 * @param simKey            lets the rehearsal simulator present simulated client addresses
 * @param instanceId        identifies this instance when holding a room's admission lease
 */
@ConfigurationProperties("foyer")
public record QueueProperties(String roomServiceUrl, String signingPrivateKey, String signingPublicKey, String keyId,
                              String ticketSecret, String powSecret, String simKey, String instanceId) {
}
