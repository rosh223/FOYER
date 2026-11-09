package dev.foyer.queue.api;

import dev.foyer.core.Codec;
import dev.foyer.queue.config.QueueProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * The visitor's address, for rate limits and bot signals. Behind nginx and the gateway, it's the first X-Forwarded-For
 * entry. The rehearsal simulator may present simulated addresses, but only with the shared rehearsal key.
 * Addresses are only ever stored as salted hashes of their network (/24 for IPv4, /48 for IPv6).
 */
@Component
public class ClientAddress {

    private final QueueProperties props;

    public ClientAddress(QueueProperties props) {
        this.props = props;
    }

    public String ip(ServerHttpRequest req) {
        String sim = req.getHeaders().getFirst("X-Foyer-Sim-Ip");
        if (sim != null && props.simKey() != null && props.simKey().equals(req.getHeaders().getFirst("X-Foyer-Sim-Key"))) return sim.trim();
        String xff = req.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        InetSocketAddress remote = req.getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    public static String subnet(String ip) {
        if (ip.contains(":")) {
            String[] p = ip.split(":");
            return p.length >= 3 ? p[0] + ":" + p[1] + ":" + p[2] + "::/48" : ip;
        }
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last) + ".0/24" : ip;
    }

    /** Short salted hash of a subnet, safe to store in events. */
    public String subnetHash(String ip) {
        return Codec.hex(Codec.hmac(props.ticketSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                subnet(ip).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 12);
    }
}
