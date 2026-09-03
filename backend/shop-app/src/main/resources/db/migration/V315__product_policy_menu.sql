-- 「禁售词」→「建品规则」（商品①）。
--
-- ⚠️ 本文件原叫 V313，与隔壁会话的 V313__pickup_point_coords_backfill 撞号 ——
-- 两份同号迁移在本地都不报错，**上生产 Flyway 启动即失败**。
-- SchemaDriftTest 有一道闸专门拦这个，是它拦下来的。
--
-- V312 建这一页时只有禁售词，现在同一页还管必填主图与标题长度 ——
-- 三条都是「提审前置约束」，都在提审那一刻校验、都拦在进审核队列之前。
-- 名字跟着内容走：叫「禁售词」的话，来配标题长度的人根本不会点进去。
--
-- **只改 name 不改 href**：路由是内部键，改它要动 TAB_KEYS、清单产物与已有链接，
-- 而这些换不来任何东西（与 M8 那次「类目 × 支付方式」改名同一条理由）。

UPDATE sys_function_point
   SET name = '建品规则', updated_at = NOW()
 WHERE point_code = 'OPS_PRODUCT__TAB_BANNED_WORD';
