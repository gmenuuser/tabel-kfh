-- Ключи доступа: админ выдаёт человеку 4-значный код; на первом вводе сервер заводит человека и его устройство.
-- Человек из ключа не имеет ни почты, ни логина, ни пароля: вход в приложение — только кодом.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_or_login;
ALTER TABLE users ADD CONSTRAINT users_email_or_login
    CHECK (email IS NOT NULL OR login IS NOT NULL OR name <> '');

-- role: то же, что у помощников; «owner» ключом не выдаётся никогда — телефон не меняет настройки хозяйства.
CREATE TABLE IF NOT EXISTS access_keys (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id     uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    person_name text NOT NULL,
    role        text NOT NULL CHECK (role IN ('manager', 'timesheet', 'coal', 'livestock', 'viewer')),
    code_hash   text NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    used_at     timestamptz,
    user_id     uuid REFERENCES users(id) ON DELETE CASCADE,
    revoked_at  timestamptz
);

-- One live code per farm: the same 4 digits cannot be used twice at the same time.
CREATE UNIQUE INDEX IF NOT EXISTS access_keys_active ON access_keys (farm_id, code_hash)
    WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS access_keys_farm ON access_keys (farm_id, created_at DESC);
