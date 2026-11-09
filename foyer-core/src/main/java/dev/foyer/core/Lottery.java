package dev.foyer.core;

import java.security.SecureRandom;

/**
 * The lobby lottery. Everyone who joins before opening gets a lobby number {@code i} in {@code [0, n)}; at opening their place
 * in line is {@code permute(i, n, seed)}: a keyed pseudo-random permutation, so every lobby member gets exactly one distinct
 * place, computed on demand and never stored.
 *
 * <p>Construction: a balanced Feistel network on {@code 2b}-bit integers (the smallest even width that covers {@code n}),
 * {@value #ROUNDS} rounds, round function = first 8 bytes of HMAC-SHA256(seed, round ‖ half) masked to {@code b} bits.
 * A Feistel network is a bijection whatever its round function; <em>cycle walking</em> (re-applying it until the value
 * falls inside {@code [0, n)}) turns it into a bijection on {@code [0, n)}. Because the domain is less than 4n, the
 * expected number of walks is under 4.
 *
 * <p>The exact byte layout is part of the public verification algorithm (the waiting room's verify page reimplements it):
 * message = 1 byte round number, then 8 bytes of the half value, big-endian.
 */
public final class Lottery {

    public static final int ROUNDS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Lottery() {
    }

    /** A fresh 256-bit seed, hex-encoded. */
    public static String newSeed() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Codec.hex(b);
    }

    /** The public commitment to a seed: hex SHA-256 of the seed's bytes. */
    public static String commitment(String seedHex) {
        return Codec.hex(Codec.sha256(Codec.unhex(seedHex)));
    }

    public static boolean verifyCommitment(String seedHex, String commitmentHex) {
        return Codec.equal(Codec.unhex(commitment(seedHex)), Codec.unhex(commitmentHex));
    }

    /** Place in line (0-based) of lobby number {@code index} in a lobby of {@code n}. */
    public static long permute(long index, long n, String seedHex) {
        if (n <= 0 || index < 0 || index >= n) throw new IllegalArgumentException("index " + index + " outside [0, " + n + ")");
        if (n == 1) return 0;
        byte[] key = Codec.unhex(seedHex);
        int half = halfBits(n);
        long x = index;
        do {
            x = feistel(x, half, key);
        } while (x >= n);
        return x;
    }

    /** Bits per half: the smallest b such that 2^(2b) >= n, at least 1. */
    static int halfBits(long n) {
        int bits = 64 - Long.numberOfLeadingZeros(n - 1);
        int half = (bits + 1) / 2;
        return Math.max(1, half);
    }

    static long feistel(long x, int half, byte[] key) {
        long mask = (1L << half) - 1;
        long left = (x >>> half) & mask;
        long right = x & mask;
        for (int r = 0; r < ROUNDS; r++) {
            long next = left ^ (round(key, r, right) & mask);
            left = right;
            right = next;
        }
        return (left << half) | right;
    }

    private static long round(byte[] key, int round, long value) {
        byte[] msg = new byte[9];
        msg[0] = (byte) round;
        for (int i = 0; i < 8; i++) msg[1 + i] = (byte) (value >>> (56 - 8 * i));
        byte[] h = Codec.hmac(key, msg);
        long out = 0;
        for (int i = 0; i < 8; i++) out = (out << 8) | (h[i] & 0xFF);
        return out;
    }
}
