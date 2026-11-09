package dev.foyer.guard;

import java.security.PublicKey;
import java.util.List;

/**
 * @param roomId          the Foyer room protecting this site
 * @param waitingRoomUrl  where visitors without a pass are sent, e.g. https://queue.example.com/q/big-sale
 * @param publicKey       Foyer's pass verification key
 * @param protectedPaths  path prefixes that need a pass (e.g. "/events/", "/checkout")
 * @param secureCookie    set the pass cookie with Secure (true behind HTTPS)
 */
public record GuardConfig(String roomId, String waitingRoomUrl, PublicKey publicKey, List<String> protectedPaths, boolean secureCookie) {

    public boolean protects(String path) {
        return protectedPaths.stream().anyMatch(path::startsWith);
    }
}
