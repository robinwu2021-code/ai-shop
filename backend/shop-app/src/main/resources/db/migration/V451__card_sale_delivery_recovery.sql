ALTER TABLE ord_card_sale_snapshot
    ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACKNOWLEDGED，整单终态交付确认',
    ADD CONSTRAINT ck_card_sale_delivery CHECK (delivery_status IN ('PENDING','ACKNOWLEDGED'));
CREATE INDEX ix_card_sale_delivery ON ord_card_sale_snapshot(tenant_no,delivery_status,order_no);
