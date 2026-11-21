-- 载具优先（TDD-发票模块 §8）：发票记下平台买家，C 端「我的发票」与会员载具查询按它查；买家常用载具。

ALTER TABLE ivc_invoice
  ADD COLUMN buyer_user_no VARCHAR(64) NULL COMMENT '平台买家用户号；门店现场无账号的单为空',
  ADD KEY idx_ivc_invoice_buyer (tenant_no, buyer_user_no, issued_at);

CREATE TABLE ivc_user_carrier (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  carrier_no VARCHAR(32) NOT NULL,
  tenant_no VARCHAR(32) NOT NULL,
  user_no VARCHAR(64) NOT NULL,
  carrier_type VARCHAR(16) NOT NULL COMMENT '3J0002 手机条码 / CQ0001 自然人凭证',
  carrier_id VARCHAR(64) NOT NULL,
  label VARCHAR(32) NULL COMMENT '买家自己起的名字，如「我的手机」',
  is_default TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_user_carrier_no (tenant_no, carrier_no),
  UNIQUE KEY uk_ivc_user_carrier (tenant_no, user_no, carrier_type, carrier_id),
  KEY idx_ivc_user_carrier_user (tenant_no, user_no)
) COMMENT='买家常用载具（平台级，跨主体通用）';
