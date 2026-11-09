package dev.foyer.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small encoding and hashing helpers shared by the token formats. */
public final class Codec {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Codec() {
    }

    public static String b64(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    public static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] unb64(String s) {
        return B64D.decode(s);
    }

    public static String unb64String(String s) {
        return new String(unb64(s), StandardCharsets.UTF_8);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public static byte[] unhex(String s) {
        return HexFormat.of().parseHex(s);
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison, so signatures can't be guessed byte by byte from timing. */
    public static boolean equal(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
