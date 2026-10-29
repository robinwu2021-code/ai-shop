-- Nullable to preserve the interpretation and hashes of all historical publications.
-- The explicit mode is not exposed until every order path can honor it.
ALTER TABLE fnb_modifier ADD COLUMN inventory_mode VARCHAR(16) NULL;
