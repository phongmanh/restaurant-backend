-- Staff and admin sign-in (core-and-auth §9). All of these tables are global: no RLS, because
-- sign-in finds an account by email before the restaurant is known and platform admins belong to
-- no restaurant (§16 Q1). Isolation for them is the repositories' restaurant_id filter (IT-CORE-07).

-- The tenant registry, as restaurants §2 defines it. It's created here, ahead of the restaurants
-- feature (step 4), because staff_accounts references it and every staff call reads its status
-- (§6). Global, without RLS (restaurants §15 Q2).
CREATE TABLE restaurants (
    id                   uuid PRIMARY KEY,                    -- UUID v7; the tenant ID everywhere
    name                 text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    address              text NOT NULL CHECK (char_length(address) BETWEEN 1 AND 300),
    time_zone            text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh', -- IANA ID, checked by the service
    currency             text NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    languages            text[] NOT NULL DEFAULT '{vi}',      -- 'vi' always included (FR-024 fallback)
    opening_hours        jsonb NOT NULL DEFAULT '{}',         -- restaurants §4.3
    owner_contact_name   text,                                -- personal data (requirements §5); admins only
    owner_contact_email  text,
    owner_contact_phone  text,
    status               text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
    suspended_at         timestamptz,
    suspension_reason    text CHECK (char_length(suspension_reason) <= 500), -- admins only
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              integer NOT NULL DEFAULT 0,
    CHECK ('vi' = ANY (languages)),
    CHECK ((status = 'suspended') = (suspended_at IS NOT NULL))
);
CREATE INDEX restaurants_status_ix ON restaurants (status, name);

-- One row per staff member or platform admin.
CREATE TABLE staff_accounts (
    id                    uuid PRIMARY KEY,                   -- UUID v7
    email                 text NOT NULL CHECK (char_length(email) <= 254), -- trimmed, lower-cased
    display_name          text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
    phone                 text,                               -- optional (requirements §5)
    restaurant_id         uuid REFERENCES restaurants (id),   -- NULL only for platform admins
    role                  text CHECK (role IN ('manager', 'waiter', 'kitchen')),
    is_platform_admin     boolean NOT NULL DEFAULT false,
    status                text NOT NULL CHECK (status IN ('invited', 'active', 'deactivated')),
    password_hash         text,                               -- PHC string; NULL until the invitation is accepted
    password_changed_at   timestamptz,
    totp_secret           bytea,                              -- AES-256-GCM: key id ‖ nonce ‖ ciphertext ‖ tag
    totp_pending_secret   bytea,                              -- during enrolment only
    totp_last_step        bigint,
    failed_count          integer NOT NULL DEFAULT 0,
    failed_window_start   timestamptz,
    locked_until          timestamptz,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    deactivated_at        timestamptz,
    anonymised_at         timestamptz,                        -- retention: 30 days after deactivation
    version               integer NOT NULL DEFAULT 0,
    CHECK ((is_platform_admin AND restaurant_id IS NULL AND role IS NULL)
        OR (NOT is_platform_admin AND restaurant_id IS NOT NULL AND role IS NOT NULL))
);
CREATE UNIQUE INDEX staff_accounts_email_uq ON staff_accounts (email);
CREATE INDEX staff_accounts_restaurant_ix ON staff_accounts (restaurant_id, status);

-- One row per sign-in; id = the access token's sid.
CREATE TABLE staff_sessions (
    id             uuid PRIMARY KEY,
    staff_id       uuid NOT NULL REFERENCES staff_accounts (id),
    audience       text NOT NULL CHECK (audience IN ('staff', 'admin')),
    amr            text[] NOT NULL,
    started_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,                      -- started_at + 24 h
    revoked_at     timestamptz,
    revoke_reason  text CHECK (revoke_reason IN ('sign_out', 'refresh_reuse', 'password_changed',
                                                 'password_reset', 'role_changed', 'deactivated'))
);
CREATE INDEX staff_sessions_live_ix ON staff_sessions (staff_id) WHERE revoked_at IS NULL;

CREATE TABLE staff_refresh_tokens (
    token_hash       bytea PRIMARY KEY,                       -- SHA-256 of the 256-bit token
    session_id       uuid NOT NULL REFERENCES staff_sessions (id) ON DELETE CASCADE,
    issued_at        timestamptz NOT NULL,
    idle_expires_at  timestamptz NOT NULL,                    -- min(issued_at + 12 h, session expires_at)
    used_at          timestamptz
);
CREATE INDEX staff_refresh_tokens_session_ix ON staff_refresh_tokens (session_id);

CREATE TABLE staff_mfa_challenges (
    token_hash  bytea PRIMARY KEY,
    staff_id    uuid NOT NULL REFERENCES staff_accounts (id),
    expires_at  timestamptz NOT NULL,                         -- + 5 min
    used_at     timestamptz
);

CREATE TABLE staff_link_tokens (
    token_hash     bytea PRIMARY KEY,
    staff_id       uuid NOT NULL REFERENCES staff_accounts (id),
    kind           text NOT NULL CHECK (kind IN ('invitation', 'password_reset')),
    created_by     uuid NOT NULL REFERENCES staff_accounts (id),
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,                      -- + 72 h / + 24 h
    used_at        timestamptz,
    superseded_at  timestamptz                                -- set when a newer link is issued
);
CREATE INDEX staff_link_tokens_staff_ix ON staff_link_tokens (staff_id, kind) WHERE used_at IS NULL AND superseded_at IS NULL;

-- What this step's code does with each role. `app` serves every staff call (the caller lookup) and
-- a restaurant's own staff's sign-in steps, in that restaurant's transaction; `app_platform` serves
-- the steps that write the platform chain (unknown emails, admins, sign-out, refresh reuse).
-- The restaurants feature (step 4) grants what staff management adds; the purge job adds DELETE.
GRANT SELECT ON restaurants TO ${appRole};
GRANT SELECT, INSERT, UPDATE ON restaurants TO ${platformRole};
GRANT SELECT, UPDATE ON staff_accounts TO ${appRole};
GRANT SELECT, INSERT, UPDATE ON staff_accounts TO ${platformRole};
GRANT SELECT, INSERT, UPDATE ON staff_sessions, staff_refresh_tokens TO ${appRole}, ${platformRole};
GRANT SELECT, UPDATE ON staff_link_tokens TO ${appRole};
GRANT SELECT, INSERT, UPDATE ON staff_link_tokens TO ${platformRole};
GRANT SELECT, INSERT, UPDATE ON staff_mfa_challenges TO ${platformRole};
