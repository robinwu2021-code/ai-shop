CREATE TABLE inv_refund_restore (
    id BIGINT NOT NULL AUTO_INCREMENT,
    purpose_no VARCHAR(64) NOT NULL,
    original_line_id BIGINT NOT NULL,
    qty INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_refund_restore_purpose_line (purpose_no, original_line_id),
    KEY idx_inv_refund_restore_original (original_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原出库行退款恢复数量声明，与退货入库同事务';
