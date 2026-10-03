-- 值合并：把重复的规格值收敛成一条。
--
-- 值池会长出重复：商家自建「750克」而平台已有「750g」，或者同一档在两个时期
-- 被两个人各加了一次。**合并不是删除** —— 历史商品的 SKU 快照里存着被合并的那个编号，
-- 删掉之后那件货的规格就再也解释不出来了。
--
-- 所以：被合并的值 status='MERGED' + merged_into 指向保留的那一条。
-- 读侧按 ACTIVE 过滤（已有逻辑），历史数据顺着 merged_into 还能找回去。
ALTER TABLE prd_spec_value
  ADD COLUMN merged_into VARCHAR(64) DEFAULT NULL COMMENT '被合并到哪个值。非空即表示这一条已退役，status=MERGED';
