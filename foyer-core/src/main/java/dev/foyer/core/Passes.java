package dev.foyer.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Admission passes: what a visitor shows the origin site. Signed with Ed25519 so origins verify them offline with the
 * public key alone (no shared secret, no call to Foyer per request).
 *
 * <p>Token: {@code fp1.<base64url("roomId|visitorId|passId|notBeforeMillis|expiresAtMillis")>.<base64url(signature)>}
 */
public final class Passes {

    private static final String PREFIX = "fp1";

    public record Claims(String roomId, String visitorId, String passId, long notBefore, long expiresAt) {
    }

    public enum Status { VALID, MALFORMED, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED, WRONG_ROOM }

    public record Verification(Status status, Claims claims) {
        public boolean valid() {
            return status == Status.VALID;
        }
    }

    private Passes() {
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PrivateKey privateKey(String base64Pkcs8) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(base64Pkcs8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Not an Ed25519 PKCS#8 private key", e);
        }
    }

    public static PublicKey publicKey(String base64X509) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(java.util.Base64.getDecoder().decode(base64X509)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Not an Ed25519 X.509 public key", e);
        }
    }

    public static String issue(PrivateKey key, Claims c) {
        String payload = Codec.b64(String.join("|", c.roomId(), c.visitorId(), c.passId(), Long.toString(c.notBefore()), Long.toString(c.expiresAt())));
        String signed = PREFIX + "." + payload;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(signed.getBytes(StandardCharsets.UTF_8));
            return signed + "." + Codec.b64(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param roomId the room this origin protects, or null to accept any room */
    public static Verification verify(PublicKey key, String token, String roomId, long now) {
        if (token == null) return new Verification(Status.MALFORMED, null);
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !parts[0].equals(PREFIX)) return new Verification(Status.MALFORMED, null);
        Claims c;
        try {
            String[] f = Codec.unb64String(parts[1]).split("\\|");
            if (f.length != 5) return new Verification(Status.MALFORMED, null);
            c = new Claims(f[0], f[1], f[2], Long.parseLong(f[3]), Long.parseLong(f[4]));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] sig = Codec.unb64(parts[2]);
            try {
                if (!s.verify(sig)) return new Verification(Status.BAD_SIGNATURE, c);
            } catch (java.security.SignatureException e) {
                // The JDK throws (rather than returning false) for some corrupted signatures, e.g. an invalid curve point.
                return new Verification(Status.BAD_SIGNATURE, c);
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            return new Verification(Status.MALFORMED, null);
        }
        if (now < c.notBefore()) return new Verification(Status.NOT_YET_VALID, c);
        if (now >= c.expiresAt()) return new Verification(Status.EXPIRED, c);
        if (roomId != null && !roomId.equals(c.roomId())) return new Verification(Status.WRONG_ROOM, c);
        return new Verification(Status.VALID, c);
    }
}
