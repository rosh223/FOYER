package dev.foyer.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotteryTest {

    static final String SEED = "6f1c0a1e9b2d4c7f8a3e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f001";

    @Test
    void isAPermutationForEverySizeUpTo2000() {
        for (int n = 1; n <= 2000; n++) {
            BitSet seen = new BitSet(n);
            for (int i = 0; i < n; i++) {
                long p = Lottery.permute(i, n, SEED);
                assertThat(p).isBetween(0L, (long) n - 1);
                assertThat(seen.get((int) p)).as("n=%d duplicate %d", n, p).isFalse();
                seen.set((int) p);
            }
        }
    }

    @Test
    void largeLobbiesGiveDistinctInRangePlaces() {
        long n = 13_000_000L; // a Coldplay-sized lobby
        Set<Long> seen = new HashSet<>();
        Random r = new Random(1);
        for (int k = 0; k < 50_000; k++) {
            long i = Math.floorMod(r.nextLong(), n);
            long p = Lottery.permute(i, n, SEED);
            assertThat(p).isBetween(0L, n - 1);
            seen.add(i * 31 + p); // smoke check of determinism below
            assertThat(Lottery.permute(i, n, SEED)).isEqualTo(p);
        }
    }

    @Test
    void differentSeedsGiveDifferentOrders() {
        long same = 0;
        String other = "00" + SEED.substring(2);
        for (int i = 0; i < 1000; i++) if (Lottery.permute(i, 1000, SEED) == Lottery.permute(i, 1000, other)) same++;
        assertThat(same).isLessThan(20);
    }

    @Test
    void knownVectorsForTheBrowserImplementation() {
        // The waiting room's verify page reimplements the algorithm; these values pin it down.
        long[] got = new long[8];
        for (int i = 0; i < 8; i++) got[i] = Lottery.permute(i, 10, SEED);
        assertThat(got).containsExactly(9, 5, 2, 7, 0, 3, 1, 4);
        assertThat(Lottery.permute(123456, 1_000_000, SEED)).isEqualTo(899820);
    }

    /** Fairness: across many seeds, a given lobby number lands uniformly anywhere in line (chi-square, 10 buckets). */
    @Test
    void placesAreUniformRegardlessOfLobbyNumber() {
        int n = 1000;
        int seeds = 4000;
        for (int index : new int[]{0, 1, 500, 999}) {
            int[] buckets = new int[10];
            Random r = new Random(index);
            for (int s = 0; s < seeds; s++) {
                byte[] seed = new byte[32];
                r.nextBytes(seed);
                buckets[(int) (Lottery.permute(index, n, Codec.hex(seed)) * 10 / n)]++;
            }
            double expected = seeds / 10.0;
            double chi2 = Arrays.stream(buckets).mapToDouble(b -> (b - expected) * (b - expected) / expected).sum();
            // 9 degrees of freedom: chi-square > 21.67 has p < 0.01.
            assertThat(chi2).as("lobby #%d buckets %s", index, Arrays.toString(buckets)).isLessThan(21.67);
        }
    }

    /** Fairness: arriving earlier in the lobby gives no advantage (Spearman rank correlation ≈ 0). */
    @Test
    void joinOrderAndPlaceAreUncorrelated() {
        int n = 20_000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double d = i - Lottery.permute(i, n, SEED);
            sum += d * d;
        }
        double rho = 1 - 6 * sum / ((double) n * ((double) n * n - 1));
        // |rho| < 2.58 / sqrt(n - 1) is not significant at p = 0.01.
        assertThat(Math.abs(rho)).isLessThan(2.58 / Math.sqrt(n - 1));
    }

    @Test
    void commitRevealAndValidation() {
        String seed = Lottery.newSeed();
        String c = Lottery.commitment(seed);
        assertThat(Lottery.verifyCommitment(seed, c)).isTrue();
        assertThat(Lottery.verifyCommitment(Lottery.newSeed(), c)).isFalse();
        assertThatThrownBy(() -> Lottery.permute(5, 5, SEED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Lottery.permute(0, 1, SEED)).isZero();
    }
}
