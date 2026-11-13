-- Security review v0.1 (docs/security-review-v0.1.md), F5/F6.

-- When the user last set a password. Replaces guessing it from accepted invites, so old invites
-- can be deleted by the cleanup job (F6) without losing that fact.
ALTER TABLE app_user ADD COLUMN password_set_at DATETIME(3) NULL;

UPDATE app_user u
SET password_set_at = COALESCE(u.updated_at, u.created_at)
WHERE u.status = 'ACTIVE'
   OR EXISTS (SELECT 1 FROM user_invite i WHERE i.user_id = u.id AND i.used_at IS NOT NULL)
   OR (u.status IN ('DISABLED', 'LOCKED') AND NOT EXISTS (SELECT 1 FROM user_invite i WHERE i.user_id = u.id));

-- The nightly cleanup deletes by these columns.
CREATE INDEX ix_rt_expires ON refresh_token (expires_at);
CREATE INDEX ix_la_time ON login_attempt (created_at);
CREATE INDEX ix_session_seen ON user_session (last_seen_at);
CREATE INDEX ix_invite_expires ON user_invite (expires_at);
