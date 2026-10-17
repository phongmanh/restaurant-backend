-- CR-009 option A (owner, 2026-10-17): a manager's note on a bill correction is cleared 30 days
-- after the correction, like the other free text (orders §6). The amount, method and reason code
-- stay: they are the accounting record.

-- The retention job finds the restaurants with correction notes to clear; cleared rows leave the index.
CREATE INDEX bill_adjustments_notes_ix ON bill_adjustments (restaurant_id, created_at) WHERE note IS NOT NULL;

-- Each restaurant's notes are cleared in its own transaction, as the app role: the note column
-- only, so no other part of a correction can change. The job's platform transaction reads only
-- what it needs to find the restaurants.
GRANT UPDATE (note) ON bill_adjustments TO ${appRole};
GRANT SELECT (restaurant_id, note, created_at) ON bill_adjustments TO ${platformRole};
