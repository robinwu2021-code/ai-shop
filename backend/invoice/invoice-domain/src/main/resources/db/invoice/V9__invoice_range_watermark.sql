-- 号段水位（TDD-发票模块 §15）：本期余量低于提醒值时提醒或向加值中心自动领号；期末前检查下期号段。
-- *_alert_key 记「已经为哪一期的哪一级报过」，同一事实只报一次，换期或补足后自动重新计。

ALTER TABLE ivc_regime_profile
  ADD COLUMN range_low_water INT NOT NULL DEFAULT 50 COMMENT '本期剩余号码低于此值即提醒 / 自动领号',
  ADD COLUMN auto_fetch_ranges TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 余量不足或期末前自动向加值中心领号（仅服务商配号的通道）',
  ADD COLUMN range_alert_key VARCHAR(32) NULL COMMENT '本期余量已报到的级别，如 11512:LOW / 11512:EMPTY',
  ADD COLUMN next_range_alert_key VARCHAR(16) NULL COMMENT '已报过「下期号段未备」的期别，如 11602';
