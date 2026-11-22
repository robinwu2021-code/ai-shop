-- 开票供应商改由系统统一决定（TDD-发票模块 §20）：主体配置的 gateway_code 为空 = 跟随市场默认供应商
-- （shop.invoice.regimes.<市场>.default-gateway），有值 = 商家自选。
-- 存量 STUB 是开通时页面的缺省值、不是商家的真实选择，置空让它们跟随默认；STUB 从未对外送出，已开的票不受影响。
ALTER TABLE ivc_regime_profile
  MODIFY gateway_code VARCHAR(32) NULL COMMENT '商家自选的开票供应商；空 = 跟随市场默认供应商';
UPDATE ivc_regime_profile SET gateway_code = NULL WHERE gateway_code = 'STUB';
