-- Existing cash commands used fixed SPL-{settleNo} and net_minor. Never resend them under a new key.
-- Newly generated bills explicitly use version 1 and their immutable split_amount_minor snapshot.
ALTER TABLE stl_bill ADD COLUMN split_protocol_version INT NOT NULL DEFAULT 0;
