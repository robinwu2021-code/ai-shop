-- Optional trusted checkout facts. Existing service authorizations and legacy intents remain compatible.
ALTER TABLE ord_ent_authorization
    ADD COLUMN order_use_snapshot JSON NULL COMMENT '真实订单源、报价与选定券，仅用于原操作恢复';
