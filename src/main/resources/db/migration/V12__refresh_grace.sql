-- A short grace period for a refresh token that was just used (core-and-auth §8.3, CR-017).
-- A web app reloaded mid-refresh sends the old token again; within the window it gets a fresh
-- sibling instead of ending the session. All three columns are additive: the running version
-- ignores them, and a row without a parent simply has no siblings.
ALTER TABLE staff_refresh_tokens
    ADD COLUMN parent_hash   bytea,                          -- the token this one replaced; NULL for a sign-in's first
    ADD COLUMN grace_swaps   smallint NOT NULL DEFAULT 0,    -- grace swaps taken; set to the cap once a child is used
    ADD COLUMN superseded_at timestamptz;                    -- a sibling was used first, so this one is reuse

CREATE INDEX staff_refresh_tokens_parent_ix ON staff_refresh_tokens (parent_hash) WHERE parent_hash IS NOT NULL;
