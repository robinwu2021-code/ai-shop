-- Select the target trade schema explicitly before applying this one-time supplement.
ALTER TABLE ord_payment
    MODIFY COLUMN biz_no VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '挂靠对象的编号',
    ADD COLUMN offline_request_hash CHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '线下记账原请求摘要',
    ADD COLUMN offline_history JSON NULL COMMENT '线下记账创建、更正、作废和退款留痕';
