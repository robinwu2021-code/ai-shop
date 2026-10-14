-- 聚落的来源与曾用名。
--
-- 为什么现在要加 source：小区与村这两类数据从三条路进库 —— 地图 POI（商家点一下即建档）、
-- 官方村名录（免审直开）、以及人工提报。此前这三条路建出来的行长得一模一样，
-- 想知道「这一条是谁按什么依据建的」只能反查 cmt_community_apply 的 decided_by，
-- 而那张表只在有提报单时才有行。将来要收紧策略（比如「地图来源也要人审」）、
-- 或者按来源做数据刷新，没有这一列就无从下手。
--
-- alias 是为「合并」准备的：同一个小区改名（「阳光花园」→「阳光花园(北区)」）时，
-- 旧名要留下来参与查重，否则下一次地图联想会把它当成一个新地方再建一条。
ALTER TABLE cmt_community
    ADD COLUMN source VARCHAR(16) NULL COMMENT '来源：MAP 地图POI / OFFICIAL 官方名录 / MERCHANT 商家提报 / OPS 运营录入',
    ADD COLUMN alias VARCHAR(128) NULL COMMENT '曾用名，逗号分隔。改名后参与查重，避免同一个地方被再建一条';

-- 存量回填：能判的就判，判不出来的留空（空 = 不知道，比猜一个更诚实）。
-- 1) 有官方村码的必然来自名录
UPDATE cmt_community SET source = 'OFFICIAL' WHERE source IS NULL AND origin_code IS NOT NULL AND origin_code <> '';
-- 2) 坐标来源标了 AMAP 的来自地图
UPDATE cmt_community SET source = 'MAP' WHERE source IS NULL AND coords_source = 'AMAP';
-- 3) 台账里有一条对应的提报单，且不是系统自动决策的 —— 那是人提报的
UPDATE cmt_community c
SET source = 'MERCHANT'
WHERE c.source IS NULL
  AND EXISTS (SELECT 1
              FROM cmt_community_apply a
              WHERE a.community_no = c.community_no
                AND (a.decided_by IS NULL OR a.decided_by <> 'SYSTEM'));
