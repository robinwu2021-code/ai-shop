-- Kuaidi100 limits repeated queries of one number. B precheck and submit reuse one recent result.
CREATE TABLE lgs_precheck_cache (
    carrier VARCHAR(16) NOT NULL,
    waybill_no VARCHAR(64) NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    status VARCHAR(32) NULL,
    reason VARCHAR(255) NULL,
    checked_at BIGINT NOT NULL,
    PRIMARY KEY (carrier, waybill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Short-lived tracking-number precheck result';
