-- 【测试专用，只在 H2 里跑】给 MpEndpointAuthTest 的匿名探测一个真实存在的料号。
--
-- 探测用占位符的话「没有这个料号」(10404) 会挡在鉴权之前，判不出
-- GET /mp/elec/part/{partNo} 是不是游客可看 —— 只能塞进「待确认」桶，而那个桶只许变短。
-- 生产库不需要它：真实料号由供应商上传长出来。
-- 版本号取 1000，与 db/elec 的迁移号永不相撞（那边从 V1 往上走）。
INSERT IGNORE INTO elc_part (part_no, mpn, mpn_norm, mfr_code, source, status)
VALUES ('EP-PROBE-0001', 'PROBE0001', 'PROBE0001', 'UNKNOWN', 'OPS', 'ACTIVE');
