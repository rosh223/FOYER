package dev.foyer.core;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Stateless proof-of-work challenges: instead of a CAPTCHA, the browser spends about a second of CPU finding a nonce such
 * that SHA-256(challenge + ":" + nonce) starts with {@code difficulty} zero bits. Cheap for one person, expensive for a
 * bot farm making thousands of joins. Challenges are HMAC-signed (the server keeps no state until one is used) and expire.
 *
 * <p>Token: {@code fc1.<base64url("roomId|challengeId|difficulty|expiresAtMillis")>.<base64url(HMAC)>}
 */
public final class ProofOfWork {

    private static final String PREFIX = "fc1";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Challenge(String token, String roomId, String id, int difficulty, long expiresAt) {
    }

    public enum Result { OK, MALFORMED, BAD_SIGNATURE, EXPIRED, WRONG_ROOM, INSUFFICIENT_WORK }

    private final byte[] secret;

    public ProofOfWork(byte[] secret) {
        if (secret.length < 16) throw new IllegalArgumentException("secret too short");
        this.secret = secret.clone();
    }

    public Challenge issue(String roomId, int difficulty, long ttlMillis, long now) {
        if (difficulty < 0 || difficulty > 40) throw new IllegalArgumentException("difficulty 0..40");
        byte[] idBytes = new byte[12];
        RANDOM.nextBytes(idBytes);
        String id = Codec.b64(idBytes);
        long exp = now + ttlMillis;
        String payload = Codec.b64(roomId + "|" + id + "|" + difficulty + "|" + exp);
        String token = PREFIX + "." + payload + "." + Codec.b64(Codec.hmac(secret, (PREFIX + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return new Challenge(token, roomId, id, difficulty, exp);
    }

    /** Checks the signature, expiry, room and the work. Single use is enforced by the caller (it knows the challenge id). */
    public Result verify(String token, String nonce, String roomId, long now) {
        Challenge c = parse(token);
        if (c == null) return Result.MALFORMED;
        String[] parts = token.split("\\.");
        byte[] expected = Codec.hmac(secret, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        if (!Codec.equal(expected, Codec.unb64(parts[2]))) return Result.BAD_SIGNATURE;
        if (now > c.expiresAt()) return Result.EXPIRED;
        if (!c.roomId().equals(roomId)) return Result.WRONG_ROOM;
        if (nonce == null || nonce.length() > 64) return Result.INSUFFICIENT_WORK;
        return leadingZeroBits(Codec.sha256(token + ":" + nonce)) >= c.difficulty() ? Result.OK : Result.INSUFFICIENT_WORK;
    }

    /** Parses without verifying (to read the id for the single-use check). Null if malformed. */
    public static Challenge parse(String token) {
        try {
            if (token == null) return null;
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !parts[0].equals(PREFIX)) return null;
            String[] f = Codec.unb64String(parts[1]).split("\\|");
            if (f.length != 4) return null;
            return new Challenge(token, f[0], f[1], Integer.parseInt(f[2]), Long.parseLong(f[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static int leadingZeroBits(byte[] hash) {
        int n = 0;
        for (byte b : hash) {
            if (b == 0) {
                n += 8;
                continue;
            }
            n += Integer.numberOfLeadingZeros(b & 0xFF) - 24;
            break;
        }
        return n;
    }

    /** Brute-force solver (tests, the load generator and the simulator). */
    public static String solve(String token, int difficulty) {
        for (long i = 0; ; i++) {
            String nonce = Long.toString(i, 36);
            if (leadingZeroBits(Codec.sha256(token + ":" + nonce)) >= difficulty) return nonce;
        }
    }
}
