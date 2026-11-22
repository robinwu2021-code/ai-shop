-- 上报期限告警（TDD-发票模块 §14）：每条上传任务记下法定期限与预警时点；巡检把未送达的任务按级别告警一次。
-- 台湾：B2C 开立 / 作废 / 折让后 48 小时内上传；B2B 7 日。预警时点 = 期限窗口过半。
-- store_no：B 端按门店看积压用（任务原先只记主体）。

ALTER TABLE ivc_transmission
  ADD COLUMN store_no VARCHAR(64) NULL COMMENT '门店（开立门店）',
  ADD COLUMN report_warn_at DATETIME(3) NULL COMMENT '预警时点（UTC）',
  ADD COLUMN report_deadline_at DATETIME(3) NULL COMMENT '法定上报期限（UTC）',
  ADD COLUMN deadline_level TINYINT NOT NULL DEFAULT 0 COMMENT '已告警级别：0 未告警 / 1 已预警 / 2 已逾期';

-- 存量回填：门店取票据；期限按开票当时的买受人类型（B2B 7 日，其余 48 小时）从任务创建时算
UPDATE ivc_transmission t JOIN ivc_invoice i ON i.tenant_no = t.tenant_no AND i.invoice_no = t.ref_no
   SET t.store_no = i.store_no,
       t.report_deadline_at = t.created_at + INTERVAL (CASE i.buyer_type WHEN 'B2B' THEN 168 ELSE 48 END) HOUR,
       t.report_warn_at = t.created_at + INTERVAL (CASE i.buyer_type WHEN 'B2B' THEN 84 ELSE 24 END) HOUR
 WHERE t.message_type IN ('ISSUE', 'VOID');

UPDATE ivc_transmission t JOIN ivc_allowance a ON a.tenant_no = t.tenant_no AND a.allowance_no = t.ref_no
   JOIN ivc_invoice i ON i.tenant_no = a.tenant_no AND i.invoice_no = a.invoice_no
   SET t.store_no = a.store_no,
       t.report_deadline_at = t.created_at + INTERVAL (CASE i.buyer_type WHEN 'B2B' THEN 168 ELSE 48 END) HOUR,
       t.report_warn_at = t.created_at + INTERVAL (CASE i.buyer_type WHEN 'B2B' THEN 84 ELSE 24 END) HOUR
 WHERE t.message_type IN ('ALLOWANCE', 'ALLOWANCE_VOID');

-- 已送达的存量不再参与告警
UPDATE ivc_transmission SET deadline_level = 2 WHERE status = 'SENT';

CREATE INDEX idx_ivc_transmission_deadline ON ivc_transmission (deadline_level, report_warn_at);
CREATE INDEX idx_ivc_transmission_store ON ivc_transmission (tenant_no, entity_no, store_no, status);
