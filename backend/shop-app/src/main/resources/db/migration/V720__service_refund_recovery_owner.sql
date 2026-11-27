CREATE TABLE ord_service_refund_recovery_owner (
    tenant_no VARCHAR(64) NOT NULL,
    scan_owner VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_no),
    CONSTRAINT ck_service_refund_scan_owner CHECK (scan_owner IN ('PAY','PAUSED','TARGET'))
) ENGINE=InnoDB COMMENT='交易侧储值退款恢复任务交接';

INSERT INTO ord_service_refund_recovery_owner (tenant_no, scan_owner) VALUES ('MAIN', 'PAY');
