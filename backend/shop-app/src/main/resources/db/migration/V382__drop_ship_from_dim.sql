-- 撤掉「发货地」维度（SD_SHIP_FROM）。
--
-- 用户 2026-10-07 提的四项里有发货地，V379 已建好并上线；2026-10-08 用户决定取消。
-- V379 已应用、冻结不能改，所以这里新写一版解绑 + 停用。
--
-- **只撤发货地，保留「品种」（SD_VARIETY）** —— 品种是同一批请求里要的，不动。
--
-- 为什么不 DELETE 维度行、只置 INACTIVE：维度一旦被哪个草稿/商品的 params 快照
-- 引用过（label 已落 prd_goods.params），删行不影响存量（快照是自带 label 的），
-- 但留行 + INACTIVE 更稳：对账/历史查得到，且将来想恢复只改一行 status。
-- 真正让它从「添加参数」里消失的是解绑 prd_category_spec。

-- 1) 从四个生鲜类目解绑：propsForCategory 读的是这张表，解绑即从参数区消失。
DELETE FROM prd_category_spec
 WHERE dim_no = 'SD_SHIP_FROM'
   AND category_no IN ('CAT110', 'CAT120', 'CAT121', 'CAT122');

-- 2) 维度本身停用：防止别处（别的类目将来绑、或模糊匹配）又把它捡起来。
UPDATE prd_spec_dim
   SET status = 'INACTIVE', updated_at = NOW(), updated_by = 'SYSTEM'
 WHERE dim_no = 'SD_SHIP_FROM';
