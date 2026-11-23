-- 门店开票设定与常用品项（TDD-统一订单与开票资料 §2 P2，AC6 / AC11）。只建两张新表，不碰已有数据。
-- 课税别存对外口径 TAXABLE / ZERO_RATED / EXEMPT（与 POS 契约、发票明细一致），订单行冻结时再换成商品侧口径。
CREATE TABLE ivc_store_setting (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  store_no VARCHAR(64) NOT NULL,
  default_tax_type VARCHAR(16) NULL,
  invoice_timing VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  updated_by VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_store_setting (tenant_no, entity_no, store_no),
  CONSTRAINT ck_ivc_store_setting CHECK ((default_tax_type IS NULL OR default_tax_type IN ('TAXABLE','ZERO_RATED','EXEMPT'))
    AND invoice_timing IN ('ON_PAID','MANUAL','LATER'))
) COMMENT='门店开票设定：自订行默认课税别、开票时机（付清自动开 / 手动 / 稍后）；没有行 = 无默认、手动';

CREATE TABLE ivc_store_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  store_item_no VARCHAR(32) NOT NULL,
  tenant_no VARCHAR(32) NOT NULL,
  entity_no VARCHAR(64) NOT NULL,
  store_no VARCHAR(64) NOT NULL,
  name VARCHAR(256) NOT NULL,
  tax_type VARCHAR(16) NOT NULL,
  sort INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ivc_store_item_no (tenant_no, store_item_no),
  KEY idx_ivc_store_item_store (tenant_no, entity_no, store_no, sort),
  CONSTRAINT ck_ivc_store_item_tax CHECK (tax_type IN ('TAXABLE','ZERO_RATED','EXEMPT') AND enabled IN (0,1))
) COMMENT='门店常用品项（品名 + 课税别）：POS 输入金额时快选，带出品名与课税别；选零税率 / 免税不需另授权';
