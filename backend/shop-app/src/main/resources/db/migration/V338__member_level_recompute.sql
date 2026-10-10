-- 会员分层每日重算（TDD-会员标签与定向营销 批 A）。
--
-- 重算按主体数近 90 天的支付笔数：mbr_member_source 里 source_type='ORDER' 的行
-- （每笔支付一行，按子订单号幂等）。已有的索引要么以 member_no 开头、要么以 store_no 为第二列，
-- 按「主体 + 类型 + 时间」扫都要回表过滤，主体一大就是整表扫。
--
-- 口径本身不落种子：LevelPolicy.DEFAULT（60 / 6 / 2）在代码里，与此前写死的值逐字一致，
-- 运营端改过之后才会在 sys_setting 里出现 member.level.policy 这一行。
ALTER TABLE mbr_member_source ADD INDEX idx_mbr_source_order (entity_no, source_type, occurred_at);
