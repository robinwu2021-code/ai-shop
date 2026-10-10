-- sys_region.level 的列注释补上 VILLAGE。
--
-- 只改注释，不改结构：类型 VARCHAR(16)、NOT NULL 与 V30 建表时逐字一致
-- （MODIFY COLUMN 会整条替换定义，少写一个 NOT NULL 就是一次静默的结构变更）。
--
-- 为什么这条注释值得动一次迁移：
--   V30 建表那天还没有村数据，注释写的是四级。VILLAGE 是后来随 62 万条村数据
--   进来的 —— RegionServiceImpl 有 4 处 .eq(getLevel, "VILLAGE")、
--   MasterDataPortImpl 判它、V211 自己还 UPDATE ... WHERE level = 'VILLAGE'。
--   实体 SysRegion 的字段 javadoc 写的就是五级，只有库里这行停在 V30。
--
--   而**列注释不是文档，是判据**：scripts/check-enum-fields.mjs 与
--   scripts/gen-glossary.mjs 的可见面扫描都拿它当取值域真源。少一个值的后果是
--   谁登记了这一列，对账就会把 VILLAGE 报成「端上编出来的词」——
--   一个确定的错答案，比没有答案更糟。
--
-- 五级的含义（与 SysRegion.LEVEL_* 常量一一对应）：
--   PROVINCE 省 / CITY 市 / DISTRICT 区县 / STREET 街道·乡镇 / VILLAGE 村委会·居委会
ALTER TABLE sys_region
    MODIFY COLUMN level VARCHAR(16) NOT NULL
        COMMENT 'PROVINCE / CITY / DISTRICT / STREET / VILLAGE';
