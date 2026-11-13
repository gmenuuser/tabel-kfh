-- Open apps: a phone registers itself as an «app device» (no account, no password); the owner manages devices on the site.
-- Safe to run again: every step is a no-op the second time.
ALTER TABLE farms ADD COLUMN IF NOT EXISTS new_devices_allowed boolean NOT NULL DEFAULT true;

-- A device without a user is an app device. When a user is deleted their (already revoked) devices stay.
ALTER TABLE devices ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE devices DROP CONSTRAINT IF EXISTS devices_user_id_fkey;
ALTER TABLE devices ADD CONSTRAINT devices_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

-- Helpers' site sessions end; the owner's stay.
UPDATE devices d SET revoked_at = now()
 WHERE d.platform = 'web' AND d.revoked_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = d.user_id AND m.role = 'owner');

-- Phones keep their tokens and become app devices; a helper's phone is named after the helper so the owner can tell them apart.
UPDATE devices d
   SET name = CASE WHEN EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = u.id AND m.role = 'owner') THEN d.name
                   ELSE d.name || ' — ' || COALESCE(NULLIF(u.name, ''), u.login, u.email) END,
       user_id = NULL
  FROM users u
 WHERE u.id = d.user_id AND d.revoked_at IS NULL AND d.platform IN ('ios', 'android');

DELETE FROM invites;
DELETE FROM users u WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = u.id AND m.role = 'owner');
