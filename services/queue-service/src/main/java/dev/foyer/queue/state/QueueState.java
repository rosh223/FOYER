package dev.foyer.queue.state;

import dev.foyer.contracts.RoomConfig;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * All queue state lives in Redis, namespaced by room and epoch ({@code fy:<room>:<epoch>:...}), so any queue-service instance
 * can answer any request and a reset simply starts a new namespace. Multi-step changes are Lua scripts, so they are atomic
 * even with many instances.
 *
 * <ul>
 *   <li>{@code pre} / {@code post}: number of lobby joins / joins after opening (ticket counters)</li>
 *   <li>{@code opened}, {@code preN}: opening time, and the lobby size frozen at that moment</li>
 *   <li>{@code served}: the frontier; every position below it has been admitted</li>
 *   <li>{@code sessions}: sorted set of live passes by expiry (the active-session count)</li>
 *   <li>{@code claim:<visitor>}: the pass a visitor received, so re-polls get the same one</li>
 * </ul>
 */
@Component
public class QueueState {

    /** Atomic join: per-IP and per-subnet counters, then a lobby or queue ticket depending on whether the room has opened. */
    private static final RedisScript<List> JOIN = RedisScript.of("""
            local c = redis.call('INCR', KEYS[4])
            if c == 1 then redis.call('PEXPIRE', KEYS[4], ARGV[2]) end
            local s = redis.call('INCR', KEYS[5])
            if s == 1 then redis.call('PEXPIRE', KEYS[5], ARGV[2]) end
            if c > tonumber(ARGV[1]) then return {-1, 0, 0} end
            if redis.call('EXISTS', KEYS[3]) == 1 then
              return {0, 1, redis.call('INCR', KEYS[2]) - 1}
            end
            return {0, 0, redis.call('INCR', KEYS[1]) - 1}
            """, List.class);

    /** Opening: freeze the lobby size exactly once. Returns {1, lobbySize} for the caller that opened it, {0, lobbySize} after. */
    private static final RedisScript<List> OPEN = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return {0, tonumber(redis.call('GET', KEYS[3]) or '0')}
            end
            local n = tonumber(redis.call('GET', KEYS[2]) or '0')
            redis.call('SET', KEYS[3], n)
            redis.call('SET', KEYS[1], ARGV[1])
            return {1, n}
            """, List.class);

    /** Advance the frontier by up to n, never past the end of the line. Returns {from, to}. */
    private static final RedisScript<List> ADMIT = RedisScript.of("""
            local total = tonumber(redis.call('GET', KEYS[2]) or '0') + tonumber(redis.call('GET', KEYS[3]) or '0')
            local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
            local nxt = math.min(cur + tonumber(ARGV[1]), total)
            if nxt > cur then redis.call('SET', KEYS[1], nxt) end
            return {cur, nxt}
            """, List.class);

    /** First claim wins: store the pass, register the session, count the claim. Returns {1, pass} or {0, existing}. */
    private static final RedisScript<List> CLAIM = RedisScript.of("""
            local ok = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[4])
            if ok then
              redis.call('ZADD', KEYS[2], ARGV[3], ARGV[2])
              local c = redis.call('INCR', KEYS[3])
              if c == 1 then redis.call('PEXPIRE', KEYS[3], 900000) end
              local b = redis.call('INCR', KEYS[4])
              if b == 1 then redis.call('PEXPIRE', KEYS[4], 300000) end
              return {1, ARGV[1]}
            end
            return {0, redis.call('GET', KEYS[1])}
            """, List.class);

    /** Live sessions: drop expired passes, count the rest. */
    private static final RedisScript<Long> ACTIVE = RedisScript.of("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    /** Take or renew a room's admission lease. 1 = this instance holds it. */
    private static final RedisScript<Long> LEASE = RedisScript.of("""
            local v = redis.call('GET', KEYS[1])
            if v == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if not v then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    /** Granularity of the admitted/claimed counters used for the session cap. */
    static final long BUCKET_MS = 10_000;
    /** How long an admitted person who hasn't claimed yet holds a slot under the cap. */
    static final Duration CLAIM_WINDOW = Duration.ofSeconds(40);

    private final ReactiveStringRedisTemplate redis;

    public QueueState(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String ns(RoomConfig r) {
        return "fy:" + r.id() + ":" + r.epoch() + ":";
    }

    public record JoinResult(boolean rateLimited, boolean lobby, long index) {
    }

    public Mono<JoinResult> join(RoomConfig r, String ip, String subnet, long minute) {
        String ns = ns(r);
        return redis.execute(JOIN, List.of(ns + "pre", ns + "post", ns + "opened", "fy:ip:" + r.id() + ":" + ip + ":" + minute,
                        "fy:net:" + r.id() + ":" + subnet + ":" + minute),
                        List.of(String.valueOf(r.joinsPerIpPerMinute()), "120000"))
                .next().map(l -> new JoinResult(num(l, 0) == -1, num(l, 1) == 0, num(l, 2)));
    }

    /** Joins from a subnet this minute: used to raise proof-of-work difficulty for busy networks. */
    public Mono<Long> subnetJoins(RoomConfig r, String subnet, long minute) {
        return redis.opsForValue().get("fy:net:" + r.id() + ":" + subnet + ":" + minute).map(Long::parseLong).defaultIfEmpty(0L);
    }

    /** Single-use proof-of-work challenges. True the first time an id is used. */
    public Mono<Boolean> consumeChallenge(String id, Duration ttl) {
        return redis.opsForValue().setIfAbsent("fy:pow:" + id, "1", ttl).defaultIfEmpty(false);
    }

    public record Snapshot(boolean opened, long lobbySize, long lobbyJoined, long queueJoined, long served, boolean soldOut, long openedAt) {
        /** Everyone with a position: the frozen lobby plus later joiners. */
        public long total() {
            return (opened ? lobbySize : lobbyJoined) + queueJoined;
        }

        public long waiting() {
            return Math.max(0, total() - served);
        }
    }

    public Mono<Snapshot> snapshot(RoomConfig r) {
        String ns = ns(r);
        return redis.opsForValue().multiGet(List.of(ns + "opened", ns + "preN", ns + "pre", ns + "post", ns + "served", ns + "soldout"))
                .map(v -> new Snapshot(v.get(0) != null, lng(v.get(1)), lng(v.get(2)), lng(v.get(3)), lng(v.get(4)), v.get(5) != null, lng(v.get(0))));
    }

    public record Opening(boolean openedNow, long lobbySize) {
    }

    public Mono<Opening> open(RoomConfig r, long nowMillis) {
        String ns = ns(r);
        return redis.execute(OPEN, List.of(ns + "opened", ns + "pre", ns + "preN"), List.of(String.valueOf(nowMillis)))
                .next().map(l -> new Opening(num(l, 0) == 1, num(l, 1)));
    }

    public record Range(long from, long to) {
    }

    public Mono<Range> admit(RoomConfig r, long n, long nowMillis) {
        String ns = ns(r);
        long minute = nowMillis / 60_000;
        long bucket = nowMillis / BUCKET_MS;
        return redis.execute(ADMIT, List.of(ns + "served", ns + "preN", ns + "post"), List.of(String.valueOf(n)))
                .next().map(l -> new Range(num(l, 0), num(l, 1)))
                .flatMap(range -> range.to() > range.from()
                        ? Mono.when(
                                redis.opsForValue().increment(ns + "adm:" + minute, range.to() - range.from())
                                        .then(redis.expire(ns + "adm:" + minute, Duration.ofMinutes(15))),
                                redis.opsForValue().increment(ns + "adm10:" + bucket, range.to() - range.from())
                                        .then(redis.expire(ns + "adm10:" + bucket, Duration.ofMinutes(5))))
                        .thenReturn(range)
                        : Mono.just(range));
    }

    public record Claim(boolean first, String value) {
    }

    /** @param value what to remember for this visitor (the pass and when it was claimed) */
    public Mono<Claim> claim(RoomConfig r, String visitorId, String passId, String value, long expiresAt, long ttlMillis, long nowMillis) {
        String ns = ns(r);
        return redis.execute(CLAIM, List.of(ns + "claim:" + visitorId, ns + "sessions", ns + "clm:" + (nowMillis / 60_000), ns + "clm10:" + (nowMillis / BUCKET_MS)),
                        List.of(value, passId, String.valueOf(expiresAt), String.valueOf(ttlMillis)))
                .next().map(l -> new Claim(num(l, 0) == 1, String.valueOf(l.get(1))));
    }

    public Mono<String> claimed(RoomConfig r, String visitorId) {
        return redis.opsForValue().get(ns(r) + "claim:" + visitorId);
    }

    public Mono<Long> activeSessions(RoomConfig r, long nowMillis) {
        return redis.execute(ACTIVE, List.of(ns(r) + "sessions"), List.of(String.valueOf(nowMillis))).next();
    }

    /** Ends a session after a purchase. True if this pass had not been completed before. */
    public Mono<Boolean> complete(RoomConfig r, String passId, Duration ttl) {
        String ns = ns(r);
        return redis.opsForZSet().remove(ns + "sessions", passId)
                .then(redis.opsForValue().setIfAbsent(ns + "used:" + passId, "1", ttl)).defaultIfEmpty(false);
    }

    public Mono<Boolean> used(RoomConfig r, String passId) {
        return redis.hasKey(ns(r) + "used:" + passId);
    }

    public Mono<Boolean> markSoldOut(RoomConfig r) {
        return redis.opsForValue().setIfAbsent(ns(r) + "soldout", "1");
    }

    public Mono<Boolean> lease(RoomConfig r, String instanceId, Duration ttl) {
        return redis.execute(LEASE, List.of(ns(r) + "lease"), List.of(instanceId, String.valueOf(ttl.toMillis()))).next().map(v -> v == 1L);
    }

    public Mono<String> leaseHolder(RoomConfig r) {
        return redis.opsForValue().get(ns(r) + "lease");
    }

    /** Fraction of recently admitted people who claimed their pass (1.0 until there is enough data). */
    public Mono<Double> claimRatio(RoomConfig r, long minute) {
        String ns = ns(r);
        // Claims lag admissions by up to a minute, so compare admissions two to six minutes ago with claims one to five.
        List<String> keys = new java.util.ArrayList<>();
        for (long m = minute - 6; m <= minute - 2; m++) keys.add(ns + "adm:" + m);
        for (long m = minute - 5; m <= minute - 1; m++) keys.add(ns + "clm:" + m);
        return redis.opsForValue().multiGet(keys).map(v -> {
            long adm = 0;
            long clm = 0;
            for (int i = 0; i < 5; i++) adm += lng(v.get(i));
            for (int i = 5; i < 10; i++) clm += lng(v.get(i));
            return adm < 30 ? 1.0 : Math.min(1.0, (double) clm / adm);
        });
    }

    /**
     * People admitted recently who haven't claimed their pass yet. They count against the session cap: otherwise, between
     * being admitted and their next poll, the cap would let more people in than it should. Someone near the front polls
     * every few seconds, so only the last CLAIM_WINDOW counts: people (and bots) who never come back stop holding slots
     * quickly. (A 2-minute window let a few dozen unclaimed bot admissions stall a small cap.)
     */
    public Mono<Long> outstanding(RoomConfig r, long nowMillis) {
        String ns = ns(r);
        long now = nowMillis / BUCKET_MS;
        int n = (int) (CLAIM_WINDOW.toMillis() / BUCKET_MS);
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) keys.add(ns + "adm10:" + (now - i));
        for (int i = 0; i < n; i++) keys.add(ns + "clm10:" + (now - i));
        return redis.opsForValue().multiGet(keys).map(v -> {
            long adm = 0;
            long clm = 0;
            for (int i = 0; i < n; i++) adm += lng(v.get(i));
            for (int i = n; i < 2 * n; i++) clm += lng(v.get(i));
            return Math.max(0, adm - clm);
        });
    }

    /** Admissions and claims in the current and previous minute, for the console. */
    public Mono<long[]> lastMinute(RoomConfig r, long minute) {
        String ns = ns(r);
        return redis.opsForValue().multiGet(List.of(ns + "adm:" + minute, ns + "adm:" + (minute - 1), ns + "clm:" + minute, ns + "clm:" + (minute - 1)))
                .map(v -> new long[]{lng(v.get(0)), lng(v.get(1)), lng(v.get(2)), lng(v.get(3))});
    }

    private static long num(List<?> l, int i) {
        Object o = l.get(i);
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    private static long lng(String s) {
        return s == null ? 0 : Long.parseLong(s);
    }
}
