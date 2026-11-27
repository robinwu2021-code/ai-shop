-- NULL identifies older POS requests that only supplied an aggregate manual discount.
ALTER TABLE ord_order ADD COLUMN pos_activity_facts_version INT NULL,
    ADD COLUMN pos_member_no VARCHAR(64) NULL, ADD COLUMN pos_member_at_payment BOOLEAN NULL;
ALTER TABLE pmt_apply ADD COLUMN pos_rule_snapshot TEXT NULL,
    MODIFY COLUMN user_no VARCHAR(64) NULL,
    ADD CONSTRAINT ck_apply_pos_anonymous CHECK (user_no IS NOT NULL OR redeem_mode='POS_ORDER');
CREATE TABLE ord_pos_activity_line (
    tenant_no VARCHAR(32) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    item_id BIGINT NOT NULL,
    apply_no VARCHAR(64) NOT NULL,
    activity_no VARCHAR(64) NOT NULL,
    activity_version_no VARCHAR(64) NOT NULL,
    discount_minor BIGINT NOT NULL,
    PRIMARY KEY (tenant_no, apply_no, item_id),
    UNIQUE KEY uk_pos_activity_line (tenant_no,sub_order_no,activity_no,item_id),
    CHECK (discount_minor > 0)
);
