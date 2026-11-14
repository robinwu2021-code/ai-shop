-- MAIN and additive modifiers are not fictitious bundle choices. Existing rows remain BUNDLE.
ALTER TABLE fnb_order_component
    MODIFY COLUMN choice_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN component_kind VARCHAR(24) NOT NULL DEFAULT 'BUNDLE',
    ADD COLUMN source_component_ref VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
-- Original specification value identifiers are already permitted up to 64 characters by the input contract.
ALTER TABLE fnb_order_modifier
    MODIFY COLUMN source_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
