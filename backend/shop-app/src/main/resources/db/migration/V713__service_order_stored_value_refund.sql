ALTER TABLE ord_ent_service_order
    ADD COLUMN refund_status VARCHAR(24) NULL COMMENT '纯储值全额退款进度',
    ADD COLUMN refund_actor_no VARCHAR(64) NULL COMMENT '发起退款的经办人',
    ADD COLUMN refund_command_snapshot JSON NULL COMMENT '原使用、全额、退款源及决定',
    ADD INDEX ix_service_refund_recover (tenant_no,refund_status,id),
    ADD CONSTRAINT ck_service_order_refund CHECK (
        (refund_status IS NULL AND refund_actor_no IS NULL AND refund_command_snapshot IS NULL) OR
        (refund_status IS NOT NULL AND refund_status IN ('REFUND_PENDING','REFUNDED')
            AND status='COMMITTED' AND refund_actor_no IS NOT NULL AND refund_command_snapshot IS NOT NULL)
    );
