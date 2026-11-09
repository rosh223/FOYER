package dev.foyer.core;

import java.nio.charset.StandardCharsets;

/**
 * A visitor's queue ticket: proof of their place, kept by the browser and sent with every status poll. HMAC-signed, so the
 * server can trust it without a lookup and a visitor can't move themselves forward by editing it.
 *
 * <p>Token: {@code ft1.<base64url("roomId|visitorId|kind|index|joinedAtMillis")>.<base64url(HMAC)>}, where kind is
 * {@code L} (lobby: index is the lobby number, place decided by the lottery) or {@code Q} (joined after opening).
 */
public final class Tickets {

    private static final String PREFIX = "ft1";

    public enum Kind { L, Q }

    public record Ticket(String roomId, String visitorId, Kind kind, long index, long joinedAt) {
    }

    private final byte[] secret;

    public Tickets(byte[] secret) {
        if (secret.length < 16) throw new IllegalArgumentException("secret too short");
        this.secret = secret.clone();
    }

    public String issue(Ticket t) {
        String payload = Codec.b64(String.join("|", t.roomId(), t.visitorId(), t.kind().name(), Long.toString(t.index()), Long.toString(t.joinedAt())));
        String signed = PREFIX + "." + payload;
        return signed + "." + Codec.b64(Codec.hmac(secret, signed.getBytes(StandardCharsets.UTF_8)));
    }

    /** The ticket, or null if the token is malformed or was not issued by this secret. */
    public Ticket verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !parts[0].equals(PREFIX)) return null;
            byte[] expected = Codec.hmac(secret, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!Codec.equal(expected, Codec.unb64(parts[2]))) return null;
            String[] f = Codec.unb64String(parts[1]).split("\\|");
            if (f.length != 5) return null;
            return new Ticket(f[0], f[1], Kind.valueOf(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
