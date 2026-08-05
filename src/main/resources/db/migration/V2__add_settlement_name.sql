-- =============================================================================
-- V2 - human-readable settlement name
--
-- `name` is a display label, NOT a key. It is nullable, not unique, and nothing
-- in the settlement path branches on it. Identity is still payments.id, and the
-- dedupe key presented to the downstream is still settlement_outbox.settlement_key.
--
-- It is carried forward BY VALUE into settlement_outbox and mock_settlements
-- rather than joined back to payments, matching how amount_minor and currency
-- are already duplicated there: an outbox row is a self-contained settlement
-- instruction, and the provider's ledger is conceptually on the far side of a
-- network and cannot join against our tables at all.
--
-- The CHECK permits NULL (name omitted) but rejects the empty string, so
-- "absent" has exactly one representation. The service normalizes blank to NULL
-- before insert.
-- =============================================================================

ALTER TABLE payments
    ADD COLUMN name TEXT
        CHECK (name IS NULL OR length(name) BETWEEN 1 AND 120);

ALTER TABLE settlement_outbox
    ADD COLUMN name TEXT;

ALTER TABLE mock_settlements
    ADD COLUMN name TEXT;
