-- 受众快照（TDD-会员标签与定向营销 批 B）。
--
-- 1) 活动引用人群时抄一份当时的条件（AC-9）。
--    此前活动受众存的是人群号，算价时按人群「此刻」的条件判 —— 商家改一次人群，
--    进行中的活动就换了一批受众，而「进行中不能改规则」这条在人群这一格上是空的。
--    为空 = 存量行，行为不变（仍按人群号当场算）。
ALTER TABLE pmt_activity_audience ADD COLUMN rule_snapshot TEXT DEFAULT NULL COMMENT '人群条件快照（SEGMENT 行）；为空按人群号当场算';

-- 2) 发券的受众由单个人群号扩为多项（标签 / 分层 / 人群 / 来源，取或）。
--    segment_no 保留：单个人群时照填，存量报表不断。
ALTER TABLE pmt_coupon_issue ADD COLUMN audience_json TEXT DEFAULT NULL COMMENT '发放那一刻的受众项 [{type,value}]';
