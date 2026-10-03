-- 公告人审单补两列：它是发给哪家店的、当时选的有效期是多久。
--
-- 两个都是真实的错，不是完备性补齐：
--
--  1. `store_no` —— 审核单只记商户号。通过时按商户取 `limit 1` 的那家店写回
--     （MerchantGovernServiceImpl），多店商家于是会把「南门店今天停电」
--     写到总店的公告上。写错的那一条没有任何报错，两边都看不出来。
--
--  2. `notice_until` —— 提交时选的「今天有效」没有地方存。通过之后只写正文，
--     有效期沿用门面表里的旧值（通常是空 = 长期），
--     于是「今天到货」被审出来之后就一直挂着 —— 而有效期这件事正是为了防这个。
--
-- 存量行 store_no 为空：老单子确实不知道是哪家店，通过时仍按默认店写回（代码里兜底），
-- 不猜一个门店号填进去 —— 猜错比空更难查。
ALTER TABLE mch_store_audit
    ADD COLUMN store_no VARCHAR(64) NULL COMMENT '这条公告发给哪家店。空 = 存量单，通过时按默认店写回',
    ADD COLUMN notice_until BIGINT(20) NULL COMMENT '提交时选的公告失效时刻（epoch 毫秒）。空 = 长期。kind=NOTICE 才有意义';
