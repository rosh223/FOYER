package dev.foyer.core;

/**
 * The admission controller's arithmetic, kept pure so it can be tested exhaustively. Each tick admits
 * {@code rate × seconds / 60} people (fractions carried over), adjusted for no-shows, capped by free session slots and by the
 * number of people actually waiting.
 */
public final class Admission {

    /** Admissions are scaled up for no-shows, but never by more than this factor. */
    public static final double MAX_NO_SHOW_BOOST = 3.0;

    public record Step(long admit, double carry) {
    }

    private Admission() {
    }

    /**
     * @param ratePerMinute  target arrivals at the origin per minute
     * @param elapsedMillis  time since the previous tick
     * @param carry          fractional admissions left over from the previous tick
     * @param claimRatio     recently admitted visitors who actually claimed their pass (0..1); 1 when unknown
     * @param freeSlots      session cap minus active sessions; Long.MAX_VALUE when uncapped
     * @param waiting        people in line who have not been admitted yet
     */
    public static Step step(int ratePerMinute, long elapsedMillis, double carry, double claimRatio, long freeSlots, long waiting) {
        if (ratePerMinute <= 0 || waiting <= 0 || freeSlots <= 0) return new Step(0, ratePerMinute <= 0 ? 0 : Math.min(carry, 1));
        double boost = claimRatio <= 0 ? MAX_NO_SHOW_BOOST : Math.min(MAX_NO_SHOW_BOOST, 1.0 / Math.min(1.0, claimRatio));
        double want = ratePerMinute * (elapsedMillis / 60_000.0) * boost + carry;
        long whole = (long) Math.floor(want);
        long admit = Math.min(whole, Math.min(freeSlots, waiting));
        // Carry the remainder only when we weren't capped, so a cap can't build up a burst for later.
        double nextCarry = admit == whole ? want - whole : 0;
        return new Step(admit, nextCarry);
    }

    /** Suggested seconds between status polls: often near the front, rarely far back (protects the servers). */
    public static int pollSeconds(long ahead, int ratePerMinute) {
        if (ahead <= 0) return 2;
        double minutesAway = ratePerMinute <= 0 ? 60 : ahead / (double) ratePerMinute;
        if (minutesAway < 1) return 3;
        if (minutesAway < 5) return 6;
        if (minutesAway < 30) return 15;
        return 30;
    }
}
