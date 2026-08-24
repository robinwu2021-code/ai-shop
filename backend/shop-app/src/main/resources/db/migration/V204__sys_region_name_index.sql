-- 区划按名字搜索的索引。
--
-- 没有它时 `WHERE level='VILLAGE' AND name LIKE '%福城%'` 只能靠 (level, enabled) 缩到
-- 62 万行的村级分区上，再逐行取 name 比对 —— 线上实测 2.3 秒，而这条查询在
-- 选择器里是每次输入都要跑的。加上 (level, name) 之后前缀写法（'福城%'，也就是
-- 绝大多数人的打法）能走索引范围扫，包含写法也退化成索引内扫描而不是回表。
--
-- 放 level 在前：四种 level 的量级差着三个数量级，先按 level 切开，
-- 街道那一档只有 4 万行，扫过去几乎不花时间。
CREATE INDEX idx_sys_region_name ON sys_region (level, name);
