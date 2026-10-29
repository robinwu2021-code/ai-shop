-- 进销存独立服务 · 盘点冻结、SN 实盘与复核（TDD-进销存独立服务与多业态 §2.10，M5 T5.1）
--
-- 盘点单本身就是持久化的冻结：状态在库里，进程重启照样在。冻结范围 = 盘点行上的物料。
-- 宿主盘点多了「提交 → 复核 → 过账」三步；批准绑定内容版本，驳回后再改，旧的批准自动失效。

ALTER TABLE inv_stock_count ADD COLUMN content_version INT NOT NULL DEFAULT 0 COMMENT '录数 / 扫码每改一次 +1';
ALTER TABLE inv_stock_count ADD COLUMN approved_version INT DEFAULT NULL COMMENT '批准时的内容版本；与当前不等 = 批准失效';
ALTER TABLE inv_stock_count ADD COLUMN submitted_by VARCHAR(64) DEFAULT NULL;
ALTER TABLE inv_stock_count ADD COLUMN reviewer VARCHAR(64) DEFAULT NULL COMMENT '复核人；档案要求时必须不同于提交人';
ALTER TABLE inv_stock_count ADD COLUMN review_note VARCHAR(255) DEFAULT NULL;
ALTER TABLE inv_stock_count ADD COLUMN profile_version INT DEFAULT NULL;

-- SN 实扫：可多次、多人；同一 SN 重扫不重计。未知 / 已出库的 SN 只列为异常，不自动当盘盈
CREATE TABLE inv_count_scan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    count_no VARCHAR(32) NOT NULL,
    serial_no VARCHAR(64) NOT NULL,
    serial_id VARCHAR(32) DEFAULT NULL,
    item_id VARCHAR(32) DEFAULT NULL,
    verdict VARCHAR(16) NOT NULL COMMENT 'IN_SCOPE 在范围内在仓 / OUT_OF_SCOPE 在仓但不在范围 / NOT_IN_STOCK 档案有但不在仓（疑似未登记退货）/ UNKNOWN 没有档案',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_count_scan (owner_id, count_no, serial_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点 SN 实扫';
