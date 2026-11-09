-- Foyer insight: every queue event, and what the console reads.

CREATE TABLE events (
    id         UUID PRIMARY KEY,
    room_id    TEXT        NOT NULL,
    epoch      INT         NOT NULL,
    type       TEXT        NOT NULL,
    visitor_id TEXT,
    kind       TEXT,
    idx        BIGINT,
    position   BIGINT,
    waited_ms  BIGINT,
    reason     TEXT,
    range_from BIGINT,
    range_to   BIGINT,
    difficulty INT,
    subnet     TEXT,
    pass_id    TEXT,
    detail     TEXT,
    at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX events_room_at ON events (room_id, epoch, at);
CREATE INDEX events_room_type ON events (room_id, epoch, type);

CREATE TABLE room_minute (
    room_id        TEXT        NOT NULL,
    epoch          INT         NOT NULL,
    minute         TIMESTAMPTZ NOT NULL,
    joins          BIGINT      NOT NULL DEFAULT 0,
    lobby_joins    BIGINT      NOT NULL DEFAULT 0,
    rejected_rate  BIGINT      NOT NULL DEFAULT 0,
    rejected_pow   BIGINT      NOT NULL DEFAULT 0,
    rejected_other BIGINT      NOT NULL DEFAULT 0,
    admitted       BIGINT      NOT NULL DEFAULT 0,
    claimed        BIGINT      NOT NULL DEFAULT 0,
    completed      BIGINT      NOT NULL DEFAULT 0,
    purchases      BIGINT      NOT NULL DEFAULT 0,
    wait_sum_ms    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (room_id, epoch, minute)
);

-- One row per lobby member, filled in as they join and when they claim: the raw material of the fairness report.
CREATE TABLE lobby_entries (
    room_id    TEXT        NOT NULL,
    epoch      INT         NOT NULL,
    visitor_id TEXT        NOT NULL,
    idx        BIGINT,
    joined_at  TIMESTAMPTZ,
    position   BIGINT,
    claimed_at TIMESTAMPTZ,
    waited_ms  BIGINT,
    PRIMARY KEY (room_id, epoch, visitor_id)
);

CREATE TABLE sales (
    room_id     TEXT        NOT NULL,
    epoch       INT         NOT NULL,
    first_join  TIMESTAMPTZ,
    opened_at   TIMESTAMPTZ,
    lobby_size  BIGINT,
    seed        TEXT,
    sold_out_at TIMESTAMPTZ,
    PRIMARY KEY (room_id, epoch)
);
