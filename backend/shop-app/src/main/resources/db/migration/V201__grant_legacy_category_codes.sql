-- 给存量商家补类目授权：把上架闸门从「对所有人都关着」恢复到「按证放行」。
--
-- 现状（2026-08-23 线上）：379 件商品里 267 件落在带门槛的类目下，而所有商家的
-- mch_entity.category_codes 全是 NULL、mch_qualification 一条都没有 ——
-- 164 件待审/草稿永远上不了架，103 件在售的一旦下架就再也上不回来。
-- 商家看到的只有「你还没有该授权」，而平台此前根本没有受理入口。
--
-- 做法：按**这家店已有商品所在的类目**反推该给哪些码。这批店是平台自己拉进来的，
-- 先上车后补票 —— 补票那条路（B 端传证 + 运营按证授码）与本迁移同批上线。
--
-- ⚠️ **三个高危码不自动补**：药品零售、酒类、婴幼儿配方乳粉。它们背后是真正的
-- 许可证（药品经营许可证 / 含酒类的食品经营许可证 / 配方乳粉销售备案），
-- 错授的后果不是「多卖一类货」而是无证经营。实测数据里就有反例：
-- 老张粮油店（M0001）名下有 32 件药品类商品（批量导入的测试数据），
-- 照单全收就等于给一家粮油店发了卖药的许可。这两家（M0001/M0004）由运营看过
-- 营业执照与许可证之后手工授权 —— 康民药房（M0004）是真药房，走人工那条路。
--
-- 可回滚：本迁移只写 category_codes 这一列，且只写现在为空的那些行。
-- 撤销就是把这批 entity_no 的 category_codes 置回 NULL（审计日志里能查到是谁在什么时候批的）。

UPDATE mch_entity e
JOIN (
  SELECT g.entity_no,
         CONCAT('["', GROUP_CONCAT(DISTINCT c.required_code ORDER BY c.required_code SEPARATOR '","'), '"]') AS codes
    FROM prd_goods g
    JOIN prd_category c ON c.category_no = g.category_no
   WHERE c.required_code IS NOT NULL
     AND c.required_code <> ''
     AND c.required_code NOT IN ('DRUG_RETAIL', 'ALCOHOL', 'INFANT_FORMULA')
   GROUP BY g.entity_no
) x ON x.entity_no = e.entity_no
   SET e.category_codes = x.codes,
       e.updated_at = NOW(),
       e.updated_by = 'SYSTEM'
 WHERE e.category_codes IS NULL OR e.category_codes = '' OR e.category_codes = '[]';
