-- 业务取消意图与渠道关闭事实分开：查询/关闭未知时仍需识别后续迟到支付。
ALTER TABLE pay_order ADD COLUMN close_requested_at DATETIME(3) NULL COMMENT '不可撤销的关闭意图；渠道确认关闭前不代表 CLOSED';
