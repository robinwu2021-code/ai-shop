-- 外部记账关联业务单号（TDD-业务单收款汇总 AC1/AC3）：餐饮结算单、美业工单等插件单据号。
--
-- 与 order_no 分开：order_no 必须是本店商城子单（已拍板 D3，订单详情据此判断「已有外部记录」）；
-- biz_no 只是门店内的引用，不校验来源单据、不改其状态，只供「已收 / 还差」汇总按单号精确查。
-- 带 DDL：166 应用库账号无改表权限，需 root 先执行（或部署时临时授权）。

ALTER TABLE ord_offline_record ADD COLUMN biz_no VARCHAR(64) COLLATE utf8mb4_bin NULL AFTER order_no;
CREATE INDEX idx_offline_record_biz ON ord_offline_record (tenant_no, store_no, biz_no);
