CREATE TABLE shows (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id         BIGINT      NOT NULL REFERENCES shows (id),
    user_id         TEXT        NOT NULL,
    seats           TEXT[]      NOT NULL,
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status          TEXT        NOT NULL CHECK (status IN ('pending', 'confirmed', 'cancelled')),
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key)
);

CREATE TABLE seats (
    show_id        BIGINT NOT NULL REFERENCES shows (id),
    label          TEXT   NOT NULL,
    status         TEXT   NOT NULL DEFAULT 'available' CHECK (status IN ('available', 'confirmed')),
    user_id        TEXT,
    reservation_id UUID   REFERENCES reservations (id),
    PRIMARY KEY (show_id, label),
    -- a seat is either fully free or fully owned; no half states
    CHECK (
        (status = 'available' AND user_id IS NULL     AND reservation_id IS NULL) OR
        (status = 'confirmed' AND user_id IS NOT NULL AND reservation_id IS NOT NULL)
    )
);

CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE user_show_quota (
    show_id BIGINT NOT NULL REFERENCES shows (id),
    user_id TEXT   NOT NULL,
    used    INT    NOT NULL DEFAULT 0 CHECK (used >= 0),
    PRIMARY KEY (show_id, user_id)
);