-- Foyer room-service: operators, rooms and their history.

CREATE TABLE operators (
    id            TEXT PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE,
    name          TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rooms (
    id                      TEXT PRIMARY KEY,
    slug                    TEXT        NOT NULL UNIQUE,
    name                    TEXT        NOT NULL,
    venue                   TEXT        NOT NULL DEFAULT '',
    description             TEXT        NOT NULL DEFAULT '',
    origin_url              TEXT        NOT NULL,
    rate_per_minute         INT         NOT NULL CHECK (rate_per_minute BETWEEN 1 AND 1000000),
    max_active              INT         NOT NULL DEFAULT 0 CHECK (max_active >= 0),
    pass_ttl_seconds        INT         NOT NULL DEFAULT 600 CHECK (pass_ttl_seconds BETWEEN 30 AND 86400),
    lobby_opens_at          TIMESTAMPTZ NOT NULL,
    opens_at                TIMESTAMPTZ NOT NULL,
    desired_state           TEXT        NOT NULL DEFAULT 'SCHEDULED' CHECK (desired_state IN ('SCHEDULED', 'PAUSED', 'CLOSED')),
    pow_difficulty          INT         NOT NULL DEFAULT 16 CHECK (pow_difficulty BETWEEN 0 AND 28),
    joins_per_ip_per_minute INT         NOT NULL DEFAULT 120 CHECK (joins_per_ip_per_minute >= 1),
    -- The lottery seed stays private until the room opens; its SHA-256 is public from the start.
    seed                    TEXT        NOT NULL,
    seed_commitment         TEXT        NOT NULL,
    epoch                   INT         NOT NULL DEFAULT 1,
    version                 BIGINT      NOT NULL DEFAULT 1,
    created_by              TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (lobby_opens_at <= opens_at)
);

-- Every seed a room has used, so a past lottery stays verifiable after a reset.
CREATE TABLE room_epochs (
    room_id         TEXT        NOT NULL REFERENCES rooms (id),
    epoch           INT         NOT NULL,
    seed            TEXT        NOT NULL,
    seed_commitment TEXT        NOT NULL,
    opens_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (room_id, epoch)
);

-- Keys that origin sites use to report purchases and sell-outs. Stored as SHA-256 only.
CREATE TABLE origin_keys (
    id         TEXT PRIMARY KEY,
    room_id    TEXT        NOT NULL REFERENCES rooms (id),
    key_hash   TEXT        NOT NULL UNIQUE,
    last4      TEXT        NOT NULL,
    label      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE TABLE admin_audit (
    id     BIGSERIAL PRIMARY KEY,
    actor  TEXT        NOT NULL,
    action TEXT        NOT NULL,
    target TEXT,
    detail TEXT,
    at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
