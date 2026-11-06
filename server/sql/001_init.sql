-- «КФХ — Учёт»: the shared database of every farm. One generic table of synced records, versioned per field,
-- so that iPhone, Android and the site use one sync protocol for every module.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text NOT NULL UNIQUE,
    name          text NOT NULL DEFAULT '',
    password_hash text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS farms (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text NOT NULL,
    region       text NOT NULL DEFAULT 'Приаргунский район, Забайкальский край, Россия',
    manager_name text NOT NULL DEFAULT '',
    timezone     text NOT NULL DEFAULT 'Asia/Chita',
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- owner: everything, members and devices; manager: every module; timesheet / coal / livestock: that module only;
-- viewer: reads everything, changes nothing.
CREATE TABLE IF NOT EXISTS memberships (
    farm_id    uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       text NOT NULL CHECK (role IN ('owner', 'manager', 'timesheet', 'coal', 'livestock', 'viewer')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (farm_id, user_id)
);

-- A signed-in device. The token is shown once; only its SHA-256 is stored. Revoking stops it at once.
CREATE TABLE IF NOT EXISTS devices (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id      uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         text NOT NULL DEFAULT '',
    platform     text NOT NULL DEFAULT '' CHECK (platform IN ('', 'ios', 'android', 'web', 'test')),
    token_hash   text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    revoked_at   timestamptz
);

CREATE TABLE IF NOT EXISTS invites (
    code       text PRIMARY KEY,
    farm_id    uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    role       text NOT NULL,
    created_by uuid NOT NULL REFERENCES users(id),
    expires_at timestamptz NOT NULL,
    used_by    uuid REFERENCES users(id),
    used_at    timestamptz
);

CREATE TABLE IF NOT EXISTS password_resets (
    code_hash  text PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);

-- Every change gets the next number: a device pulls everything after the last number it has seen.
CREATE SEQUENCE IF NOT EXISTS change_seq;

CREATE TABLE IF NOT EXISTS records (
    farm_id        uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    entity         text NOT NULL,
    id             uuid NOT NULL,
    version        integer NOT NULL,
    data           jsonb NOT NULL,
    -- The record version at which each field last changed: the base of field-level merging and conflicts.
    field_versions jsonb NOT NULL,
    deleted        boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by_device uuid,
    updated_by_device uuid,
    seq            bigint NOT NULL,
    PRIMARY KEY (farm_id, entity, id)
);
CREATE INDEX IF NOT EXISTS records_farm_seq ON records (farm_id, seq);

-- Each pushed operation once: a repeated push gets the stored result back and changes nothing.
CREATE TABLE IF NOT EXISTS processed_ops (
    farm_id    uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    op_id      uuid NOT NULL,
    result     jsonb NOT NULL,
    device_id  uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (farm_id, op_id)
);

-- One field changed on two devices: both values are kept until the owner chooses.
CREATE TABLE IF NOT EXISTS conflicts (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id       uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    entity        text NOT NULL,
    record_id     uuid NOT NULL,
    field         text NOT NULL,
    server_value  jsonb,
    client_value  jsonb,
    base_version  integer NOT NULL,
    device_id     uuid,
    op_id         uuid NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    resolved_at   timestamptz,
    resolution    text CHECK (resolution IN ('server', 'client'))
);
CREATE INDEX IF NOT EXISTS conflicts_open ON conflicts (farm_id) WHERE resolved_at IS NULL;

CREATE TABLE IF NOT EXISTS files (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id      uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    sha256       text NOT NULL,
    size         bigint NOT NULL,
    content_type text NOT NULL,
    name         text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    uploaded_by  uuid,
    UNIQUE (farm_id, sha256)
);

CREATE TABLE IF NOT EXISTS backups (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id    uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    sha256     text NOT NULL,
    records    integer NOT NULL,
    size       bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         bigserial PRIMARY KEY,
    farm_id    uuid,
    user_id    uuid,
    device_id  uuid,
    action     text NOT NULL,
    details    jsonb NOT NULL DEFAULT '{}',
    at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS audit_farm ON audit_log (farm_id, at);
