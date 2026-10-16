-- Publishing, catch-up and purge of the event log (realtime §4.2–4.3, §6, §10; step 8).
-- The purge job (platform role) deletes events older than 7 days; their outbox rows go with them
-- through the foreign key's ON DELETE CASCADE.
GRANT DELETE ON domain_events TO ${platformRole};
