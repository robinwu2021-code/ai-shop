-- 入驻意向：商家自己写的行业（TDD-C端入驻意向-行业口径 AC3）。
--
-- 为什么不是把 sys_industry 加行：那张表是**准入口径** —— 每一行都挂着
-- 小微白名单、积分强制、执照经营范围的判定。为了收一句意向而往里加行，
-- 等于让「平台能接什么」跟着「有人想做什么」变。
--
-- 只在 industry = 'OTHER' 时有值；选了具体行业时后端置空
-- （否则改一次行业就留下一句对不上的话，审核的人不知道该信哪个）。
-- 24 字：这是一行「你做什么生意」的答案，不是简介 —— 长了运营也不会读。

ALTER TABLE mch_entity_apply
    ADD COLUMN industry_note VARCHAR(64) DEFAULT NULL COMMENT '商家手填的行业（仅 industry=OTHER 时有值）。意向口径，不参与任何准入判定';
