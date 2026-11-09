package dev.foyer.queue.rooms;

import dev.foyer.contracts.RoomConfig;

import java.time.Instant;

/** Where a room is in its life, from the schedule, the operator's intent and runtime flags. */
public enum Phase {
    /** Before the lobby opens: nobody can join yet. */
    SCHEDULED,
    /** Joining the lobby; places are decided by lottery at opening. */
    LOBBY,
    /** Admitting people. */
    OPEN,
    /** Joining continues, admissions are on hold. */
    PAUSED,
    /** The origin reported it has nothing left. */
    SOLD_OUT,
    /** Closed by the operator. */
    CLOSED;

    public static Phase of(RoomConfig r, Instant now, boolean soldOut) {
        if ("CLOSED".equals(r.desiredState())) return CLOSED;
        if (soldOut) return SOLD_OUT;
        if (now.isBefore(r.lobbyOpensAt())) return SCHEDULED;
        if (now.isBefore(r.opensAt())) return LOBBY;
        return "PAUSED".equals(r.desiredState()) ? PAUSED : OPEN;
    }

    public boolean joinable() {
        return this == LOBBY || this == OPEN || this == PAUSED;
    }
}
