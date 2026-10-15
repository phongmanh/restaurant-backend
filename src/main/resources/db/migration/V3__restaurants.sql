-- The restaurants feature (restaurants §2, §7, §9). `restaurants` itself was created by V2, because
-- staff_accounts references it.

-- The audit log read: one restaurant's chain (or the platform's, restaurant_id IS NULL), newest
-- first, which the cursor follows (§9).
CREATE INDEX audit_events_read_ix ON audit_events (restaurant_id, occurred_at DESC, id DESC);

-- A manager edits their restaurant's profile, invites staff and issues their links in the
-- restaurant's own transaction, as `app` (§4.2, §7). Admin work on one restaurant runs the same way;
-- onboarding, the platform admins and privacy requests use ${platformRole}, which V2 already covers.
GRANT UPDATE ON restaurants TO ${appRole};
GRANT INSERT ON staff_accounts, staff_link_tokens TO ${appRole};

-- An admin's lost authenticator ends their sessions with its own reason (restaurants §8).
ALTER TABLE staff_sessions DROP CONSTRAINT staff_sessions_revoke_reason_check;
ALTER TABLE staff_sessions ADD CONSTRAINT staff_sessions_revoke_reason_check
    CHECK (revoke_reason IN ('sign_out', 'refresh_reuse', 'password_changed', 'password_reset', 'role_changed', 'deactivated',
                             'totp_reset'));
