-- 门店级违规处置（TDD-运营端门店与商品治理 D2）。
-- mch_violation 此前只有主体级（entity_no）：一店出事只能连坐全店。
-- 加 store_no 而不是把门店号塞进 detail 文本 —— V9 建这张表的理由就是
-- 「处置需要的是事实」，而塞文本的事实在申诉时按门店检索不出来。
ALTER TABLE mch_violation
    ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '门店级处置时的门店号，空=主体级处置',
    ADD KEY idx_violation_store (store_no, at);

-- 平台压下的货架行要打标记：解除处置时只恢复「平台压下去的」，
-- 不动商家在处置期间自己下架的 —— 否则解除等于替商家做了一次全店上架。
ALTER TABLE prd_store_goods
    ADD COLUMN platform_suspended TINYINT NOT NULL DEFAULT 0 COMMENT '1=该行由平台强制下线压下，解除时恢复并清零';

-- 驳回/强制下架原因：它是商家能看到的那半边 —— 审计日志里的原因只有运营看得到，
-- 此前 audit() 驳回时原因哪里都不落，商家面对 REJECTED 只能猜要改什么。
ALTER TABLE prd_goods
    ADD COLUMN audit_reason VARCHAR(512) DEFAULT NULL COMMENT '最近一次驳回/强制下架的原因，过审时清空';
