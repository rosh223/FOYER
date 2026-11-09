package dev.foyer.contracts;

import java.time.Instant;
import java.util.List;

/**
 * Everything queue-service needs to run a room, pushed as a snapshot on every change.
 *
 * @param originUrl        where admitted visitors are sent (with their pass)
 * @param ratePerMinute    target admissions per minute
 * @param maxActive        cap on visitors holding a live pass at once (0 = no cap)
 * @param passTtlSeconds   how long a pass stays valid
 * @param lobbyOpensAt     from this moment people can join the lobby
 * @param opensAt          the lottery runs and admissions start
 * @param desiredState     operator intent: SCHEDULED (follow the clock), PAUSED or CLOSED
 * @param powDifficulty    base proof-of-work difficulty in leading zero bits
 * @param joinsPerIpPerMinute join rate limit per IP address (NAT and campus networks need generous values)
 * @param seed             the lottery seed (hex); internal, revealed publicly only after opening
 * @param seedCommitment   SHA-256 of the seed, public from the start
 * @param originKeyHashes  SHA-256 of the origin API keys allowed to report purchases
 * @param epoch            bumped when an operator resets the room; queue state is namespaced by it
 */
public record RoomConfig(
        String id,
        String slug,
        String name,
        String venue,
        String description,
        String originUrl,
        int ratePerMinute,
        int maxActive,
        int passTtlSeconds,
        Instant lobbyOpensAt,
        Instant opensAt,
        String desiredState,
        int powDifficulty,
        int joinsPerIpPerMinute,
        String seed,
        String seedCommitment,
        List<String> originKeyHashes,
        int epoch,
        long version,
        Instant updatedAt) {
}
