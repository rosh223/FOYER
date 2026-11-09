package dev.foyer.shop;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param foyerUrl        Foyer's API (for the verification key and purchase reports)
 * @param waitingRoomBase public URL of Foyer's waiting room pages, e.g. http://localhost:3005/q/
 * @param events          the events on sale, each protected by its own Foyer room
 */
@ConfigurationProperties("shop")
public record ShopProperties(String foyerUrl, String waitingRoomBase, String adminKey, List<Event> events) {

    /** @param tickets stock per seat category, in the order Gold, Silver, Bronze */
    public record Event(String slug, String roomId, String originKey, String title, String subtitle, String date, List<Integer> tickets) {
    }
}
