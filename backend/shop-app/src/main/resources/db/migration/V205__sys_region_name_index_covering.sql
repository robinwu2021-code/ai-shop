-- 把 V204 的 (level, name) 换成把过滤列都带上的 (level, enabled, audit_status, name)。
--
-- V204 加完之后优化器**没选它**：查询里 enabled 与 audit_status 是等值条件，
-- 而 idx_sys_region_level 正好是 (level, enabled)，于是它挑了那条，再回表逐行比 name ——
-- 线上实测 `LIKE '%福城%'` 仍要 2.66 秒；同一条查询加 FORCE INDEX 走 V204 只要 0.36 秒。
--
-- 与其在代码里写 FORCE INDEX（MyBatis 里要拼裸 SQL，而关键词来自用户输入），
-- 不如让索引本身把三个等值列都含住：这样前缀写法走范围扫，
-- 包含写法退化成这一段索引内的扫描，都不用回表。
DROP INDEX idx_sys_region_name ON sys_region;
CREATE INDEX idx_sys_region_name ON sys_region (level, enabled, audit_status, name);
