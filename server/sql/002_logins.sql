-- Helpers sign in with a login instead of an e-mail; the owner creates them and sets their passwords.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ADD COLUMN login text;
CREATE UNIQUE INDEX users_login_key ON users (login) WHERE login IS NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_email_or_login CHECK (email IS NOT NULL OR login IS NOT NULL);
