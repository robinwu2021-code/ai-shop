CREATE TABLE mch_pos_staff_pin_policy (
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_no, entity_no)
);
