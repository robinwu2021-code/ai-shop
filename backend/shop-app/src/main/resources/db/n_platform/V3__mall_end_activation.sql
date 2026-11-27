-- Apply to n_platform after its V1 and V2. The default application Flyway location is db/migration.
ALTER TABLE mch_store_end_grant
    ADD COLUMN client_id VARCHAR(128) NULL COMMENT '开通时绑定的客户端',
    MODIFY operation_ref_no VARCHAR(64) COMMENT '商城订单或运营操作号',
    MODIFY end_code VARCHAR(64) COMMENT '业务端编码',
    MODIFY entitlement_code VARCHAR(64) COMMENT '权益编码',
    MODIFY created_by VARCHAR(64) COMMENT '创建人',
    MODIFY updated_by VARCHAR(64) COMMENT '更新人',
    ADD KEY idx_operation (tenant_no,operation_ref_no),
    DROP INDEX uk_0,
    ADD UNIQUE KEY uk_0 (tenant_no,grant_no),
    DROP INDEX uk_1,
    ADD UNIQUE KEY uk_1 (tenant_no,period_no,entitlement_code,schedule_revision,segment_seq);
