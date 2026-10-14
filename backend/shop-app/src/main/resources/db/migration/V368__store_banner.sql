-- 门店背景图（TDD-门店背景图）。
--
-- C 端门户顶部那一条：店主设了就是这张照片，没设就是主色浅底（2026-09-29 用户定）。
-- 空串 = 店主清掉了，NULL = 从没设过，读出来都是「没设」。
-- 写成一行：MediaRefCoverageTest 的 ADD COLUMN 扫描只认单行（多行写法它看不见）。
ALTER TABLE mch_store ADD COLUMN banner_url VARCHAR(512) NULL COMMENT '门店背景图 URL（门户顶部横幅）；空 = 没设，用主色浅底';
